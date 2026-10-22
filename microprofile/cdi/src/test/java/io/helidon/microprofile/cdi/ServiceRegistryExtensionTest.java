/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.context.Context;
import io.helidon.config.Config;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.helidon.common.context.Contexts.runInContext;
import static io.helidon.microprofile.cdi.SeContainerInitializers.initialize;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceRegistryExtensionTest {
    @Test
    void contextualRegistrySurvivesClose() {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        ServiceRegistryExtension unrelatedOwner = new ServiceRegistryExtension();
        try (SeContainer _ = initializer(unrelatedOwner).initialize()) {
            ServiceRegistry unrelated = GlobalServiceRegistry.registry();
            ServiceRegistry registry = manager.registry();
            RegistryService service = registry.get(RegistryService.class);
            Config config = Config.empty();
            // Construction occurs outside the context that supplies the registry during initialization.
            SeContainerInitializer initializer = SeContainerInitializer.newInstance().addBeanClasses(RegistryUser.class);
            runInContext(context(registry), () -> {
                Services.set(Config.class, config);
                try (SeContainer container = initializer.initialize()) {
                    // (RegistryUser is a nested class below.)
                    assertThat(container.select(RegistryUser.class).get().config(), sameInstance(config));
                }
                assertThat(GlobalServiceRegistry.registry(), sameInstance(registry));
            });
            assertThat(registry.get(Config.class), sameInstance(config));
            assertThat(service.destructions, is(0));
            assertThat(service.cdiDestructions, is(1));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(unrelated));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void contextualRegistrySurvivesFailedInitialization() {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        ServiceRegistryExtension unrelatedOwner = new ServiceRegistryExtension();
        try (SeContainer _ = initializer(unrelatedOwner).initialize()) {
            ServiceRegistry unrelated = GlobalServiceRegistry.registry();
            ServiceRegistry registry = manager.registry();
            RegistryService service = registry.get(RegistryService.class);
            ServiceRegistryExtension extension = new ServiceRegistryExtension();
            // (ValidationFailure is a nested class below.)
            ValidationFailure failure = new ValidationFailure();
            runInContext(context(registry), () -> {
                assertThrows(RuntimeException.class,
                             () -> initialize(initializer(extension).addExtensions(failure)));
                assertThat(GlobalServiceRegistry.registry(), sameInstance(registry));
            });
            assertThat(failure.observed, is(true));
            assertThat(service.destructions, is(0));
            assertThat(registry.get(RegistryService.class), sameInstance(service));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(unrelated));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void failedBorrowerLeavesRunningOwnerAlone() {
        ServiceRegistryExtension owner = new ServiceRegistryExtension();
        ServiceRegistryExtension borrower = new ServiceRegistryExtension();
        RegistryService service;
        try (SeContainer container = initialize(initializer(owner))) {
            ServiceRegistry registry = GlobalServiceRegistry.registry();
            service = registry.get(RegistryService.class);
            ValidationFailure failure = new ValidationFailure();

            assertThrows(RuntimeException.class,
                         () -> initialize(initializer(borrower).addExtensions(failure)));

            assertThat(failure.observed, is(true));
            assertThat(container.isRunning(), is(true));
            assertThat(service.destructions, is(0));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(registry));
        }
        assertThat(service.destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void ownedRegistryOutlivesCdiBeanDestruction() {
        RegistryService service;
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addBeanClasses(RegistryUser.class)
                .initialize()) {
            service = GlobalServiceRegistry.registry().get(RegistryService.class);
            // (RegistryUser is a nested class below.)
            container.select(RegistryUser.class).get().config();
            assertThat(service.destructions, is(0));
        }
        assertThat(service.cdiDestructions, is(1));
        assertThat(service.destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void contextualRegistrySurvivesRawInitializationFailures() {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        try {
            ServiceRegistry registry = manager.registry();
            RegistryService service = registry.get(RegistryService.class);
            AtomicInteger shutdowns = new AtomicInteger();
            Extension earlyFailure = new Extension() {
                void fail(@Observes @Priority(PLATFORM_AFTER + 1) BeforeBeanDiscovery event) {
                    throw new IllegalStateException("Deliberate failure during bean discovery");
                }
            };
            Extension capture = new Extension() {
                void shutdown(@Observes BeforeShutdown event) {
                    shutdowns.incrementAndGet();
                }
            };
            ValidationFailure validationFailure = new ValidationFailure();
            runInContext(context(registry), () -> {
                assertThrows(RuntimeException.class,
                             () -> initializer(new ServiceRegistryExtension()).addExtensions(earlyFailure, capture).initialize());
                assertThrows(RuntimeException.class,
                             () -> initializer(new ServiceRegistryExtension()).addExtensions(validationFailure, capture)
                                     .initialize());
                assertThat(GlobalServiceRegistry.registry(), sameInstance(registry));
            });
            assertThat(validationFailure.observed, is(true));
            assertThat(shutdowns.get(), is(0));
            assertThat(service.destructions, is(0));
            assertThat(registry.get(RegistryService.class), sameInstance(service));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void ownedRegistryIsCleanedOnEarlyFailure() {
        ServiceRegistryExtension extension = new ServiceRegistryExtension();
        AtomicReference<RegistryService> service = new AtomicReference<>();
        Extension failure = new Extension() {
            void fail(@Observes @Priority(PLATFORM_AFTER + 1) BeforeBeanDiscovery event) {
                service.set(GlobalServiceRegistry.registry().get(RegistryService.class));
                throw new IllegalStateException("Deliberate failure during bean discovery");
            }
        };
        assertThrows(RuntimeException.class, () -> initialize(initializer(extension).addExtensions(failure)));
        assertThat(service.get().destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void ownedRegistryIsCleanedWithoutShutdownNotificationOnValidationFailure() {
        ServiceRegistryExtension extension = new ServiceRegistryExtension();
        AtomicReference<RegistryService> service = new AtomicReference<>();
        AtomicInteger shutdowns = new AtomicInteger();
        Extension capture = new Extension() {
            void capture(@Observes BeforeBeanDiscovery event) {
                service.set(GlobalServiceRegistry.registry().get(RegistryService.class));
            }

            void shutdown(@Observes BeforeShutdown event) {
                shutdowns.incrementAndGet();
            }
        };
        ValidationFailure failure = new ValidationFailure();
        assertThrows(RuntimeException.class,
                     () -> initialize(initializer(extension).addExtensions(capture, failure)));
        assertThat(failure.observed, is(true));
        // Stock Weld does not promise BeforeShutdown after failed initialization.
        assertThat(shutdowns.get(), is(0));
        assertThat(service.get().destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void borrowedAndDelayedCleanupLeaveOwnersAlone() {
        ServiceRegistryExtension first = new ServiceRegistryExtension();
        ServiceRegistryExtension borrower = new ServiceRegistryExtension();
        ServiceRegistryExtension second = new ServiceRegistryExtension();
        ServiceRegistryExtension secondBorrower = new ServiceRegistryExtension();
        SeContainer firstContainer = initialize(initializer(first));
        try {
            RegistryService firstService;
            ServiceRegistry firstRegistry = GlobalServiceRegistry.registry();
            firstService = firstRegistry.get(RegistryService.class);
            SeContainer borrowerContainer = initialize(initializer(borrower));
            try {
                assertThat(GlobalServiceRegistry.registry(), sameInstance(firstRegistry));
                assertThat(firstService.destructions, is(0));
                firstContainer.close();
                assertThat(firstService.destructions, is(1));
                RegistryService secondService;
                try (SeContainer _ = initialize(initializer(second))) {
                    ServiceRegistry secondRegistry = GlobalServiceRegistry.registry();
                    secondService = secondRegistry.get(RegistryService.class);
                    borrowerContainer.close();
                    assertThat(secondService.destructions, is(0));
                    assertThat(GlobalServiceRegistry.registry(), sameInstance(secondRegistry));
                    try (SeContainer _ = initialize(initializer(secondBorrower))) {
                        assertThat(GlobalServiceRegistry.registry(), sameInstance(secondRegistry));
                    }
                    assertThat(secondService.destructions, is(0));
                }
                assertThat(secondService.destructions, is(1));
            } finally {
                if (borrowerContainer.isRunning()) {
                    borrowerContainer.close();
                }
            }
        } finally {
            if (firstContainer.isRunning()) {
                firstContainer.close();
            }
        }
    }

    private static SeContainerInitializer initializer(ServiceRegistryExtension extension) {
        return SeContainerInitializer.newInstance()
            .disableDiscovery()
            .addExtensions(extension)
            .addBeanClasses(RegistryUser.class);
    }

    private static Context context(ServiceRegistry registry) {
        Context context = Context.create();
        context.register("helidon-registry-static-context", context);
        context.register("helidon-registry", registry);
        return context;
    }

    static class ValidationFailure implements Extension {
        private boolean observed;

        void fail(@Observes AfterDeploymentValidation event) {
            observed = true;
            event.addDeploymentProblem(new IllegalStateException("Deliberate failure during validation"));
        }
    }

    @ApplicationScoped
    static class RegistryUser {
        @Inject
        private ServiceRegistry registry;

        Config config() {
            return registry.get(Config.class);
        }

        @PreDestroy
        void destroy() {
            registry.get(Config.class);
            RegistryService service = registry.get(RegistryService.class);
            assertThat(service.destructions, is(0));
            service.cdiDestructions++;
        }
    }

    @Service.Singleton
    static class RegistryService {
        private int cdiDestructions;
        private int destructions;

        @Service.PreDestroy
        void destroy() {
            destructions++;
        }
    }
}
