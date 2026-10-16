/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.microprofile.scheduling;

import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import io.helidon.common.configurable.ScheduledThreadPoolSupplier;
import io.helidon.config.Config;
import io.helidon.config.ConfigBuilderSupport;
import io.helidon.scheduling.Cron;
import io.helidon.scheduling.Invocation;
import io.helidon.scheduling.Scheduling;
import io.helidon.scheduling.Task;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessManagedBean;
import jakarta.enterprise.inject.spi.WithAnnotations;

import static io.helidon.microprofile.cdi.SeContainerInitializers.onInitializationFailure;
import static io.helidon.microprofile.config.core.MpConfig.toHelidonConfig;
import static jakarta.interceptor.Interceptor.Priority.LIBRARY_BEFORE;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static org.eclipse.microprofile.config.ConfigProvider.getConfig;

/**
 * Scheduling CDI Extension.
 */
public class SchedulingCdiExtension implements Extension {
    private static final System.Logger LOGGER = System.getLogger(SchedulingCdiExtension.class.getName());
    private final Queue<AnnotatedMethod<?>> methods;
    private final Map<AnnotatedMethod<?>, Bean<?>> beans;
    private final Queue<ScheduledExecutorService> executors;
    private Config config;
    private Config schedulingConfig;

    /**
     * Required public constructor for {@link java.util.ServiceLoader}.
     */
    public SchedulingCdiExtension() {
        super();
        this.methods = new LinkedList<>();
        this.beans = new HashMap<>();
        this.executors = new LinkedList<>();
    }

    @SuppressWarnings("unchecked")
    static <T> T lookup(Bean<?> bean, BeanManager beanManager) {
        jakarta.enterprise.context.spi.Context context = beanManager.getContext(bean.getScope());
        Object instance = context.get(bean);

        if (instance == null) {
            CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
            instance = beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
        }

        if (instance == null) {
            throw new DeploymentException("Instance of bean " + bean.getName() + " not found");
        }

        return (T) instance;
    }

    static void invokeWithOptionalParam(Object instance, Method method, Invocation invocation)
            throws InvocationTargetException, IllegalAccessException {

        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<? extends Invocation> invClazz = invocation.getClass();

        if (parameterTypes.length > 1
                || parameterTypes.length > 0 && !parameterTypes[0].isAssignableFrom(invClazz)) {

            throw new DeploymentException(
                    String.format("Unsupported param types for scheduled method %s, none or %s is supported.",
                                  method.getName(), invClazz.getName())
            );
        }

        if (parameterTypes.length == 0) {
            method.invoke(instance);
        } else {
            method.invoke(instance, invocation);
        }
    }

    void registerMethods(
            @Observes
            @WithAnnotations({
                    Scheduling.Cron.class,
                    Scheduling.FixedRate.class}) ProcessAnnotatedType<?> pat) {
        // Lookup scheduled methods
        pat.getAnnotatedType()
                .getMethods()
                .stream()
                .filter(this::hasScheduleAnnotation)
                .forEach(methods::add);
    }

    void onProcessBean(@Observes ProcessManagedBean<?> event) {
        // Gather bean references
        Bean<?> bean = event.getBean();
        Class<?> beanClass = bean.getBeanClass();
        for (AnnotatedMethod<?> am : methods) {
            if (beanClass == am.getDeclaringType().getJavaClass()) {
                beans.put(am, bean);
            }
        }
    }

    void invoke(@Observes @Priority(PLATFORM_AFTER + 4000) @Initialized(ApplicationScoped.class) Object event,
                BeanManager beanManager) {

        var scheduledThreadPoolbuilder = ScheduledThreadPoolSupplier.builder().config(schedulingConfig);

        if (scheduledThreadPoolbuilder.threadNamePrefix().isEmpty()) {
            scheduledThreadPoolbuilder.threadNamePrefix("scheduled-");
        }

        ScheduledExecutorService executorService = scheduledThreadPoolbuilder.build().get();
        executors.add(executorService);
        onInitializationFailure(this::terminate);

        for (AnnotatedMethod<?> am : methods) {
            Class<?> aClass = am.getDeclaringType().getJavaClass();
            Bean<?> bean = beans.get(am);
            if (bean == null) {
                continue;
            }
            Object beanInstance = lookup(bean, beanManager);
            Method method = am.getJavaMember();

            if (!method.trySetAccessible()) {
                throw new DeploymentException(String.format("Scheduled method %s#%s is not accessible!",
                                                            method.getDeclaringClass().getName(),
                                                            method.getName()));
            }

            if (am.isAnnotationPresent(Scheduling.Cron.class)
                    && am.isAnnotationPresent(Scheduling.FixedRate.class)) {
                throw new DeploymentException(String.format("Scheduled method %s#%s can have only one scheduling annotation.",
                                                            method.getDeclaringClass().getName(),
                                                            method.getName()));
            }

            Config methodConfig = config.get(aClass.getName() + "." + method.getName() + ".schedule");

            if (am.isAnnotationPresent(Scheduling.FixedRate.class)) {
                Scheduling.FixedRate annotation = am.getAnnotation(Scheduling.FixedRate.class);

                Task task = io.helidon.scheduling.FixedRate.builder()
                        .delayBy(Duration.parse(ConfigBuilderSupport.resolveExpression(config, annotation.delayBy())))
                        .delayType(annotation.delayType())
                        .interval(Duration.parse(ConfigBuilderSupport.resolveExpression(config, annotation.value())))
                        .config(methodConfig)
                        .executor(executorService)
                        .task(inv -> invokeWithOptionalParam(beanInstance, method, inv))
                        .build();

                LOGGER.log(Level.DEBUG, () -> String.format("Method %s#%s scheduled to be executed %s",
                                                            aClass.getSimpleName(), method.getName(), task.description()));
            } else if (am.isAnnotationPresent(Scheduling.Cron.class)) {
                Scheduling.Cron annotation = am.getAnnotation(Scheduling.Cron.class);

                Task task = Cron.builder()
                        .concurrentExecution(annotation.concurrent())
                        .expression(ConfigBuilderSupport.resolveExpression(config, annotation.value()))
                        .config(methodConfig)
                        .executor(executorService)
                        .task(inv -> invokeWithOptionalParam(beanInstance, method, inv))
                        .build();

                LOGGER.log(Level.DEBUG, () -> String.format("Method %s#%s scheduled to be executed %s",
                                                            aClass.getSimpleName(), method.getName(), task.description()));
            }
        }
    }

    void terminate(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
        this.terminate();
    }

    private void configure(@Observes @Priority(LIBRARY_BEFORE) AfterDeploymentValidation event) {
        this.config = toHelidonConfig(getConfig());
        this.schedulingConfig = config.get("schedule");
    }

    private boolean hasScheduleAnnotation(AnnotatedMethod<?> am) {
        return am.isAnnotationPresent(Scheduling.Cron.class)
                || am.isAnnotationPresent(Scheduling.FixedRate.class);
    }

    private void terminate() {
        this.executors.forEach(ExecutorService::shutdownNow);
    }
}
