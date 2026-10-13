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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

class LifecycleExtensionTest {
    @Test
    void testStandardStartupAndShutdown() {
        List<String> events = new ArrayList<>();
        Extension observer = new Extension() {
            void start(@Observes Startup event) {
                events.add("startup");
            }

            void stop(@Observes Shutdown event) {
                events.add("shutdown");
            }
        };
        try (SeContainer _ = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(observer)
                .addBeanClasses(TestBean2.class)
                .initialize()) {
            assertThat(events, contains("startup"));
        }
        assertThat(events, contains("startup", "shutdown"));
    }

    @SuppressWarnings("unchecked") // heap pollution
    @Test
    void testExplicitExtensionUsesConfigurationFromBeanDiscovery() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Config originalConfig = resolver.getConfig(classLoader);
        Config config = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("lifecycle.message", "discovery")))
                .build();
        TestExtension testExtension = new TestExtension();
        Extension configure = new Extension() {
            void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
                assertThat(testExtension.events(), empty());
                resolver.registerConfig(config, classLoader);
            }

            void afterDeploymentValidation(@Observes AfterDeploymentValidation event) {
                assertThat(((Config) testExtension.runtimeConfig()).unwrap(Config.class), sameInstance(config));
                assertThat(testExtension.events(), contains(TestExtension.BUILD_TIME_START,
                                                       TestExtension.BUILD_TIME_END,
                                                       TestExtension.RUNTIME_INIT));
            }
        };

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(LifecycleExtension.class, ServiceRegistryExtension.class)
                .addExtensions(testExtension, configure)
                .initialize()) {
            assertThat(container.getBeanManager().getExtension(TestExtension.class), sameInstance(testExtension));
            assertThat(testExtension.events(), contains(TestExtension.BUILD_TIME_START,
                                                   TestExtension.BUILD_TIME_END,
                                                   TestExtension.RUNTIME_INIT,
                                                   TestExtension.APPLICATION_INIT));
        } finally {
            resolver.registerConfig(originalConfig, classLoader);
        }
    }

    @SuppressWarnings("unchecked") // heap pollution
    @Test
    void testDisabledDiscoveryDoesNotDeliverLegacyEvents() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(TestExtension.class, ServiceRegistryExtension.class)
                .initialize()) {
            TestExtension testExtension = container.getBeanManager().getExtension(TestExtension.class);
            assertThat(testExtension.runtimeConfig(), nullValue());
            assertThat(testExtension.events(), contains(TestExtension.APPLICATION_INIT));
        }
    }
}
