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

import io.helidon.microprofile.cdi.Main;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerStartupFailureTest {
    private static final ScopedValue<ServerStartupFailureTest> CURRENT_TEST = ScopedValue.newInstance();

    private final IllegalStateException startupFailure = new IllegalStateException("Deliberate startup failure");
    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private ServerCdiExtension extension;
    private WebServer webServer;
    private int boundPort;
    private boolean registryAvailableAtHttpStop;
    private Server replacement;

    @BeforeEach
    void setUp() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);
        this.config = this.resolver.getBuilder().build();
    }

    @AfterEach
    void tearDown() {
        try {
            try {
                if (this.replacement != null) {
                    this.replacement.stop();
                }
            } finally {
                Main.shutdown();
            }
        } finally {
            try {
                if (this.extension != null) {
                    this.extension.stopServer();
                }
            } finally {
                this.resolver.releaseConfig(this.config);
                this.resolver.registerConfig(this.originalConfig, this.classLoader);
            }
        }
    }

    @Test
    void startupFailureClosesListenerAndAllowsReplacement() {
        Server server = buildServer(0);

        IllegalStateException failure = ScopedValue.where(CURRENT_TEST, this)
                .call(() -> assertThrows(IllegalStateException.class, server::start));

        assertThat(failure, sameInstance(this.startupFailure));
        assertThat(this.boundPort, greaterThan(0));
        assertThat(this.webServer.isRunning(), is(false));
        assertThat(this.extension.started(), is(false));
        assertThat(this.registryAvailableAtHttpStop, is(true));
        assertThat(GlobalServiceRegistry.configured(), is(false));

        Server rejected = buildServer(0);
        this.replacement = buildServer(this.boundPort).start();
        assertThat(this.replacement.port(), is(this.boundPort));

        this.extension.stopServer();
        try {
            // Make sure ServerCdiExtension's static IN_PROGRESS_OR_RUNNING flag stays as it is.
            assertThrows(IllegalStateException.class, rejected::start);
        } finally {
            if (rejected.port() > 0) {
                rejected.stop();
            }
        }
    }

    @Test
    void bindingFailureReleasesGuard() throws Exception {
        int port;
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = occupied.getLocalPort();
            Server server = buildServer(port);

            RuntimeException failure = ScopedValue.where(CURRENT_TEST, this)
                    .call(() -> assertThrows(RuntimeException.class, server::start));

            assertThat(failure.getMessage(), containsString("Failed to start webserver"));
            assertThat(this.extension.started(), is(false));
            assertThat(GlobalServiceRegistry.configured(), is(false));
        }

        this.replacement = buildServer(port).start();
        assertThat(this.replacement.port(), is(port));
    }

    @Test
    void mainStartupFailureClosesListenerAndAllowsReplacement() {
        registerConfigWithServerPort(0);

        IllegalStateException failure = ScopedValue.where(CURRENT_TEST, this)
                .call(() -> assertThrows(IllegalStateException.class, () -> Main.main(new String[0])));

        assertThat(failure, sameInstance(this.startupFailure));
        assertThat(this.boundPort, greaterThan(0));
        assertThat(this.webServer.isRunning(), is(false));
        assertThat(this.extension.started(), is(false));
        assertThat(this.registryAvailableAtHttpStop, is(true));
        assertThat(GlobalServiceRegistry.configured(), is(false));

        Server rejected = buildServer(0);
        registerConfigWithServerPort(this.boundPort);
        Main.main(new String[0]);
        SeContainer replacementContainer = (SeContainer) CDI.current();
        assertThat(replacementContainer.getBeanManager().getExtension(ServerCdiExtension.class).port(), is(this.boundPort));

        this.extension.stopServer();
        try {
            // Delayed cleanup must not clear the shared running flag claimed by the replacement.
            assertThrows(IllegalStateException.class, rejected::start);
            assertThat(replacementContainer.isRunning(), is(true));
        } finally {
            if (rejected.port() > 0) {
                rejected.stop();
            }
        }

        Main.shutdown();
        assertThat(replacementContainer.isRunning(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void mainBindingFailureReleasesStartupFlag() throws Exception {
        int port;
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = occupied.getLocalPort();
            registerConfigWithServerPort(port);

            RuntimeException failure = ScopedValue.where(CURRENT_TEST, this)
                    .call(() -> assertThrows(RuntimeException.class, () -> Main.main(new String[0])));

            assertThat(failure.getMessage(), containsString("Failed to start webserver"));
            assertThat(this.extension.started(), is(false));
            assertThat(GlobalServiceRegistry.configured(), is(false));
        }

        Main.main(new String[0]);
        SeContainer replacementContainer = (SeContainer) CDI.current();
        assertThat(replacementContainer.getBeanManager().getExtension(ServerCdiExtension.class).port(), is(port));

        Main.shutdown();
        assertThat(replacementContainer.isRunning(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    private Server buildServer(int port) {
        return Server.builder().config(this.config).host("127.0.0.1").port(port).build();
    }

    private void registerConfigWithServerPort(int port) {
        this.resolver.releaseConfig(this.config);
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("server.host", "127.0.0.1", "server.port", Integer.toString(port))))
                .build();
        this.resolver.registerConfig(this.config, this.classLoader);
    }

    @ApplicationScoped
    public static class FailingStartup {
        void fail(@Observes Startup event) {
            throw CURRENT_TEST.get().startupFailure;
        }
    }

    public static class StartupFailureExtension implements Extension {
        void discover(@Observes BeforeBeanDiscovery event) {
            if (CURRENT_TEST.isBound()) {
                event.addAnnotatedType(FailingStartup.class, FailingStartup.class.getName());
            }
        }

        void capture(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
            if (CURRENT_TEST.isBound()) {
                ServerStartupFailureTest test = CURRENT_TEST.get();
                test.extension = beanManager.getExtension(ServerCdiExtension.class);
                test.extension.serverRoutingBuilder().addFeature(new HttpFeature() {
                    @Override
                    public void setup(HttpRouting.Builder routing) {
                    }

                    @Override
                    public void afterStart(WebServer webServer) {
                        test.webServer = webServer;
                        test.boundPort = webServer.port();
                    }

                    @Override
                    public void afterStop() {
                        test.registryAvailableAtHttpStop = GlobalServiceRegistry.configured();
                    }
                });
            }
        }
    }
}
