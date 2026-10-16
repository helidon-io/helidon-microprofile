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

package io.helidon.microprofile.security;

import java.util.Map;

import io.helidon.common.context.Context;
import io.helidon.microprofile.cdi.RuntimeStart;
import io.helidon.microprofile.cdi.ServiceRegistryExtension;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.JaxRsCdiExtension;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.security.AuthenticationResponse;
import io.helidon.security.Principal;
import io.helidon.security.Security;
import io.helidon.service.registry.ServiceRegistryManager;

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

import static io.helidon.common.context.Contexts.globalContext;
import static io.helidon.common.context.Contexts.runInContext;
import static io.helidon.security.AuthenticationResponse.success;
import static io.helidon.security.SecurityResponse.SecurityStatus.SUCCESS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies security configuration and builder customization ordering during CDI startup, with and without
 * CdiExtension. Configuration registered during AfterBeanDiscovery must be applied before application
 * customization observers run, and their changes must survive until Security is built. Authentication checks
 * verify that providers added by those observers are used.
 */
class SecurityConfigurationLifecycleTest {

    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private ServiceRegistryManager registryManager;
    private Context context;
    private SeContainer container;
    private Security security;

    @BeforeEach
    void saveConfiguration() {
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);

        // Other tests can initialize the application-global registry before these CDI containers start.
        this.registryManager = ServiceRegistryManager.create();
        this.context = Context.create();
        this.context.register("helidon-registry-static-context", this.context);
        this.context.register("helidon-registry", this.registryManager.registry());
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.container != null) {
                runInContext(this.context, this.container::close);
            }
        } finally {
            try {
                if (this.registryManager != null) {
                    this.registryManager.shutdown();
                }
            } finally {
                // SecurityCdiExtension publishes this instance in the global context.
                if (this.security != null) {
                    globalContext().unregister(this.security);
                }
                if (this.config != null) {
                    this.resolver.releaseConfig(this.config);
                }
                if (this.originalConfig != null) {
                    this.resolver.registerConfig(this.originalConfig, this.classLoader);
                }
            }
        }
    }

    private SeContainerInitializer createInitializer() {
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("server.port", "0", "security.enabled", "false")))
                .build();
        Extension configure = new Extension() {
            void configure(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }
        };
        return SeContainerInitializer.newInstance()
                .addExtensions(configure)
                .addBeanClasses(TestBean.class);
    }

    private void startContainer(SeContainerInitializer initializer) {
        this.container = runInContext(this.context, initializer::initialize);
        this.security = this.container.getBeanManager().getExtension(SecurityCdiExtension.class).security().orElseThrow();
    }

    private SeContainerInitializer createInitializerWithoutCdiExtension() {
        return createInitializer()
                .disableDiscovery()
                .addExtensions(new ServiceRegistryExtension(), new ServerCdiExtension(),
                               new JaxRsCdiExtension(), new SecurityCdiExtension());
    }

    @Dependent
    static class TestBean {
    }

    @Nested
    class ConfiguredSecurity {
        @BeforeEach
        void start() {
            startContainer(createInitializer());
        }

        @Test
        void configurationFromBeanDiscoveryDisablesSecurity() {
            assertThat(security.enabled(), is(false));
        }
    }

    @Nested
    class RuntimeStartCustomization {
        @BeforeEach
        void start() {
            Extension customize = new Extension() {
                void customize(@Observes @RuntimeStart Object event, BeanManager beanManager) {
                    beanManager.getExtension(SecurityCdiExtension.class).securityBuilder()
                            .enabled(true)
                            .addAuthenticationProvider(_ -> success(Principal.create("lifecycle-user")));
                }
            };
            startContainer(createInitializer().addExtensions(customize));
        }

        @Test
        void defaultPriorityRuntimeStartCustomizationOverridesConfiguration() {
            assertThat(security.enabled(), is(true));
        }

        @Test
        void providerAddedDuringRuntimeStartAuthenticates() {
            AuthenticationResponse response = runInContext(context,
                                                          () -> security.createContext("configuration-lifecycle").authenticate());
            assertThat(response.status(), is(SUCCESS));
            assertThat(response.user().orElseThrow().principal().getName(), is("lifecycle-user"));
        }
    }

    @Nested
    class WithoutCdiExtension {
        @BeforeEach
        void start() {
            startContainer(createInitializerWithoutCdiExtension());
        }

        @Test
        void configurationFromBeanDiscoveryDisablesSecurity() {
            assertThat(security.enabled(), is(false));
        }
    }

    @Nested
    class ValidationCustomization {
        @BeforeEach
        void start() {
            Extension customize = new Extension() {
                void customize(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
                    beanManager.getExtension(SecurityCdiExtension.class).securityBuilder()
                            .enabled(true)
                            .addAuthenticationProvider(_ -> success(Principal.create("validation-user")));
                }
            };
            startContainer(createInitializerWithoutCdiExtension().addExtensions(customize));
        }

        @Test
        void defaultPriorityValidationCustomizationOverridesConfiguration() {
            assertThat(security.enabled(), is(true));
        }

        @Test
        void providerAddedDuringValidationAuthenticates() {
            AuthenticationResponse response = runInContext(context,
                                                          () -> security.createContext("configuration-lifecycle").authenticate());
            assertThat(response.status(), is(SUCCESS));
            assertThat(response.user().orElseThrow().principal().getName(), is("validation-user"));
        }
    }
}
