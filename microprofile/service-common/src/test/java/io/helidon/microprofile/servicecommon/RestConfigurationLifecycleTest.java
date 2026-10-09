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
package io.helidon.microprofile.servicecommon;

import java.util.Map;

import io.helidon.microprofile.cdi.LifecycleExtension;
import io.helidon.microprofile.cdi.RuntimeStart;
import io.helidon.microprofile.config.core.MpConfigSources;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.helidon.microprofile.config.core.MpConfig.toHelidonConfig;
import static jakarta.interceptor.Interceptor.Priority.LIBRARY_BEFORE;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class RestConfigurationLifecycleTest {

    private final TestExtension extension = new TestExtension();
    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private Config eventConfig;
    private SeContainer container;
    private boolean runtimeStartObserved;

    @BeforeEach
    void setUp() {
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("test.importance", "3")))
                .build();
        this.eventConfig = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("test.importance", "7")))
                .build();
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.container != null) {
                this.container.close();
            }
        } finally {
            this.resolver.releaseConfig(this.eventConfig);
            this.resolver.releaseConfig(this.config);
            this.resolver.registerConfig(this.originalConfig, this.classLoader);
        }
    }

    private SeContainerInitializer createInitializer() {
        Extension configure = new Extension() {
            void configure(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }

            void runtimeStart(@Observes @RuntimeStart Object event) {
                runtimeStartObserved = true;
            }
        };
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(this.extension, configure)
                .addBeanClasses(TestBean.class);
    }

    @Dependent
    static class TestBean {
    }

    static class TestExtension extends HelidonRestCdiExtension {
        private int rootImportance;
        private int componentImportance;

        TestExtension() {
            super(System.getLogger(TestExtension.class.getName()), "test");
        }

        void configureService(@Observes @Priority(LIBRARY_BEFORE + 10)
                              @Initialized(ApplicationScoped.class) Object event) {
            this.rootImportance = this.rootConfig().get("test.importance").asInt().get();
            this.componentImportance = ConfiguredTestSupport.builder().config(this.componentConfig()).build().importance();
        }
    }

    @Nested
    class WithoutLifecycleExtension {
        @BeforeEach
        void start() {
            container = createInitializer().initialize();
        }

        @Test
        void beanDiscoveryConfigurationIsAvailableAtApplicationInitialization() {
            assertThat(runtimeStartObserved, is(false));
            assertThat(extension.rootImportance, is(3));
            assertThat(extension.componentImportance, is(3));
        }
    }

    @Nested
    class WithLifecycleExtension {
        @BeforeEach
        void start() {
            container = createInitializer().addExtensions(new LifecycleExtension()).initialize();
        }

        @Test
        void beanDiscoveryConfigurationIsAvailableThroughRuntimeStart() {
            assertThat(runtimeStartObserved, is(true));
            assertThat(extension.rootImportance, is(3));
            assertThat(extension.componentImportance, is(3));
        }
    }

    @Nested
    class SuppliedEventConfiguration {
        @BeforeEach
        void start() {
            Extension supply = new Extension() {
                void supply(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
                    beanManager.getEvent().select(RuntimeStart.Literal.INSTANCE).fire(toHelidonConfig(eventConfig));
                }
            };
            container = createInitializer().addExtensions(supply).initialize();
        }

        @Test
        void inheritedObserverUsesEventConfigurationInsteadOfProviderConfiguration() {
            assertThat(runtimeStartObserved, is(true));
            assertThat(resolver.getConfig(classLoader).getValue("test.importance", Integer.class), is(3));
            assertThat(extension.rootImportance, is(7));
            assertThat(extension.componentImportance, is(7));
        }
    }
}
