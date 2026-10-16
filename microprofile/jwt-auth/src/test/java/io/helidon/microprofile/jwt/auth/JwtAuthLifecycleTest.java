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

package io.helidon.microprofile.jwt.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.helidon.common.configurable.Resource;
import io.helidon.common.context.Context;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.security.SecurityCdiExtension;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.security.Security;
import io.helidon.security.jwt.Jwt;
import io.helidon.security.jwt.SignedJwt;
import io.helidon.security.jwt.jwk.JwkKeys;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.auth.LoginConfig;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.helidon.common.context.Contexts.globalContext;
import static io.helidon.common.context.Contexts.runInContext;
import static io.helidon.security.jwt.jwk.JwkRSA.ALG_RS256;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class JwtAuthLifecycleTest {

    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private ServiceRegistryManager registryManager;
    private Context context;
    private SeContainer container;
    private Security security;
    private String uri;

    @BeforeEach
    void saveConfiguration() {
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);

        // Other tests can initialize the global service registry before us, so make our own "global" registry to avoid
        // interference.
        this.registryManager = ServiceRegistryManager.create();
        this.context = Context.create();
        this.context.register("helidon-registry-static-context", this.context);
        this.context.register("helidon-registry", this.registryManager.registry());
    }

    @AfterEach
    void stopContainer() {
        try {
            if (this.container != null) {
                runInContext(this.context, this.container::close);
            }
        } finally {
            try {
                if (this.registryManager != null) {
                    runInContext(this.context, this.registryManager::shutdown);
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

    private static String signedToken() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.builder()
                .subject("lifecycle-user")
                .preferredUsername("lifecycle-user")
                .issuer("lifecycle.example")
                .algorithm(ALG_RS256)
                .keyId("verify-rsa")
                .issueTime(now)
                .expirationTime(now.plusSeconds(3600))
                .addPayloadClaim("automatic-groups", List.of("automatic"))
                .addPayloadClaim("configured-groups", List.of("configured"))
                .build();
        JwkKeys keys = JwkKeys.builder()
                .resource(Resource.create("sign-jwk.json"))
                .build();
        return SignedJwt.sign(jwt, keys.forKeyId("sign-rsa").orElseThrow()).tokenContent();
    }

    private void startContainer(Map<String, String> providerConfig) {
        Map<String, String> properties = new HashMap<>(Map.of(
                "server.port", "0",
                "mp.jwt.verify.publickey.location", "verify-jwk.json",
                "mp.jwt.verify.issuer", "lifecycle.example"));
        properties.putAll(providerConfig);
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(properties))
                .build();
        Extension configure = new Extension() {
            void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }
        };

        SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .addExtensions(configure)
                .addBeanClasses(JwtApplication.class, JwtResource.class);
        this.container = runInContext(this.context, initializer::initialize);
        this.security = this.container.getBeanManager().getExtension(SecurityCdiExtension.class).security().orElseThrow();
        int port = this.container.getBeanManager().getExtension(ServerCdiExtension.class).port();
        this.uri = "http://localhost:" + port + "/jwt-lifecycle";
    }

    @ApplicationScoped
    @LoginConfig(authMethod = "MP-JWT")
    public static class JwtApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(JwtResource.class);
        }
    }

    @RequestScoped
    @Path("/jwt-lifecycle")
    public static class JwtResource {
        @Inject
        private JsonWebToken token;

        @GET
        public String groups() {
            return String.join(",", token.getGroups());
        }
    }

    @Nested
    class AutomaticProvider {
        @BeforeEach
        void start() {
            startContainer(Map.of("jwt-groups-path", "automatic-groups"));
        }

        @Test
        void usesConfigurationFromBeanDiscovery() {
            try (Client client = ClientBuilder.newClient();
                 Response response = client.target(uri)
                         .request()
                         .header("Authorization", "Bearer " + signedToken())
                         .get()) {
                assertThat(response.getStatusInfo(), is(Response.Status.OK));
                assertThat(response.readEntity(String.class), is("automatic"));
            }
        }
    }

    @Nested
    class ConfiguredProvider {
        @BeforeEach
        void start() {
            startContainer(Map.of("jwt-groups-path", "automatic-groups",
                                  "security.providers.0.mp-jwt-auth.jwt-groups-path", "configured-groups"));
        }

        @Test
        void takesPrecedence() {
            try (Client client = ClientBuilder.newClient();
                 Response response = client.target(uri)
                         .request()
                         .header("Authorization", "Bearer " + signedToken())
                         .get()) {
                assertThat(response.getStatusInfo(), is(Response.Status.OK));
                assertThat(response.readEntity(String.class), is("configured"));
            }
        }
    }
}
