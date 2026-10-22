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

import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainRegistryFailureTest {
    private static final ScopedValue<MainRegistryFailureTest> CURRENT_TEST = ScopedValue.newInstance();

    private final IllegalStateException failure = new IllegalStateException("Deliberate initialization failure");
    private FailureStage failureStage;
    private ServiceRegistry registry;
    private RegistryService service;
    private boolean failureObserved;
    private int shutdownNotifications;

    @BeforeEach
    void setUp() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @AfterEach
    void tearDown() {
        Main.shutdown();
    }

    @Test
    void discoveryFailureClosesOwnedRegistry() {
        this.failureStage = FailureStage.DISCOVERY;

        assertThrows(RuntimeException.class, this::startFailingMain);

        assertThat(this.failureObserved, is(true));
        assertThat(this.shutdownNotifications, is(0));
        assertThat(this.service.destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void validationFailureClosesOwnedRegistry() {
        this.failureStage = FailureStage.VALIDATION;

        assertThrows(RuntimeException.class, this::startFailingMain);

        assertThat(this.failureObserved, is(true));
        assertThat(this.shutdownNotifications, is(0));
        assertThat(this.service.destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void startupFailureClosesOwnedRegistryAndAllowsReplacement() {
        this.failureStage = FailureStage.STARTUP;

        assertThat(assertThrows(IllegalStateException.class, this::startFailingMain), sameInstance(this.failure));

        assertThat(this.failureObserved, is(true));
        assertThat(this.shutdownNotifications, is(0));
        assertThat(this.service.destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));

        Main.main(new String[0]);
        SeContainer replacement = (SeContainer) CDI.current();
        ServiceRegistry replacementRegistry = GlobalServiceRegistry.registry();
        RegistryService replacementService = replacementRegistry.get(RegistryService.class);
        assertThat(replacement.isRunning(), is(true));
        assertThat(replacementRegistry, not(sameInstance(this.registry)));
        assertThat(replacementService.destructions, is(0));

        Main.shutdown();
        assertThat(replacement.isRunning(), is(false));
        assertThat(replacementService.destructions, is(1));
        assertThat(this.service.destructions, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    private void startFailingMain() {
        ScopedValue.where(CURRENT_TEST, this).run(() -> Main.main(new String[0]));
    }

    private enum FailureStage {
        DISCOVERY,
        VALIDATION,
        STARTUP
    }

    @Service.Singleton
    static class RegistryService {
        private int destructions;

        @Service.PreDestroy
        void destroy() {
            this.destructions++;
        }
    }

    // Discovered for Main's ordinary bootstrap; inactive unless this test binds CURRENT_TEST.
    public static class FailureExtension implements Extension {
        void beforeBeanDiscovery(@Observes @Priority(PLATFORM_AFTER + 1) BeforeBeanDiscovery event) {
            if (CURRENT_TEST.isBound()) {
                MainRegistryFailureTest test = CURRENT_TEST.get();
                test.registry = GlobalServiceRegistry.registry();
                test.service = test.registry.get(RegistryService.class);
                if (test.failureStage == FailureStage.DISCOVERY) {
                    test.failureObserved = true;
                    throw test.failure;
                }
            }
        }

        void afterDeploymentValidation(@Observes AfterDeploymentValidation event) {
            if (CURRENT_TEST.isBound()) {
                MainRegistryFailureTest test = CURRENT_TEST.get();
                if (test.failureStage == FailureStage.VALIDATION) {
                    test.failureObserved = true;
                    event.addDeploymentProblem(test.failure);
                }
            }
        }

        void startup(@Observes Startup event) {
            if (CURRENT_TEST.isBound()) {
                MainRegistryFailureTest test = CURRENT_TEST.get();
                if (test.failureStage == FailureStage.STARTUP) {
                    test.failureObserved = true;
                    throw test.failure;
                }
            }
        }

        void beforeShutdown(@Observes BeforeShutdown event) {
            if (CURRENT_TEST.isBound()) {
                CURRENT_TEST.get().shutdownNotifications++;
            }
        }
    }
}
