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

package io.helidon.microprofile.server;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import io.helidon.common.Builder;
import io.helidon.microprofile.cdi.BuildTimeEnd;
import io.helidon.microprofile.cdi.RuntimeStart;
import io.helidon.microprofile.cdi.ServiceRegistryExtension;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.webserver.Routing;
import io.helidon.webserver.WebServer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
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

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

class ServerConfigurationLifecycleTest {

    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;

    @BeforeEach
    void saveConfiguration() {
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);
    }

    @AfterEach
    void restoreConfiguration() {
        if (this.config != null) {
            this.resolver.releaseConfig(this.config);
        }
        if (this.originalConfig != null) {
            this.resolver.registerConfig(this.originalConfig, this.classLoader);
        }
    }

    private void configure(Map<String, String> properties) {
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(properties))
                .build();
    }

    private interface TestRoutingBuilder extends Builder<TestRoutingBuilder, TestRouting> {
    }

    @Dependent
    static class TestBean {
    }

    private static class TestRouting implements Routing {
        private int port;
        private int backlog;

        @Override
        public void afterStart(WebServer server) {
            this.port = server.port("custom");
            this.backlog = server.prototype().backlog();
        }
    }

    @Nested
    class ProgrammaticOverrides {
        private ServerSocket occupiedPort;
        private Server server;

        @BeforeEach
        void start() throws Exception {
            this.occupiedPort = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            configure(Map.of("server.port", Integer.toString(this.occupiedPort.getLocalPort())));
            // The configured port is unavailable; the explicit builder setting must win.
            this.server = Server.builder()
                    .config(config)
                    .host("127.0.0.1")
                    .port(0)
                    .build()
                    .start();
        }

        @AfterEach
        void stop() throws Exception {
            try {
                if (this.server != null) {
                    this.server.stop();
                }
            } finally {
                if (this.occupiedPort != null) {
                    this.occupiedPort.close();
                }
            }
        }

        @Test
        void builderPortOverridesConfiguredPort() {
            assertThat(this.server.port(), greaterThan(0));
            assertThat(this.server.port(), is(not(this.occupiedPort.getLocalPort())));
        }
    }

    @Nested
    class RuntimeStartCustomization {
        private final TestRouting routing = new TestRouting();
        private SeContainer container;

        @BeforeEach
        void start() {
            configure(Map.of("server.port", "0", "server.sockets.custom.port", "0", "server.backlog", "10"));
            Extension customize = new Extension() {
                void configure(@Observes AfterBeanDiscovery event) {
                    resolver.registerConfig(config, classLoader);
                }

                @SuppressWarnings("deprecation")
                void customize(@Observes @Priority(PLATFORM_AFTER) @RuntimeStart Object event, BeanManager beanManager) {
                    TestRoutingBuilder builder = () -> routing;
                    ServerCdiExtension server = beanManager.getExtension(ServerCdiExtension.class);
                    server.addRouting(builder, "custom", true, "runtime-start-customization");
                    server.serverBuilder().backlog(20);
                }
            };
            this.container = SeContainerInitializer.newInstance()
                    .addExtensions(customize)
                    .addBeanClasses(TestBean.class)
                    .initialize();
        }

        @AfterEach
        void stop() {
            if (this.container != null) {
                this.container.close();
            }
        }

        @Test
        void laterRuntimeStartObserverCanUseConfiguredSocket() {
            assertThat(this.routing.port, greaterThan(0));
        }

        @Test
        void runtimeStartCustomizationWinsOverConfiguration() {
            assertThat(this.routing.backlog, is(20));
        }
    }

    @Nested
    class BuildTimeEndConfiguration {
        private final TestRouting routing = new TestRouting();
        private SeContainer container;

        @BeforeEach
        void start() {
            configure(Map.of("server.port", "0", "server.sockets.custom.port", "0", "server.backlog", "10"));
            Extension customize = new Extension() {
                @SuppressWarnings("deprecation")
                void configure(@Observes @BuildTimeEnd Object event) {
                    resolver.registerConfig(config, classLoader);
                }

                @SuppressWarnings("deprecation")
                void customize(@Observes @Priority(PLATFORM_BEFORE) @RuntimeStart Object event, BeanManager beanManager) {
                    TestRoutingBuilder builder = () -> routing;
                    ServerCdiExtension server = beanManager.getExtension(ServerCdiExtension.class);
                    server.addRouting(builder, "custom", true, "build-time-end-configuration");
                    server.serverBuilder().backlog(20);
                }
            };
            this.container = SeContainerInitializer.newInstance()
                    .addExtensions(customize)
                    .addBeanClasses(TestBean.class)
                    .initialize();
        }

        @AfterEach
        void stop() {
            if (this.container != null) {
                this.container.close();
            }
        }

        @Test
        void earlyRuntimeStartObserverCanUseSocketConfiguredDuringBuildTimeEnd() {
            assertThat(this.routing.port, greaterThan(0));
        }

        @Test
        void earlyRuntimeStartCustomizationWinsOverBuildTimeEndConfiguration() {
            assertThat(this.routing.backlog, is(20));
        }
    }

    @Nested
    class WithoutCdiExtension {
        private final TestRouting routing = new TestRouting();
        private SeContainer container;

        @BeforeEach
        void start() {
            configure(Map.of("server.port", "0", "server.sockets.custom.port", "0", "server.backlog", "10"));
            Extension customize = new Extension() {
                void configure(@Observes AfterBeanDiscovery event) {
                    resolver.registerConfig(config, classLoader);
                }

                void customize(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
                    TestRoutingBuilder builder = () -> routing;
                    ServerCdiExtension server = beanManager.getExtension(ServerCdiExtension.class);
                    server.addRouting(builder, "custom", true, "validation-customization");
                    server.serverBuilder().backlog(20);
                }
            };
            this.container = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addExtensions(customize,
                                   new JaxRsCdiExtension(),
                                   // (No CdiExtension on purpose.)
                                   new ServerCdiExtension(),
                                   new ServiceRegistryExtension())
                    .addBeanClasses(TestBean.class)
                    .initialize();
        }

        @AfterEach
        void stop() {
            if (this.container != null) {
                this.container.close();
            }
        }

        @Test
        void defaultPriorityValidationObserverCanUseConfiguredSocket() {
            assertThat(this.routing.port, greaterThan(0));
        }

        @Test
        void defaultPriorityValidationCustomizationWinsOverConfiguration() {
            assertThat(this.routing.backlog, is(20));
        }
    }

}
