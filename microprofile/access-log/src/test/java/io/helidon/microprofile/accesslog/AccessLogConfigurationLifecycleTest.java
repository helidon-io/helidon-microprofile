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

package io.helidon.microprofile.accesslog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.helidon.common.Builder;
import io.helidon.microprofile.cdi.RuntimeStart;
import io.helidon.microprofile.cdi.ServiceRegistryExtension;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.JaxRsCdiExtension;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.webserver.Routing;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.accesslog.AccessLogConfig;
import io.helidon.webserver.accesslog.AccessLogFeature;

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

import static io.helidon.webserver.accesslog.AccessLogFeature.DEFAULT_LOGGER_NAME;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

class AccessLogConfigurationLifecycleTest {

    private final TestRouting routing = new TestRouting();
    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private SeContainer container;

    @BeforeEach
    void saveConfiguration() {
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.container != null) {
                this.container.close();
            }
        } finally {
            if (this.config != null) {
                this.resolver.releaseConfig(this.config);
            }
            if (this.originalConfig != null) {
                this.resolver.registerConfig(this.originalConfig, this.classLoader);
            }
        }
    }

    private SeContainerInitializer createInitializer(Map<String, String> properties) {
        Map<String, String> allProperties = new HashMap<>(properties);
        allProperties.put("server.port", "0");
        allProperties.put("server.host", "127.0.0.1");
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(allProperties))
                .build();
        Extension configure = new Extension() {
            void configure(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }

            void registerRouting(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
                TestRoutingBuilder builder = () -> routing;
                beanManager.getExtension(ServerCdiExtension.class).addRouting(builder);
            }
        };
        return SeContainerInitializer.newInstance()
                .addExtensions(configure)
                .addBeanClasses(TestBean.class);
    }

    private void startContainer(SeContainerInitializer initializer) {
        this.container = initializer.initialize();
    }

    private SeContainerInitializer createInitializerWithoutLifecycleExtension(Map<String, String> properties) {
        return createInitializer(properties)
                .disableDiscovery()
                .addExtensions(new ServiceRegistryExtension(), new ServerCdiExtension(),
                               new JaxRsCdiExtension(), new AccessLogCdiExtension());
    }

    private Extension runtimeStartCustomization() {
        return new Extension() {
            void customize(@Observes @RuntimeStart Object event, BeanManager beanManager) {
                beanManager.getExtension(ServerCdiExtension.class)
                        .addFeature(AccessLogFeature.builder()
                                            .enabled(false)
                                            .loggerName("runtime-start-access-log")
                                            .build());
            }
        };
    }

    private void assertAccessLog(boolean enabled, String loggerName) {
        assertThat(this.routing.accessLogs, hasSize(1));
        AccessLogConfig accessLog = this.routing.accessLogs.getFirst().prototype();
        assertThat(accessLog.enabled(), is(enabled));
        assertThat(accessLog.loggerName(), is(loggerName));
    }

    private interface TestRoutingBuilder extends Builder<TestRoutingBuilder, TestRouting> {
    }

    @Dependent
    static class TestBean {
    }

    private static class TestRouting implements Routing {
        private List<AccessLogFeature> accessLogs = List.of();

        @Override
        public void afterStart(WebServer server) {
            this.accessLogs = server.prototype().features().stream()
                    .filter(AccessLogFeature.class::isInstance)
                    .map(AccessLogFeature.class::cast)
                    .toList();
        }
    }

    @Nested
    class Defaults {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of()));
        }

        @Test
        void accessLoggingIsEnabledByDefault() {
            assertAccessLog(true, DEFAULT_LOGGER_NAME);
        }
    }

    @Nested
    class OldConfiguration {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of("server.access-log.enabled", "false",
                                                   "server.access-log.logger-name", "old-access-log")));
        }

        @Test
        void oldConfigurationKeyStillConfiguresAccessLogging() {
            assertAccessLog(false, "old-access-log");
        }
    }

    @Nested
    class NewConfiguration {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of("server.features.access-log.enabled", "true",
                                                   "server.features.access-log.logger-name", "new-access-log")));
        }

        @Test
        void newConfigurationKeyConfiguresAccessLogging() {
            assertAccessLog(true, "new-access-log");
        }
    }

    @Nested
    class DisabledNewConfiguration {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of("server.features.access-log.enabled", "false")));
        }

        @Test
        void newConfigurationCanDisableFeatureRegistration() {
            assertThat(routing.accessLogs, is(empty()));
        }
    }

    @Nested
    class BothConfigurations {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of("server.access-log.enabled", "false",
                                                   "server.access-log.logger-name", "old-access-log",
                                                   "server.features.access-log.enabled", "true",
                                                   "server.features.access-log.logger-name", "new-access-log")));
        }

        @Test
        void oldConfigurationTakesPrecedenceWhenBothKeysArePresent() {
            assertAccessLog(false, "old-access-log");
        }
    }

    @Nested
    class RuntimeStartCustomization {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of()).addExtensions(runtimeStartCustomization()));
        }

        @Test
        void defaultFeatureTakesPrecedenceOverLaterRuntimeStartFeature() {
            assertAccessLog(true, DEFAULT_LOGGER_NAME);
        }
    }

    @Nested
    class NewConfigurationWithRuntimeStartCustomization {
        @BeforeEach
        void start() {
            startContainer(createInitializer(Map.of("server.features.access-log.enabled", "true",
                                                   "server.features.access-log.logger-name", "new-access-log"))
                                   .addExtensions(runtimeStartCustomization()));
        }

        @Test
        void runtimeStartFeatureTakesPrecedenceOverNewConfiguration() {
            assertAccessLog(false, "runtime-start-access-log");
        }
    }

    @Nested
    class WithoutLifecycleExtension {
        @BeforeEach
        void start() {
            startContainer(createInitializerWithoutLifecycleExtension(Map.of()));
        }

        @Test
        void accessLoggingIsEnabledByDefault() {
            assertAccessLog(true, DEFAULT_LOGGER_NAME);
        }
    }

    @Nested
    class OldConfigurationWithoutLifecycleExtension {
        @BeforeEach
        void start() {
            startContainer(createInitializerWithoutLifecycleExtension(Map.of("server.access-log.enabled", "false",
                                                                            "server.access-log.logger-name", "old-access-log")));
        }

        @Test
        void oldConfigurationKeyStillConfiguresAccessLogging() {
            assertAccessLog(false, "old-access-log");
        }
    }

    @Nested
    class NewConfigurationWithoutLifecycleExtension {
        @BeforeEach
        void start() {
            startContainer(createInitializerWithoutLifecycleExtension(Map.of("server.features.access-log.enabled", "false")));
        }

        @Test
        void newConfigurationCanDisableFeatureRegistration() {
            assertThat(routing.accessLogs, is(empty()));
        }
    }

    @Nested
    class ValidationCustomization {
        @BeforeEach
        void start() {
            Extension customize = new Extension() {
                void customize(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
                    beanManager.getExtension(ServerCdiExtension.class)
                            .addFeature(AccessLogFeature.builder()
                                                .enabled(false)
                                                .loggerName("validation-access-log")
                                                .build());
                }
            };
            startContainer(createInitializerWithoutLifecycleExtension(Map.of()).addExtensions(customize));
        }

        @Test
        void defaultFeatureTakesPrecedenceOverDefaultPriorityValidationFeature() {
            assertAccessLog(true, DEFAULT_LOGGER_NAME);
        }
    }
}
