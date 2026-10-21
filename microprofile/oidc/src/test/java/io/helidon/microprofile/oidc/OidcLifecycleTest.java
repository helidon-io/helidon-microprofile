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

package io.helidon.microprofile.oidc;

import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.ServerCdiExtension;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class OidcLifecycleTest {

    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private SeContainer container;
    private Client client;
    private String uri;

    @BeforeEach
    void saveConfiguration() {
        resolver = ConfigProviderResolver.instance();
        classLoader = Thread.currentThread().getContextClassLoader();
        originalConfig = resolver.getConfig(classLoader).unwrap(Config.class);
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            try {
                if (container != null) {
                    container.close();
                }
            } finally {
                if (config != null) {
                    resolver.releaseConfig(config);
                }
                if (originalConfig != null) {
                    resolver.registerConfig(originalConfig, classLoader);
                }
            }
        }
    }

    private SeContainerInitializer createInitializer(Map<String, String> properties) {
        config = resolver.getBuilder()
                .withSources(MpConfigSources.create(properties))
                .build();
        Extension configure = new Extension() {
            void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }
        };
        return SeContainerInitializer.newInstance()
                .addExtensions(configure)
                .addBeanClasses(TestBean.class);
    }

    @Dependent
    static class TestBean {
    }

    @Nested
    class EnabledSecurity {
        @BeforeEach
        void start() {
            String provider = "security.providers.0.oidc.";
            // Configure endpoints explicitly so this lifecycle test needs no identity server.
            container = createInitializer(Map.of(
                    "server.port", "0",
                    provider + "client-id", "lifecycle-client",
                    provider + "client-secret", "lifecycle-secret",
                    provider + "identity-uri", "https://identity.example.test",
                    provider + "oidc-metadata-well-known", "false",
                    provider + "token-endpoint-uri", "https://identity.example.test/token",
                    provider + "authorization-endpoint-uri", "https://identity.example.test/authorize",
                    provider + "validate-jwt-with-jwk", "false",
                    provider + "redirect-uri", "/configured/callback"))
                    .initialize();
            int port = container.getBeanManager().getExtension(ServerCdiExtension.class).port();
            uri = "http://localhost:" + port;
            client = ClientBuilder.newBuilder()
                    .connectTimeout(10, SECONDS)
                    .readTimeout(10, SECONDS)
                    .build();
        }

        @Test
        void registersConfiguredCallbackWithSecurityEnabledByDefault() {
            try (Response response = client.target(uri + "/configured/callback")
                    .queryParam("error", "access_denied")
                    .queryParam("error_description", "lifecycle-test")
                    .request().get()) {
                assertThat(response.getStatusInfo(), is(Response.Status.BAD_REQUEST));
                assertThat(response.readEntity(String.class), containsString("lifecycle-test"));
            }
            try (Response response = client.target(uri + "/oidc/redirect").request().get()) {
                assertThat(response.getStatusInfo(), is(Response.Status.NOT_FOUND));
            }
        }
    }

    @Nested
    class DisabledSecurity {
        @BeforeEach
        void start() {
            // Disabling security must avoid accessing the server extension, even without
            // io.helidon.microprofile.cdi.CdiExtension.
            container = createInitializer(Map.of("security.enabled", "false"))
                    .disableDiscovery()
                    .addExtensions(new OidcCdiExtension())
                    .initialize();
        }

        @Test
        void startsWithoutServerIntegration() {
            assertThat(container.isRunning(), is(true));
        }
    }
}
