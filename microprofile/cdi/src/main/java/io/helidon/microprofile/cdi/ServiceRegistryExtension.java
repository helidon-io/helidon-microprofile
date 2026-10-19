/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.microprofile.cdi;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.helidon.common.Api;
import io.helidon.common.Weighted;
import io.helidon.common.Weights;
import io.helidon.common.context.Context;
import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.config.Config;
import io.helidon.metadata.reflection.AnnotationFactory;
import io.helidon.metadata.reflection.TypeFactory;
import io.helidon.service.registry.DependencyContext;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.InterceptionMetadata;
import io.helidon.service.registry.Lookup;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceDescriptor;
import io.helidon.service.registry.ServiceInfo;
import io.helidon.service.registry.ServiceInstance;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryException;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessBean;
import jakarta.enterprise.inject.spi.ProcessManagedBean;
import jakarta.enterprise.inject.spi.ProcessProducer;
import jakarta.enterprise.inject.spi.ProcessSyntheticBean;
import jakarta.enterprise.inject.spi.configurator.AnnotatedConstructorConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedMethodConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;
import jakarta.enterprise.inject.spi.configurator.BeanConfigurator;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.weld.literal.NamedLiteral;

import static io.helidon.common.context.Contexts.context;
import static io.helidon.common.context.Contexts.globalContext;
import static io.helidon.common.context.Contexts.runInContext;
import static io.helidon.microprofile.cdi.SeContainerInitializers.registerCleanupAction;
import static io.helidon.service.registry.Service.Named.DEFAULT_NAME;
import static io.helidon.service.registry.Service.Named.WILDCARD_NAME;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;
import static java.util.Objects.requireNonNull;

/**
 * CDI extension that exposes service registry types as CDI beans and registers eligible CDI beans in the service registry.
 */
@Api.Stable
public class ServiceRegistryExtension implements Extension {
    // this is needed for a workaround; we cannot depend on security to avoid cyclic dependency
    private static final ResolvedType SECURITY = ResolvedType.create("io.helidon.security.Security");
    private static final ResolvedType OPEN_TELEMETRY = ResolvedType.create("io.opentelemetry.api.OpenTelemetry");
    // Helidon tracer producers must be excluded, as telemetry adds the application tracer directly
    private static final ResolvedType TRACER = ResolvedType.create("io.helidon.tracing.Tracer");
    private static final TypeName CDI_NAMED_TYPE = TypeName.create(Named.class);
    /*
    - we add all Helidon Service Registry qualifiers as CDI qualifiers (registerTypes)
    - we add ServiceRegistry itself as a bean (afterBeanDiscovery)
    - we add CDI beans to service registry (from managed beans, producers, and synthetic beans)
    - we add registry services to CDI
     */
    private static final System.Logger LOGGER = System.getLogger(ServiceRegistryExtension.class.getName());
    private static final int SYNTHETIC_BEAN_PRIORITY = 0;
    // higher than default - CDI beans are "more important" as we run in CDI
    private static final double WEIGHT = Weighted.DEFAULT_WEIGHT + 10;

    private final Set<CdiServiceId> processedBeans;
    private final RegistryLifecycle registryLifecycle;

    private ServiceRegistry serviceRegistry;

    /**
     * Creates an extension whose registry is selected during Helidon CDI initialization.
     *
     * <p>An existing contextual or MP-owned registry is borrowed; otherwise a registry is created and owned by this
     * extension.</p>
     */
    public ServiceRegistryExtension() {
        super();
        this.processedBeans = new HashSet<>();
        this.registryLifecycle = new RegistryLifecycle();
    }

    void shutdown() {
        this.registryLifecycle.shutdown();
    }

    void obtainServiceRegistry(@Observes @Priority(PLATFORM_BEFORE) BeforeBeanDiscovery bbd) {
        this.serviceRegistry = this.registryLifecycle.start();
    }

    void registerTypes(@Observes @Priority(PLATFORM_AFTER) BeforeBeanDiscovery bbd) {
        List<ServiceInfo> allServices = this.serviceRegistry.lookupServices(Lookup.EMPTY);

        Set<TypeName> addedQualifiers = new HashSet<>();

        for (ServiceInfo service : allServices) {
            // add all qualifiers
            for (Qualifier qualifier : service.qualifiers()) {
                TypeName typeName = qualifier.typeName();
                if (addedQualifiers.add(typeName)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Annotation> q = (Class<? extends Annotation>) TypeFactory.toClass(typeName);
                    bbd.addQualifier(q);
                }
            }
        }
    }

    void processManagedBean(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER) ProcessManagedBean<?> pb,
                            BeanManager bm) {
        this.doProcessBean(bm, pb);
    }

    void processSyntheticBean(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER) ProcessSyntheticBean<?> pb,
                              BeanManager bm) {
        if (pb.getSource() == this) {
            return;
        }
        this.doProcessBean(bm, pb);
    }

    void processProducer(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER) ProcessProducer<?, ?> pb,
                         BeanManager bm) {
        // make sure we also add produced stuff
        var member = pb.getAnnotatedMember();
        Set<Type> typeClosure = member.getTypeClosure();
        Set<Annotation> annotations = member.getAnnotations();

        Set<Qualifier> qualifiers = findRegistryQualifiers(annotations);
        TypeName beanType = TypeName.create(member.getDeclaringType().getJavaClass());

        for (Type type : typeClosure) {
            addCdiProducer(bm, new CdiProducer(beanType, type, annotations, qualifiers));
        }
    }

    void registryToCdi(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER + 2000) AfterBeanDiscovery abd,
                       BeanManager bm) {
        var registry = this.serviceRegistry;
        List<ServiceInfo> allServices = registry.lookupServices(Lookup.EMPTY);
        Set<UniqueBean> processedTypes = new HashSet<>();

        for (ServiceInfo service : allServices) {
            if (service instanceof CdiBeanDescriptor || service instanceof CdiProducerDescriptor) {
                // we do not want to re-insert CDI beans into CDI, obviously
                continue;
            }
            if (service.contracts().contains(SECURITY)) {
                // workaround - we must use `SecurityCdiExtension`, as it has public methods with security builder...
                continue;
            }
            if (service.contracts().contains(TRACER)) {
                // workaround - we must use `TelemetryCdiExtension`
                continue;
            }
            if (service.contracts().contains(OPEN_TELEMETRY)) {
                // workaround = use `TelemetryCdiExtension`
                continue;
            }
            addServiceInfo(abd, bm, registry, processedTypes, service, TypeFactory.toClass(service.providedType()));
        }

        // add support for injecting the service registry itself into CDI beans
        abd.addBean()
                .id("helidon-service-registry")
                .scope(ApplicationScoped.class)
                .beanClass(ServiceRegistry.class)
                .addType(ServiceRegistry.class)
                .createWith(_ -> this.serviceRegistry);
    }

    void shutdownServiceRegistry(@Observes @Priority(PLATFORM_AFTER) BeforeShutdown event) {
        this.shutdown();
    }

    private static io.helidon.common.types.Annotation mapNamed(io.helidon.common.types.Annotation annotation) {
        if (annotation.typeName().equals(CDI_NAMED_TYPE)) {
            return io.helidon.common.types.Annotation.builder()
                    .typeName(Service.Named.TYPE)
                    .update(it -> annotation.value().ifPresent(it::value))
                    .build();
        }
        return annotation;
    }

    private static Set<Qualifier> findRegistryQualifiers(Set<Annotation> annotations) {

        return Stream.concat(
                annotations.stream()
                        .filter(it -> it.annotationType().isAnnotationPresent(Service.Qualifier.class))
                        .map(AnnotationFactory::create)
                        .map(Qualifier::create),
                annotations.stream()
                        .filter(it -> it.annotationType().equals(Named.class))
                        .map(AnnotationFactory::create)
                        .map(ServiceRegistryExtension::mapNamed)
                        .map(Qualifier::create)
        ).collect(Collectors.toUnmodifiableSet());
    }

    private void addServiceDescriptor(ServiceDescriptor<?> descriptor) {
        requireNonNull(descriptor, "descriptor");
        // Preserve access to other contextual values while directing this addition to our retained registry.
        Context registrationContext = Context.create(context().orElse(globalContext()));

        // GlobalServiceRegistry first uses this exact key to find the Context containing the registry.
        // Point it here so that "global" lookup uses this context instead of an inherited or JVM-wide location.
        registrationContext.register("helidon-registry-static-context", registrationContext);

        // It then uses this exact key to find the ServiceRegistry within that selected context.
        registrationContext.register("helidon-registry", this.serviceRegistry);

        // runInContext restores the previous binding on return or failure.
        // A successfully added descriptor remains in the registry after the temporary binding ends.
        runInContext(registrationContext, () -> Services.add(descriptor));
    }

    // all the parameters are needed, private method
    @SuppressWarnings("checkstyle:ParameterNumber")
    private <T> void addNamedBean(BeanManager bm,
                                  AfterBeanDiscovery abd,
                                  AnnotatedType<T> annotatedType,
                                  TypeName serviceType,
                                  Class<T> beanClass,
                                  Set<Type> typeClosure,
                                  Class<? extends Annotation> cdiScope,
                                  String name,
                                  Supplier<T> instanceSupplier) {
        if (typeClosure.isEmpty()) {
            return;
        }
        BeanConfigurator<T> configurator = addBean(abd, serviceType, beanClass, typeClosure, cdiScope, "-" + name);
        beanCreateWith(bm, annotatedType, beanClass, configurator, instanceSupplier);
        configurator.addQualifier(new NamedLiteral(name));
    }

    private <T> BeanConfigurator<T> addBean(AfterBeanDiscovery abd,
                                            TypeName serviceType,
                                            Class<T> beanClass,
                                            Set<Type> typeClosure,
                                            Class<? extends Annotation> cdiScope,
                                            String idSuffix) {

        return abd.<T>addBean()
                .beanClass(beanClass)
                .types(typeClosure)
                .id("service-registry-" + serviceType.fqName() + idSuffix)
                .alternative(true)
                .priority(SYNTHETIC_BEAN_PRIORITY)
                .scope(cdiScope);
    }

    /**
     * Creates a bean from its instance.
     *
     * @param bm            CDI bean manager
     * @param annotatedType CDI annotated type
     * @param beanClass     the service provided type
     * @param configurator  CDI bean configurator
     * @param instance      service registry instance
     */
    private <T> void beanCreateWith(BeanManager bm,
                                    AnnotatedType<T> annotatedType,
                                    Class<T> beanClass,
                                    BeanConfigurator<T> configurator,
                                    Supplier<T> instance) {
        configurator.<T>createWith(ctx -> {
            return this.<T>interceptInstance(bm, beanClass, annotatedType, ctx, instance.get());
        });
    }

    private <T> T interceptInstance(BeanManager bm,
                                    Class<T> beanClass,
                                    AnnotatedType<T> annotatedType,
                                    CreationalContext<T> ctx,
                                    T instance) {
        var factory = bm.createInterceptionFactory(ctx, beanClass);
        if (annotatedType != null) {
            updateFromAnnotatedType(annotatedType, factory.configure());
        }
        return factory.createInterceptedInstance(instance);
    }

    private Set<Type> toTypes(Set<ResolvedType> usedContracts) {
        return usedContracts.stream()
                .map(ResolvedType::type)
                .map(TypeFactory::toType)
                .collect(Collectors.toUnmodifiableSet());
    }

    private <T> void updateFromAnnotatedType(AnnotatedType<T> type, AnnotatedTypeConfigurator<T> builder) {
        AnnotatedType<T> beforeChanges = builder.getAnnotated();

        // fix annotations on type itself
        fixAnnotations(type, beforeChanges, builder::add);

        for (AnnotatedMethod<? super T> method : type.getMethods()) {
            Predicate<AnnotatedMethod<? super T>> p = it -> it.getJavaMember().equals(method.getJavaMember());
            Optional<AnnotatedMethodConfigurator<? super T>> found = builder.filterMethods(p)
                    .findFirst();

            found.ifPresent(it -> fixAnnotations(method,
                                                 it.getAnnotated(),
                                                 it::add));
        }
        for (AnnotatedField<? super T> field : type.getFields()) {
            Predicate<AnnotatedField<? super T>> p = it -> it.getJavaMember().equals(field.getJavaMember());
            Optional<AnnotatedFieldConfigurator<? super T>> found = builder.filterFields(p)
                    .findFirst();

            found.ifPresent(it -> fixAnnotations(field,
                                                 it.getAnnotated(),
                                                 it::add));
        }
        for (AnnotatedConstructor<? super T> constructor : type.getConstructors()) {
            Predicate<AnnotatedConstructor<T>> p = it -> it.getJavaMember().equals(constructor.getJavaMember());
            Optional<AnnotatedConstructorConfigurator<T>> found = builder.filterConstructors(p)
                    .findFirst();

            found.ifPresent(it -> fixAnnotations(constructor,
                                                 it.getAnnotated(),
                                                 it::add));
        }
    }

    private void fixAnnotations(Annotated desiredAnnotated,
                                Annotated targetAnnotated,
                                Consumer<Annotation> configurator) {
        var targetAnnotations = targetAnnotated.getAnnotations();
        for (Annotation annotation : desiredAnnotated.getAnnotations()) {
            if (!targetAnnotations.contains(annotation)) {
                configurator.accept(annotation);
            }
        }
    }

    private void addCdiProducer(BeanManager bm, CdiProducer cdiProducer) {
        var type = cdiProducer.contract();
        ResolvedType contract = ResolvedType.create(type);
        if (contract.equals(TRACER)) {
            // Telemetry registers the concrete application tracer directly; a CDI producer descriptor cannot safely
            // serve async static registry lookups.
            return;
        }
        if (invalidContract(contract)) {
            // this is not a contract we can support
            return;
        }

        Class<? extends Annotation> scope = findCdiScope(bm, cdiProducer.annotations()).orElse(Dependent.class);
        Optional<TypeName> registryScope = toRegistryScope(scope);
        if (registryScope.isEmpty()) {
            return;
        }
        TypeName actualScope = registryScope.get();
        Annotation[] cdiQualifiers = findCdiQualifiers(bm, cdiProducer.annotations());

        try {
            this.addServiceDescriptor(new CdiProducerDescriptor(bm, actualScope, cdiProducer, cdiQualifiers));
        } catch (ServiceRegistryException e) {
            // the service is not used by service registry, so it cannot be added, or already used
            // make sure we can debug problems
            LOGGER.log(System.Logger.Level.TRACE, "Failed to register CDI producer for contract: "
                               + cdiProducer.contract().getTypeName() + "," + " producer: " + cdiProducer.beanType().fqName(),
                       e);
        }
    }

    private void addCdiBean(BeanManager bm, CdiBean cdiBean) {
        Bean<?> bean = cdiBean.bean();

        Class<? extends Annotation> scope = bean.getScope();
        Optional<TypeName> registryScope = toRegistryScope(scope);
        if (registryScope.isEmpty()) {
            return;
        }
        TypeName actualScope = registryScope.get();
        for (Type type : bean.getTypes()) {
            if (invalidContract(ResolvedType.create(type))) {
                // this is not a contract we can support
                continue;
            }
            try {
                this.addServiceDescriptor(new CdiBeanDescriptor(bm, cdiBean, type, actualScope));
            } catch (ServiceRegistryException e) {
                // the service was already used - we do not throw, as this may be expected, still log, to make
                // sure we can debug problems
                LOGGER.log(System.Logger.Level.TRACE, "Failed to register CDI bean for contract: " + type + ","
                        + " bean: " + bean, e);
            }
        }
    }

    // find all qualifiers present on the element
    private Annotation[] findCdiQualifiers(BeanManager bm, Set<Annotation> annotations) {
        return annotations.stream()
                .filter(it -> bm.isQualifier(it.annotationType()))
                .toArray(Annotation[]::new);
    }

    /*
    This can probably be done in some more clever way, though I am not sure what it would be
    The task: find the closest annotation with scope (either NormalScope or Scope depending on caller) annotation
    So if there is an annotation such as:
    @ApplicationScoped
    @interface MyScope {}
    we want to get ApplicationScoped.class, not MyScope.class
     */
    private Optional<Class<? extends Annotation>> findCdiScope(BeanManager bm, Set<Annotation> annotations) {
        // find any annotation with @Scope or @NormalScope meta-annotation (through inherited meta-annotations)
        for (Annotation annotation : annotations) {
            if (bm.isNormalScope(annotation.annotationType())) {
                // we need the actual annotation annotated with normal scope
                var metaAnnotated = findMetaAnnotated(annotation, NormalScope.class, new HashSet<>());
                if (metaAnnotated.isPresent()) {
                    return metaAnnotated.map(Annotation::annotationType);
                }
            }
            if (bm.isScope(annotation.annotationType())) {
                // we need the actual annotation annotated with scope
                var metaAnnotated = findMetaAnnotated(annotation, Scope.class, new HashSet<>());
                if (metaAnnotated.isPresent()) {
                    return metaAnnotated.map(Annotation::annotationType);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Annotation> findMetaAnnotated(Annotation annotation,
                                                   Class<? extends Annotation> annotationType,
                                                   Set<Class<? extends Annotation>> processed) {
        Class<? extends Annotation> aClass = annotation.annotationType();
        if (aClass.getDeclaredAnnotation(annotationType) != null) {
            return Optional.of(annotation);
        }
        if (!processed.add(aClass)) {
            return Optional.empty();
        }
        Annotation[] declaredAnnotations = aClass.getDeclaredAnnotations();
        for (Annotation declaredAnnotation : declaredAnnotations) {
            var found = findMetaAnnotated(declaredAnnotation, annotationType, processed);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private void doProcessBean(BeanManager bm, ProcessBean<?> pb) {

        Bean<?> bean = pb.getBean();

        // we need to be sure we register each bean only once
        CdiServiceId id = new CdiServiceId(bean.getBeanClass(), bean.hashCode());
        if (processedBeans.add(id)) {
            addCdiBean(bm, new CdiBean(id, bean, pb.getAnnotated()));
        }
    }

    private Optional<Qualifier> namedQualifier(Set<Qualifier> qualifiers) {
        for (Qualifier qualifier : qualifiers) {
            if (qualifier.typeName().equals(Service.Named.TYPE)) {
                return Optional.of(qualifier);
            }
        }
        return Optional.empty();
    }

    private boolean invalidContract(ResolvedType contract) {
        // ignore java.lang.Object
        // ignore wildcard types
        if (contract.type().equals(TypeNames.OBJECT)) {
            return true;
        }
        return hasWildcard(contract.type()) || hasGeneric(contract.type());
    }

    private boolean hasWildcard(TypeName type) {
        if (type.wildcard()) {
            return true;
        }

        if (!type.typeArguments().isEmpty()) {
            for (TypeName typeArgument : type.typeArguments()) {
                if (hasWildcard(typeArgument)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasGeneric(TypeName type) {
        if (type.generic()) {
            return true;
        }

        if (!type.typeArguments().isEmpty()) {
            for (TypeName typeArgument : type.typeArguments()) {
                if (hasGeneric(typeArgument)) {
                    return true;
                }
            }
        }

        return false;
    }

    private Optional<TypeName> toRegistryScope(Class<? extends Annotation> scope) {
        if (scope == ApplicationScoped.class) {
            return Optional.of(Service.Singleton.TYPE);
        }
        if (scope == Singleton.class) {
            return Optional.of(Service.Singleton.TYPE);
        }
        if (scope == RequestScoped.class) {
            return Optional.of(Service.PerRequest.TYPE);
        }
        if (scope == Dependent.class) {
            return Optional.of(Service.PerLookup.TYPE);
        }
        // we only support the known scopes, cannot map other
        return Optional.empty();
    }

    private Class<? extends Annotation> toCdiScope(ServiceInfo service) {
        TypeName scope = service.scope();
        if (scope.equals(Service.Singleton.TYPE)) {
            return ApplicationScoped.class;
        }
        if (scope.equals(Service.PerLookup.TYPE)) {
            return Dependent.class;
        }
        if (scope.equals(Service.PerRequest.TYPE)) {
            return RequestScoped.class;
        }
        // ignore scope, use dependent - this will always use service registry to create an instance, for each injection point
        return Dependent.class;
    }

    private <T> void addServiceInfo(AfterBeanDiscovery abd,
                                    BeanManager bm,
                                    ServiceRegistry registry,
                                    Set<UniqueBean> processedTypes,
                                    ServiceInfo service,
                                    Class<T> beanClass) {
        Set<ResolvedType> contracts = service.contracts();
        TypeName serviceType = service.serviceType();
        Set<ResolvedType> usedContracts = new HashSet<>();
        // first collect all contracts that we should advertise for this bean
        for (ResolvedType contract : contracts) {
            var uniqueBean = new UniqueBean(contract, service.qualifiers());
            if (!processedTypes.add(uniqueBean)) {
                // this contract is already advertised by a higher weighted service; CDI only supports one bean
                continue;
            }

            if (invalidContract(contract)) {
                // this is not a contract we can support
                continue;
            }

            usedContracts.add(contract);
        }

        TypeName serviceProvidedType = service.providedType();
        ResolvedType serviceProvidedResolved = ResolvedType.create(serviceProvidedType);
        if (processedTypes.add(new UniqueBean(serviceProvidedResolved, service.qualifiers()))
                && !invalidContract(serviceProvidedResolved)) {
            // we also advertise the service provided type (factories are NOT advertised)
            usedContracts.add(serviceProvidedResolved);
        }

        /*
        bean class: provided type (MyService, MyContract - never MyFactory)
        id: provided type fq name with our prefix
        scope: "guessed" from registry scope
         */

        // annotated type of our provided type, to support interception
        AnnotatedType<T> annotatedType = abd.getAnnotatedType(beanClass, serviceProvidedType.fqName());

        Optional<Qualifier> named = namedQualifier(service.qualifiers());
        /*
        Three options:
        1. no name, just un-named instance
        2. * name - services factory, need to list instances
        3. other - just a named instance
         */

        var typeClosure = toTypes(usedContracts);
        var cdiScope = toCdiScope(service);

        if (named.isEmpty()) {
            if (!typeClosure.isEmpty()) {
                // unnamed
                addBean(
                        abd,
                        serviceType,
                        beanClass,
                        typeClosure,
                        cdiScope,
                        "")
                        .createWith(ctx -> {
                            T instance = registry.get(serviceProvidedType);
                            return interceptInstance(bm, beanClass, annotatedType, ctx, instance);
                        });
            }
        } else {
            if (WILDCARD_NAME.equals(named.get().value().orElse(WILDCARD_NAME))) {
                // services factory (or other factory type)
                // we are interested in all instances provided by this specific factory
                Lookup lookup = Lookup.builder()
                        .addContract(serviceProvidedType)
                        .serviceType(serviceType)
                        .build();
                List<ServiceInstance<T>> instances = registry.lookupInstances(lookup);
                // add each named instance as its own bean
                Set<String> names = new HashSet<>();
                for (ServiceInstance<T> instance : instances) {
                    String name = namedQualifier(instance.qualifiers())
                            .flatMap(Qualifier::stringValue)
                            .orElse(DEFAULT_NAME);
                    if (!names.add(name)) {
                        // each name can only be added once
                        continue;
                    }
                    addNamedBean(bm,
                                 abd,
                                 annotatedType,
                                 serviceType,
                                 beanClass,
                                 typeClosure,
                                 cdiScope,
                                 name,
                                 instance);
                }
            } else {
                String name = named.get().stringValue().orElseThrow();
                addNamedBean(bm,
                             abd,
                             annotatedType,
                             serviceType,
                             beanClass,
                             typeClosure,
                             cdiScope,
                             name,
                             () -> registry.get(Lookup.builder()
                                                        .addQualifier(named.get())
                                                        .addContract(serviceProvidedType)
                                                        .build()));
            }
        }
    }

    private record CdiServiceId(Class<?> beanClass, int hash) { }

    private record UniqueBean(ResolvedType contract, Set<Qualifier> qualifiers) {
    }

    private record CdiBean(CdiServiceId id,
                           Bean<?> bean,
                           Annotated annotated) {
    }

    private record CdiProducer(TypeName beanType,
                               Type contract,
                               Set<Annotation> annotations,
                               Set<Qualifier> qualifiers) {
    }

    private static class CdiProducerDescriptor implements ServiceDescriptor<Object> {
        private static final TypeName DESCRIPTOR_TYPE = TypeName.create(CdiProducerDescriptor.class);

        private final TypeName serviceType;
        private final Set<ResolvedType> contracts;
        private final Set<Qualifier> qualifiers;
        private final BeanManager bm;
        private final Type theType;
        private final TypeName scope;
        private final Annotation[] cdiQualifiers;

        CdiProducerDescriptor(BeanManager bm,
                              TypeName scope,
                              CdiProducer producer,
                              Annotation[] cdiQualifiers) {
            this.bm = bm;
            this.scope = scope;
            this.theType = producer.contract();
            this.serviceType = producer.beanType();
            this.contracts = Set.of(ResolvedType.create(producer.contract()));
            this.qualifiers = producer.qualifiers();
            this.cdiQualifiers = cdiQualifiers;
        }

        @Override
        public TypeName serviceType() {
            return serviceType;
        }

        @Override
        public TypeName descriptorType() {
            return DESCRIPTOR_TYPE;
        }

        @Override
        public Set<ResolvedType> contracts() {
            return contracts;
        }

        @Override
        public Set<Qualifier> qualifiers() {
            return qualifiers;
        }

        @Override
        public Object instantiate(DependencyContext ctx, InterceptionMetadata interceptionMetadata) {
            var beans = bm.getBeans(theType, cdiQualifiers);
            if (beans.isEmpty()) {
                throw new ServiceRegistryException("CDI did not provide any beans for producer. Bean: " + serviceType().fqName()
                                                           + "; type: " + theType);
            }
            Bean<?> bean = beans.iterator().next();

            return bm.getReference(bean, theType, bm.createCreationalContext(bean));
        }

        @Override
        public TypeName scope() {
            return scope;
        }

        @Override
        public double weight() {
            return WEIGHT;
        }

        @Override
        public String toString() {
            return "CDI producer descriptor for: Contract: " + theType.getTypeName()
                    + "; producer: " + serviceType.fqName();
        }
    }

    private static class CdiBeanDescriptor implements ServiceDescriptor<Object> {
        private static final TypeName DESCRIPTOR_TYPE = TypeName.create(CdiBeanDescriptor.class);
        private final TypeName serviceType;
        private final Set<ResolvedType> contracts;
        private final Set<Qualifier> qualifiers;
        private final Bean<?> bean;
        private final BeanManager bm;
        private final Type theType;
        private final double weight;
        private final TypeName scope;

        CdiBeanDescriptor(BeanManager bm,
                          CdiBean cdiBean,
                          Type contract,
                          TypeName scope) {
            this.bm = bm;
            this.bean = cdiBean.bean();
            this.scope = scope;
            this.theType = contract;
            this.serviceType = TypeName.create(bean.getBeanClass());
            this.weight = Weights.find(bean.getBeanClass(), WEIGHT);
            this.contracts = Set.of(ResolvedType.create(contract));
            this.qualifiers = findRegistryQualifiers(cdiBean.annotated().getAnnotations());
        }

        @Override
        public TypeName serviceType() {
            return serviceType;
        }

        @Override
        public TypeName descriptorType() {
            return DESCRIPTOR_TYPE;
        }

        @Override
        public Set<ResolvedType> contracts() {
            return contracts;
        }

        @Override
        public Set<Qualifier> qualifiers() {
            return qualifiers;
        }

        @Override
        public Object instantiate(DependencyContext ctx, InterceptionMetadata interceptionMetadata) {
            return bm.getReference(bean, theType, bm.createCreationalContext(bean));
        }

        @Override
        public TypeName scope() {
            return scope;
        }

        @Override
        public double weight() {
            return weight;
        }

        @Override
        public String toString() {
            return "CDI bean descriptor for: Contract: " + theType.getTypeName()
                    + "; bean: " + serviceType.fqName()
                    + " (" + weight + ")";
        }
    }

    private static final class RegistryLifecycle {
        private static final String GLOBAL_CONTEXT_CLASSIFIER = "helidon-registry-static-context";
        private static final String GLOBAL_REGISTRY_CLASSIFIER = "helidon-registry";
        private static final ReentrantLock LOCK = new ReentrantLock();

        private static RegistryShutdownHandler shutdownHandler;

        private RegistryShutdownHandler createdShutdownHandler;

        private RegistryLifecycle() {
            super();
        }

        private static Config config() {
            org.eclipse.microprofile.config.Config config = ConfigProvider.getConfig();
            return config instanceof Config helidonConfig ? helidonConfig : config.unwrap(Config.class);
        }

        private static void configureRegistry(ServiceRegistry registry, Config config) {
            Context setupContext = Context.create();
            setupContext.register(GLOBAL_CONTEXT_CLASSIFIER, setupContext);
            setupContext.register(GLOBAL_REGISTRY_CLASSIFIER, registry);
            runInContext(setupContext, () -> Services.set(Config.class, config));
        }

        private static Optional<ServiceRegistry> contextualRegistry() {
            return context()
                    .flatMap(context -> context.get(GLOBAL_CONTEXT_CLASSIFIER, Context.class))
                    .filter(context -> context != globalContext())
                    .flatMap(context -> context.get(GLOBAL_REGISTRY_CLASSIFIER, ServiceRegistry.class));
        }

        private ServiceRegistry start() {
            Optional<ServiceRegistry> contextualRegistry = contextualRegistry();
            if (contextualRegistry.isPresent()) {
                return contextualRegistry.get();
            }

            LOCK.lock();
            try {
                if (shutdownHandler != null) {
                    if (!shutdownHandler.shutdown.get()
                            && GlobalServiceRegistry.configured()
                            && GlobalServiceRegistry.registry() == shutdownHandler.registry) {
                        return shutdownHandler.registry;
                    }
                    throw new ServiceRegistryException("Helidon MP service registry is already started.");
                }
                Config config = config();
                ServiceRegistryManager manager = null;
                try {
                    if (GlobalServiceRegistry.configured()) {
                        throw new ServiceRegistryException(
                                "Application-global service registry was initialized before Helidon MP startup.");
                    }
                    manager = ServiceRegistryManager.create();
                    ServiceRegistry registry = manager.registry();
                    configureRegistry(registry, config);
                    ServiceRegistry selectedRegistry = GlobalServiceRegistry.registry(() -> registry);
                    if (selectedRegistry != registry) {
                        throw new ServiceRegistryException(
                                "Application-global service registry was initialized while Helidon MP was starting.");
                    }

                    RegistryShutdownHandler newShutdownHandler = new RegistryShutdownHandler(manager, registry);
                    shutdownHandler = newShutdownHandler;
                    this.createdShutdownHandler = newShutdownHandler;
                    registerCleanupAction(this::shutdown);
                    return registry;
                } catch (RuntimeException | Error e) {
                    shutdownHandler = null;
                    if (manager != null) {
                        try {
                            manager.shutdown();
                        } catch (RuntimeException | Error cleanupFailure) {
                            e.addSuppressed(cleanupFailure);
                        }
                    }
                    throw e;
                }
            } finally {
                LOCK.unlock();
            }
        }

        private void shutdown() {
            LOCK.lock();
            try {
                if (this.createdShutdownHandler != null) {
                    this.createdShutdownHandler.shutdown();
                    if (shutdownHandler == this.createdShutdownHandler) {
                        // Clear the static shutdownHandler only if it still "belongs" to us.
                        shutdownHandler = null;
                    }
                }
            } finally {
                LOCK.unlock();
            }
        }

        private static final class RegistryShutdownHandler {
            private final ServiceRegistryManager manager;
            private final ServiceRegistry registry;
            private final AtomicBoolean shutdown = new AtomicBoolean();

            private RegistryShutdownHandler(ServiceRegistryManager manager, ServiceRegistry registry) {
                this.manager = manager;
                this.registry = registry;
            }

            private void shutdown() {
                if (this.shutdown.compareAndSet(false, true)) {
                    try {
                        this.manager.shutdown();
                    } catch (Exception e) {
                        LOGGER.log(System.Logger.Level.ERROR, "Failed to shutdown Helidon MP Service Registry", e);
                    }
                }
            }
        }
    }
}
