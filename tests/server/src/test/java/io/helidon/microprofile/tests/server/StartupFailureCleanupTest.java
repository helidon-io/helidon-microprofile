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

package io.helidon.microprofile.tests.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;

import io.helidon.microprofile.cdi.Main;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.Server;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.scheduling.Scheduling;
import io.helidon.scheduling.Task;
import io.helidon.scheduling.TaskManager;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;

import jakarta.enterprise.context.Dependent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupFailureCleanupTest {
    private static final ScopedValue<TestApplication> CURRENT_APPLICATION = ScopedValue.newInstance();

    private final TestApplication application;
    private final TestApplication replacement;
    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;

    StartupFailureCleanupTest() {
        super();
        this.application = new TestApplication();
        this.replacement = new TestApplication();
    }

    @BeforeEach
    void setUp() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);
        registerConfig(0);
    }

    @AfterEach
    void tearDown() {
        try {
            try {
                Main.shutdown();
            } finally {
                try {
                    this.replacement.close();
                } finally {
                    this.application.close();
                }
            }
        } finally {
            this.resolver.releaseConfig(this.config);
            this.resolver.registerConfig(this.originalConfig, this.classLoader);
        }
    }

    @ParameterizedTest
    @EnumSource(StartupType.class)
    void startupFailureReleasesResourcesAndAllowsReplacement(StartupType startupType) throws Exception {
        this.application.failStartup = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> start(startupType, this.application, 0));

        assertThat(failure, sameInstance(this.application.failure));
        assertStopped(this.application);
        assertThat(this.application.executorShutdownAtHttpStop, is(true));

        registerConfig(this.application.port);
        start(startupType, this.replacement, this.application.port);

        assertThat(this.replacement.port, is(this.application.port));
        assertThat(this.replacement.registry, not(sameInstance(this.application.registry)));
        assertThat(this.replacement.executor, not(sameInstance(this.application.executor)));
        assertThat(this.replacement.executor.isShutdown(), is(false));
        assertThat(this.replacement.webServer.isRunning(), is(true));
        assertThat(GlobalServiceRegistry.registry(), sameInstance(this.replacement.registry));

        switch (startupType) {
        case MAIN -> Main.shutdown();
        case SERVER -> this.replacement.server.stop();
        }

        assertStopped(this.replacement);
    }

    private static void assertStopped(TestApplication application) throws InterruptedException {
        assertThat(application.port, greaterThan(0));
        assertThat(application.webServer.isRunning(), is(false));
        assertThat(application.httpStops, is(1));
        assertThat(application.registryAvailableAtHttpStop, is(true));
        assertThat(application.executor.isShutdown(), is(true));
        assertThat(application.taskInterrupted.await(10, SECONDS), is(true));
        assertThat(application.executor.awaitTermination(10, SECONDS), is(true));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    private void registerConfig(int port) {
        if (this.config != null) {
            this.resolver.releaseConfig(this.config);
        }
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("server.host", "127.0.0.1",
                                                           "server.port", Integer.toString(port),
                                                           "schedule.core-pool-size", "1")))
                .build();
        this.resolver.registerConfig(this.config, this.classLoader);
    }

    private void start(StartupType startupType, TestApplication application, int port) {
        ScopedValue.where(CURRENT_APPLICATION, application).run(() -> {
            switch (startupType) {
            case MAIN -> Main.main(new String[0]);
            case SERVER -> {
                application.server = Server.builder().config(this.config).host("127.0.0.1").port(port).build();
                application.server.start();
            }
            }
            application.container = (SeContainer) CDI.current();
        });
    }

    private enum StartupType {
        MAIN,
        SERVER
    }

    private static class TestApplication {
        private final IllegalStateException failure = new IllegalStateException("Deliberate startup failure");
        private final CountDownLatch taskStarted = new CountDownLatch(1);
        private final CountDownLatch taskInterrupted = new CountDownLatch(1);
        private final CountDownLatch releaseTask = new CountDownLatch(1);
        private ServiceRegistry registry;
        private ScheduledExecutorService executor;
        private WebServer webServer;
        private Server server;
        private SeContainer container;
        private int port;
        private int httpStops;
        private boolean registryAvailableAtHttpStop;
        private boolean executorShutdownAtHttpStop;
        private boolean failStartup;

        private void close() {
            try {
                if (this.container != null && this.container.isRunning()) {
                    this.container.close();
                }
            } finally {
                try {
                    // Rescue resources only after assertions, if automatic cleanup failed.
                    if (this.webServer != null && this.webServer.isRunning()) {
                        this.webServer.stop();
                    }
                } finally {
                    if (this.executor != null) {
                        this.executor.shutdownNow();
                    }
                    this.releaseTask.countDown();
                }
            }
        }
    }

    static class ScheduledBean {
        private final TestApplication application = CURRENT_APPLICATION.get();

        @Scheduling.FixedRate(value = "PT0.01S", delayBy = "PT0S")
        void run() {
            this.application.taskStarted.countDown();
            try {
                this.application.releaseTask.await();
            } catch (InterruptedException _) {
                this.application.taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static class StartupFailureExtension implements Extension {
        void discover(@Observes BeforeBeanDiscovery event) {
            if (CURRENT_APPLICATION.isBound()) {
                event.addAnnotatedType(ScheduledBean.class, ScheduledBean.class.getName())
                        .add(Dependent.Literal.INSTANCE);
            }
        }

        void configure(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
            if (CURRENT_APPLICATION.isBound()) {
                TestApplication application = CURRENT_APPLICATION.get();
                application.registry = GlobalServiceRegistry.registry();
                beanManager.getExtension(ServerCdiExtension.class).serverRoutingBuilder().addFeature(new HttpFeature() {
                    @Override
                    public void setup(HttpRouting.Builder routing) {
                        routing.get("/cleanup", (req, res) -> res.send("ready"));
                    }

                    @Override
                    public void afterStart(WebServer webServer) {
                        application.webServer = webServer;
                        application.port = webServer.port();
                    }

                    @Override
                    public void afterStop() {
                        application.httpStops++;
                        application.registryAvailableAtHttpStop = GlobalServiceRegistry.configured()
                                && GlobalServiceRegistry.registry() == application.registry;
                        application.executorShutdownAtHttpStop = application.executor != null
                                && application.executor.isShutdown();
                    }
                });
            }
        }

        void startup(@Observes Startup event) throws Exception {
            if (CURRENT_APPLICATION.isBound()) {
                TestApplication application = CURRENT_APPLICATION.get();
                Collection<Task> tasks = application.registry.get(TaskManager.class).tasks();
                assertThat(tasks.size(), is(1));
                application.executor = tasks.iterator().next().executor();
                assertThat(application.taskStarted.await(10, SECONDS), is(true));
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + application.port + "/cleanup"))
                            .timeout(Duration.ofSeconds(10))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode(), is(200));
                    assertThat(response.body(), is("ready"));
                }
                if (application.failStartup) {
                    throw application.failure;
                }
            }
        }
    }
}
