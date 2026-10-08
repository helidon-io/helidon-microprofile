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

package io.helidon.microprofile.cors;

import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.ServerCdiExtension;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static io.helidon.http.HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN_NAME;
import static io.helidon.http.HeaderNames.ACCESS_CONTROL_REQUEST_METHOD_NAME;
import static io.helidon.http.HeaderNames.ORIGIN_NAME;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class CorsConfigTimingTest {

    private static final String ALLOW_RESTRICTED_HEADERS = "sun.net.http.allowRestrictedHeaders";

    @Test
    void testConfigurationFromBeanDiscovery() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Config originalConfig = resolver.getConfig(classLoader);
        Config config = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of(
                        "server.port", "0",
                        "cors.paths.0.path-pattern", "/cors3",
                        "cors.paths.0.allow-origins", "http://discovery.example",
                        "cors.paths.0.allow-methods", "PUT")))
                .build();
        Extension configure = new Extension() {
            void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }
        };

        String originalAllowRestrictedHeaders = System.getProperty(ALLOW_RESTRICTED_HEADERS);
        System.setProperty(ALLOW_RESTRICTED_HEADERS, "true");
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addExtensions(configure)
                .addBeanClasses(CorsTest.CorsResource3.class)
                .initialize();
             Client client = ClientBuilder.newClient()) {
            int port = container.getBeanManager().getExtension(ServerCdiExtension.class).port();
            WebTarget target = client.target("http://localhost:" + port + "/cors3");
            try (Response allowed = target.request()
                    .header(ORIGIN_NAME, "http://discovery.example")
                    .header(ACCESS_CONTROL_REQUEST_METHOD_NAME, "PUT")
                    .options()) {
                assertThat(allowed.getStatusInfo(), is(Response.Status.OK));
                assertThat(allowed.getHeaderString(ACCESS_CONTROL_ALLOW_ORIGIN_NAME), is("http://discovery.example"));
            }

            try (Response forbidden = target.request()
                    .header(ORIGIN_NAME, "http://other.example")
                    .header(ACCESS_CONTROL_REQUEST_METHOD_NAME, "PUT")
                    .options()) {
                assertThat(forbidden.getStatusInfo(), is(Response.Status.FORBIDDEN));
            }
        } finally {
            resolver.releaseConfig(config);
            resolver.registerConfig(originalConfig, classLoader);
            if (originalAllowRestrictedHeaders == null) {
                System.clearProperty(ALLOW_RESTRICTED_HEADERS);
            } else {
                System.setProperty(ALLOW_RESTRICTED_HEADERS, originalAllowRestrictedHeaders);
            }
        }
    }
}
