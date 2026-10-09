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

package io.helidon.microprofile.tyrus;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.helidon.common.context.Context;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.RoutingName;
import io.helidon.microprofile.server.RoutingPath;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.websocket.Endpoint;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerApplicationConfig;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.helidon.common.context.Contexts.runInContext;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSocketLifecycleTest {

    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private ServiceRegistryManager registryManager;
    private Context context;
    private SeContainerInitializer initializer;
    private SeContainer container;
    private EchoClient client;

    @BeforeEach
    void setUp() {
        resolver = ConfigProviderResolver.instance();
        classLoader = Thread.currentThread().getContextClassLoader();
        originalConfig = resolver.getConfig(classLoader).unwrap(Config.class);

        // Own the registry so that the failed-initialization case also has deterministic cleanup.
        registryManager = ServiceRegistryManager.create();
        context = Context.create();
        context.register("helidon-registry-static-context", context);
        context.register("helidon-registry", registryManager.registry());
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) {
                client.shutdown();
            }
        } finally {
            try {
                if (container != null) {
                    runInContext(context, container::close);
                }
            } finally {
                try {
                    if (registryManager != null) {
                        registryManager.shutdown();
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
    }

    private void configure(Map<String, String> routingConfig) {
        Map<String, String> properties = new HashMap<>(Map.of(
                "server.port", "0",
                EndpointApplication.class.getName() + ".routing-path.path", "configured",
                EndpointApplication.class.getName() + ".routing-name.name", "websocket"));
        properties.putAll(routingConfig);
        config = resolver.getBuilder()
                .withSources(MpConfigSources.create(properties))
                .build();
        Extension configure = new Extension() {
            void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }
        };
        initializer = SeContainerInitializer.newInstance()
                .addExtensions(configure)
                .addBeanClasses(EndpointApplication.class, EchoEndpoint.class);
    }

    private void startContainer() {
        container = runInContext(context, initializer::initialize);
    }

    @Dependent
    @RoutingPath("/annotation")
    @RoutingName(value = "annotation", required = true)
    public static class EndpointApplication implements ServerApplicationConfig {
        @Override
        public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> endpoints) {
            return Set.of();
        }

        @Override
        public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> endpoints) {
            return Set.of(EchoEndpoint.class);
        }
    }

    @ServerEndpoint("/echo")
    public static class EchoEndpoint {
        @OnMessage
        public String echo(String message) {
            return message;
        }
    }

    @Nested
    class ConfiguredSocket {
        @BeforeEach
        void start() {
            configure(Map.of("server.sockets.0.name", "websocket",
                             "server.sockets.0.port", "0"));
            startContainer();
            int port = container.getBeanManager().getExtension(ServerCdiExtension.class).port("websocket");
            client = new EchoClient(URI.create("ws://localhost:" + port + "/configured/echo"));
        }

        @Test
        void usesPathAndSocketFromBeanDiscovery() throws Exception {
            client.echo("configured socket");
        }
    }

    @Nested
    class MissingOptionalSocket {
        @BeforeEach
        void start() {
            configure(Map.of(EndpointApplication.class.getName() + ".routing-name.required", "false"));
            startContainer();
            int port = container.getBeanManager().getExtension(ServerCdiExtension.class).port();
            client = new EchoClient(URI.create("ws://localhost:" + port + "/configured/echo"));
        }

        @Test
        void fallsBackToDefaultSocket() throws Exception {
            client.echo("default socket");
        }
    }

    @Nested
    class MissingRequiredSocket {
        @BeforeEach
        void prepare() {
            configure(Map.of(EndpointApplication.class.getName() + ".routing-name.required", "true"));
        }

        @Test
        void rejectsInitialization() {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                          WebSocketLifecycleTest.this::startContainer);
            assertThat(exception.getMessage(), containsString("requires configured listener (socket name) websocket"));
            assertThat(exception.getMessage(), containsString(EndpointApplication.class.getName()));
        }
    }
}
