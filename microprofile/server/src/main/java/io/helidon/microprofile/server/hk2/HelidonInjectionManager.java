/*
 * Copyright (c) 2022, 2026 Oracle and/or its affiliates.
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

package io.helidon.microprofile.server.hk2;

import java.lang.System.Logger.Level;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.helidon.common.Api;

import jakarta.ws.rs.core.Application;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.jersey.inject.hk2.Hk2InjectionManagerFactory;
import org.glassfish.jersey.internal.inject.Binder;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.ForeignDescriptor;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.server.ResourceConfig;

import static java.util.Objects.requireNonNull;
import static org.glassfish.jersey.inject.hk2.Bindings.service;

/**
 * <p>Helidon implementation of an injection manager. Based on two underlying injection managers:
 * one to handle application specific classes (returned by the {@code Application} subclass
 * methods) and one that is shared among all the {@code Application} subclasses. Each application
 * has its own manager for application-specific registrations and uses the common manager for shared registrations.</p>
 *
 * <p>Creating a separate injection manager ensures that providers associated with a certain
 * subclass are not returned for others. There will be an instance of this class for each
 * {@code Application} subclass. This manager needs to get access to the values returned
 * by {@code getClasses} and {@code getSingletons} in order to provide the correct registration
 * semantics.</p>
 *
 * <p>The {@code hk2} package name is required by Jersey's CDI integration, which uses it to identify HK2 managers.</p>
 */
@Api.Internal
public final class HelidonInjectionManager implements InjectionManager {
    private static final System.Logger LOGGER = System.getLogger(HelidonInjectionManager.class.getName());

    // This is the class whose isInstance() method is used to test Binding instances in #returnedByApplication(Binding)
    // below. We can't just declare it, because it is not actually accessible to us. We could load it reflectively by
    // name, but this approach is more robust given the use case. See #returnedByApplication(Binding).
    private static final Class<?> INSTANCEBINDING_CLASS = service(new Object()).getClass();

    private final ResourceConfig resourceConfig;
    private final InjectionManager shared;
    private final InjectionManager forApplication;

    private HelidonInjectionManager(InjectionManager forApplication, InjectionManager shared, ResourceConfig resourceConfig) {
        this.forApplication = requireNonNull(forApplication);
        this.shared = requireNonNull(shared);
        this.resourceConfig = requireNonNull(resourceConfig);
    }

    /**
     * Create an injection manager for an application.
     *
     * @param forApplication manager for application-specific providers
     * @param shared manager for shared providers
     * @param resourceConfig application configuration
     * @return the injection manager
     */
    public static HelidonInjectionManager create(InjectionManager forApplication,
                                                InjectionManager shared,
                                                ResourceConfig resourceConfig) {
        return new HelidonInjectionManager(forApplication, shared, resourceConfig);
    }

    @Override
    public void completeRegistration() {
        this.shared.completeRegistration();
        this.forApplication.completeRegistration();
    }

    @Override
    public void shutdown() {
        this.shared.shutdown();
        this.forApplication.shutdown();
    }

    @Override
    public boolean isShutdown() {
        return this.shared.isShutdown() && this.forApplication.isShutdown();
    }

    /**
     * Registers classes returned by {@code getClasses} in {@code forApplication} and
     * all other classes in {@code shared}. This is done to keep separation between
     * global providers and those that are specific to an {@code Application} class.
     *
     * @param binding the binding to register.
     */
    @Override
    @SuppressWarnings("rawtypes") // Required by Jersey's InjectionManager signature.
    public void register(Binding binding) {
        if (returnedByApplication(binding)) {
            this.forApplication.register(binding);
            LOGGER.log(Level.TRACE, () -> "register forApplication " + this.forApplication + " " + toString(binding));
        } else {
            this.shared.register(binding);
            LOGGER.log(Level.TRACE, () -> "register shared " + this.shared + " " + toString(binding));
        }
    }

    @Override
    @SuppressWarnings("rawtypes") // Required by Jersey's InjectionManager signature.
    public void register(Iterable<Binding> descriptors) {
        descriptors.forEach(this::register);
    }

    @Override
    public void register(Binder binder) {
        binder.getBindings().forEach(this::register);
    }

    @Override
    public void register(Object provider) throws IllegalArgumentException {
        if (getSingletons().contains(provider)) {
            this.forApplication.register(provider);
            LOGGER.log(Level.TRACE, () -> "register forApplication " + this.forApplication + " " + provider);
        } else {
            this.shared.register(provider);
            LOGGER.log(Level.TRACE, () -> "register shared " + this.forApplication + " " + provider);
        }
    }

    @Override
    public boolean isRegistrable(Class<?> clazz) {
        return this.shared.isRegistrable(clazz) || this.forApplication.isRegistrable(clazz);
    }

    @Override
    public <T> T create(Class<T> createMe) {
        try {
            return this.shared.create(createMe);
        } catch (Throwable t) {
            return this.forApplication.create(createMe);
        }
    }

    @Override
    public <T> T createAndInitialize(Class<T> createMe) {
        try {
            return this.shared.createAndInitialize(createMe);
        } catch (Throwable t) {
            return this.forApplication.createAndInitialize(createMe);
        }
    }

    /**
     * Collects all service holders, including those registered in the {@code shared}
     * and the {@code forApplication}.
     *
     * @param contractOrImpl contract or implementation class.
     * @param qualifiers     the qualifiers.
     * @param <T>            parameter type.
     * @return list of service holders.
     */
    @Override
    public <T> List<ServiceHolder<T>> getAllServiceHolders(Class<T> contractOrImpl, Annotation... qualifiers) {
        List<ServiceHolder<T>> sharedList = this.shared.getAllServiceHolders(contractOrImpl, qualifiers);
        if (LOGGER.isLoggable(Level.TRACE)) {
            sharedList.forEach(sh -> LOGGER.log(Level.TRACE, "getAllServiceHolders shared "
                    + this.shared + " "
                    + sh.getContractTypes().iterator().next()));
        }

        List<ServiceHolder<T>> forApplicationList = this.forApplication.getAllServiceHolders(contractOrImpl, qualifiers);

        if (LOGGER.isLoggable(Level.TRACE)) {
            forApplicationList.forEach(sh -> LOGGER.log(Level.TRACE, "getAllServiceHolders forApplication "
                    + this.forApplication + " "
                    + sh.getContractTypes().iterator().next()));
        }
        List<ServiceHolder<T>> result = new ArrayList<>(sharedList);
        result.addAll(forApplicationList);
        return result;
    }

    @Override
    public <T> T getInstance(Class<T> contractOrImpl, Annotation... qualifiers) {
        T t = this.shared.getInstance(contractOrImpl, qualifiers);
        return t != null ? t : this.forApplication.getInstance(contractOrImpl, qualifiers);
    }

    @Override
    public <T> T getInstance(Class<T> contractOrImpl, String classAnalyzer) {
        T t = this.shared.getInstance(contractOrImpl, classAnalyzer);
        return t != null ? t : this.forApplication.getInstance(contractOrImpl, classAnalyzer);
    }

    @Override
    public <T> T getInstance(Class<T> contractOrImpl) {
        T t = this.shared.getInstance(contractOrImpl);
        return t != null ? t : this.forApplication.getInstance(contractOrImpl);
    }

    @Override
    public <T> T getInstance(Type contractOrImpl) {
        T t = this.shared.getInstance(contractOrImpl);
        return t != null ? t : this.forApplication.getInstance(contractOrImpl);
    }

    @Override
    public Object getInstance(ForeignDescriptor foreignDescriptor) {
        Object o = this.shared.getInstance(foreignDescriptor);
        return o != null ? o : this.forApplication.getInstance(foreignDescriptor);
    }

    @Override
    @SuppressWarnings("rawtypes") // Required by Jersey's InjectionManager signature.
    public ForeignDescriptor createForeignDescriptor(Binding binding) {
        try {
            return this.shared.createForeignDescriptor(binding);
        } catch (Throwable t) {
            return this.forApplication.createForeignDescriptor(binding);
        }
    }

    @Override
    public <T> List<T> getAllInstances(Type contractOrImpl) {
        List<T> result = new ArrayList<>();
        result.addAll(this.shared.getAllInstances(contractOrImpl));
        result.addAll(this.forApplication.getAllInstances(contractOrImpl));
        return result;
    }

    @Override
    public void inject(Object injectMe) {
        try {
            this.shared.inject(injectMe);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Injection failed for " + injectMe + " using shared", t);
            this.forApplication.inject(injectMe);
        }
    }

    @Override
    public void inject(Object injectMe, String classAnalyzer) {
        try {
            this.shared.inject(injectMe, classAnalyzer);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Injection failed for " + injectMe + " using shared", t);
            this.forApplication.inject(injectMe, classAnalyzer);
        }
    }

    @Override
    public void preDestroy(Object preDestroyMe) {
        this.shared.preDestroy(preDestroyMe);
        this.forApplication.preDestroy(preDestroyMe);
    }

    /**
     * Convenience method to display a binding in logging messages.
     *
     * @param b the binding
     * @return string representation of binding
     */
    private static String toString(Binding<?, ?> b) {
        StringBuilder sb = new StringBuilder();
        b.getContracts().forEach(c -> sb.append(" Cont ").append(c));
        if (b.getImplementationType() != null) {
            sb.append("\n\tImpl ").append(b.getImplementationType());
        }
        return sb.toString();
    }

    /**
     * Calls {@code getClasses} method in resource config.
     *
     * @return set of classes returned from {@code Application} object.
     */
    private Set<Class<?>> getClasses() {
        Application application = this.resourceConfig.getApplication();
        return application != null ? this.resourceConfig.getClasses() : Collections.emptySet();
    }

    /**
     * Calls {@code getSingletons} method in resource config.
     *
     * @return set of singletons returned from {@code Application} object.
     */
    private Set<Object> getSingletons() {
        Application application = this.resourceConfig.getApplication();
        return application != null ? this.resourceConfig.getSingletons() : Collections.emptySet();
    }

    /**
     * Checks if any of the contracts are returned by {@code getClasses()} or the bound instance
     * is returned by {@code getSingletons}. For a provider such as a filter,
     * the contracts will include both the class implementing the filter,
     * returned by {@code getClasses()}, as well as the JAX-RS API filter interface.
     *
     * @param binding injection manager binding
     * @return outcome of test
     */
    private boolean returnedByApplication(Binding<?, ?> binding) {
        if (binding.getContracts().stream().anyMatch(c -> getClasses().contains(c))) {
            return true;
        }

        Set<Object> singletons = this.getSingletons();
        if (singletons.isEmpty() || !INSTANCEBINDING_CLASS.isInstance(binding)) {
            return false;
        }

        // Jersey hides InstanceBinding on the module path. Translate it through the public HK2 descriptor API.
        // Only instance bindings reach this point: create returns their existing object, without constructing a bean.
        // Keep this inspection separate from the application and shared managers so it cannot register providers in either.
        InjectionManager inspection = new Hk2InjectionManagerFactory().create(null);
        try {
            ActiveDescriptor<?> descriptor = (ActiveDescriptor<?>) inspection.createForeignDescriptor(binding).get();
            return singletons.contains(descriptor.create(null));
        } finally {
            inspection.shutdown();
        }
    }
}
