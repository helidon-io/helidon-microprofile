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
import jakarta.enterprise.inject.spi.Extension;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.helidon.common.context.Contexts.runInContext;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceRegistryExtensionTest {
    @Test
    void suppliedRegistryTakesPrecedenceAndSurvivesClose() {
        ServiceRegistryManager suppliedManager = ServiceRegistryManager.create();
        ServiceRegistryManager contextualManager = ServiceRegistryManager.create();
        try {
            ServiceRegistry supplied = suppliedManager.registry();
            ServiceRegistry contextual = contextualManager.registry();
            Config config = Config.empty();
            runInContext(context(supplied), () -> Services.set(Config.class, config));
            // (RegistryService is a nested class below.)
            RegistryService service = supplied.get(RegistryService.class);
            ServiceRegistryExtension extension = new ServiceRegistryExtension(supplied);

            runInContext(context(contextual), () -> {
                try (SeContainer container = initializer().addExtensions(extension).initialize()) {
                    assertThat(container.getBeanManager().getExtension(ServiceRegistryExtension.class), sameInstance(extension));
                    // (RegistryUser is a nested class below.)
                    assertThat(container.select(RegistryUser.class).get().config(), sameInstance(config));
                    assertThat(GlobalServiceRegistry.registry(), sameInstance(contextual));
                }
                assertThat(GlobalServiceRegistry.registry(), sameInstance(contextual));
            });

            assertThat(service.destructions, is(0));
            assertThat(service.cdiDestructions, is(1));
            assertThat(supplied.get(Config.class), sameInstance(config));
            suppliedManager.shutdown();
            assertThat(service.destructions, is(1));
        } finally {
            suppliedManager.shutdown();
            contextualManager.shutdown();
        }
    }

    @Test
    void contextualRegistrySurvivesClose() {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        MpServiceRegistry unrelatedOwner = new MpServiceRegistry();
        try {
            unrelatedOwner.start();
            ServiceRegistry registry = manager.registry();
            RegistryService service = registry.get(RegistryService.class);
            Config config = Config.empty();
            runInContext(context(registry), () -> {
                Services.set(Config.class, config);
                try (SeContainer container = initializer().initialize()) {
                    // (RegistryUser is a nested class below.)
                    assertThat(container.select(RegistryUser.class).get().config(), sameInstance(config));
                }
                assertThat(GlobalServiceRegistry.registry(), sameInstance(registry));
            });
            assertThat(registry.get(Config.class), sameInstance(config));
            assertThat(service.destructions, is(0));
            assertThat(service.cdiDestructions, is(1));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(unrelatedOwner.registry()));
        } finally {
            manager.shutdown();
            unrelatedOwner.shutdown();
        }
    }

    @Test
    void suppliedRegistrySurvivesFailedInitialization() {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        MpServiceRegistry unrelatedOwner = new MpServiceRegistry();
        try {
            unrelatedOwner.start();
            ServiceRegistry unrelated = unrelatedOwner.registry();
            ServiceRegistry supplied = manager.registry();
            RegistryService service = supplied.get(RegistryService.class);
            ServiceRegistryExtension extension = new ServiceRegistryExtension(supplied);
            // (ValidationFailure is a nested class below.)
            ValidationFailure failure = new ValidationFailure();
            assertThrows(RuntimeException.class,
                         () -> initializer().addExtensions(extension, failure).initialize());
            assertThat(failure.observed, is(true));
            assertThat(service.destructions, is(0));
            assertThat(supplied.get(RegistryService.class), sameInstance(service));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(unrelated));
        } finally {
            manager.shutdown();
            unrelatedOwner.shutdown();
        }
    }

    @Test
    void ownedRegistryOutlivesCdiBeanDestruction() {
        RegistryService service;
        try (SeContainer container = initializer().initialize()) {
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
    void ownedRegistryIsClosedOnEarlyFailure() {
        AtomicReference<RegistryService> service = new AtomicReference<>();
        Extension failure = new Extension() {
            void fail(@Observes @Priority(PLATFORM_AFTER + 1) BeforeBeanDiscovery event) {
                service.set(GlobalServiceRegistry.registry().get(RegistryService.class));
                throw new IllegalStateException("Deliberate failure during bean discovery");
            }
        };
        assertThrows(RuntimeException.class, () -> initializer().addExtensions(failure).initialize());
        assertThat(service.get().destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void ownedRegistryIsClosedOnValidationFailure() {
        AtomicReference<RegistryService> service = new AtomicReference<>();
        Extension capture = new Extension() {
            void capture(@Observes BeforeBeanDiscovery event) {
                service.set(GlobalServiceRegistry.registry().get(RegistryService.class));
            }
        };
        ValidationFailure failure = new ValidationFailure();
        assertThrows(RuntimeException.class,
                     () -> initializer().addExtensions(capture, failure).initialize());
        assertThat(failure.observed, is(true));
        assertThat(service.get().destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void borrowedAndRepeatedCleanupLeaveOwnersAlone() {
        MpServiceRegistry first = new MpServiceRegistry();
        MpServiceRegistry borrower = new MpServiceRegistry();
        MpServiceRegistry second = new MpServiceRegistry();
        MpServiceRegistry secondBorrower = new MpServiceRegistry();
        try {
            first.start();
            RegistryService firstService = first.registry().get(RegistryService.class);
            borrower.start();
            assertThat(borrower.registry(), sameInstance(first.registry()));
            borrower.shutdown();
            assertThat(firstService.destructions, is(0));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(first.registry()));

            first.shutdown();
            assertThat(firstService.destructions, is(1));
            second.start();
            RegistryService secondService = second.registry().get(RegistryService.class);
            first.shutdown();
            borrower.shutdown();
            assertThat(secondService.destructions, is(0));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(second.registry()));
            secondBorrower.start();
            assertThat(secondBorrower.registry(), sameInstance(second.registry()));
        } finally {
            first.shutdown();
            borrower.shutdown();
            secondBorrower.shutdown();
            second.shutdown();
        }
    }

    private static SeContainerInitializer initializer() {
        return SeContainerInitializer.newInstance()
            .disableDiscovery()
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
