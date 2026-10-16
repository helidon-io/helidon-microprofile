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

package io.helidon.microprofile.scheduling;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;

import io.helidon.common.context.Context;
import io.helidon.microprofile.cdi.Main;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.scheduling.Scheduling;
import io.helidon.scheduling.Task;
import io.helidon.scheduling.TaskManager;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.helidon.common.context.Contexts.runInContext;
import static io.helidon.microprofile.cdi.SeContainerInitializers.initialize;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulingStartupFailureTest {
    private static final ScopedValue<SchedulingStartupFailureTest> CURRENT_TEST = ScopedValue.newInstance();

    private final IllegalStateException startupFailure;
    private final CountDownLatch taskStarted;
    private final CountDownLatch taskInterrupted;
    private final CountDownLatch releaseTask;
    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private ServiceRegistryManager registryManager;
    private Context context;
    private SeContainer container;
    private ScheduledExecutorService executor;
    private boolean failStartup;

    SchedulingStartupFailureTest() {
        super();
        this.startupFailure = new IllegalStateException("Deliberate startup failure");
        this.taskStarted = new CountDownLatch(1);
        this.taskInterrupted = new CountDownLatch(1);
        this.releaseTask = new CountDownLatch(1);
    }

    @BeforeEach
    void setUp() {
        this.resolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.resolver.getConfig(this.classLoader).unwrap(Config.class);
        this.config = this.resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("schedule.core-pool-size", "1")))
                .build();
        this.resolver.registerConfig(this.config, this.classLoader);
        // Keep the registry caller-owned so its shutdown cannot mask missing executor cleanup.
        this.registryManager = ServiceRegistryManager.create();
        this.context = Context.create();
        this.context.register("helidon-registry-static-context", this.context);
        this.context.register("helidon-registry", this.registryManager.registry());
    }

    @AfterEach
    void tearDown() {
        try {
            runInContext(this.context, () -> {
                try {
                    if (this.container != null && this.container.isRunning()) {
                        this.container.close();
                    }
                } finally {
                    Main.shutdown();
                }
            });
        } finally {
            try {
                // Rescue the fixture if automatic executor cleanup was broken.
                if (this.executor != null) {
                    this.executor.shutdownNow();
                }
                this.releaseTask.countDown();
                this.registryManager.shutdown();
            } finally {
                this.resolver.releaseConfig(this.config);
                this.resolver.registerConfig(this.originalConfig, this.classLoader);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Launcher.class)
    void startupFailureStopsScheduledWork(Launcher launcher) throws Exception {
        this.failStartup = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> start(launcher));

        assertThat(failure, sameInstance(this.startupFailure));
        assertThat(this.executor.isShutdown(), is(true));
        assertThat(this.taskInterrupted.await(10, SECONDS), is(true));
        assertThat(this.executor.awaitTermination(10, SECONDS), is(true));
    }

    @ParameterizedTest
    @EnumSource(Launcher.class)
    void normalShutdownStopsScheduledWork(Launcher launcher) throws Exception {
        start(launcher);
        assertThat(this.executor.isShutdown(), is(false));

        runInContext(this.context, () -> {
            switch (launcher) {
            case MAIN -> Main.shutdown();
            case CDI -> this.container.close();
            }
        });

        assertThat(this.container.isRunning(), is(false));
        assertThat(this.executor.isShutdown(), is(true));
        assertThat(this.taskInterrupted.await(10, SECONDS), is(true));
        assertThat(this.executor.awaitTermination(10, SECONDS), is(true));
    }

    private void start(Launcher launcher) {
        this.container = runInContext(this.context, () -> ScopedValue.where(CURRENT_TEST, this).call(() ->
                switch (launcher) {
                case MAIN -> {
                    Main.main(new String[0]);
                    yield (SeContainer) CDI.current();
                }
                case CDI -> initialize(SeContainerInitializer.newInstance()
                        .disableDiscovery()
                        .addExtensions(new SchedulingCdiExtension(), new StartupFailureExtension()));
                }));
    }

    private enum Launcher {
        MAIN,
        CDI
    }

    @Dependent
    static class ScheduledBean {
        private final SchedulingStartupFailureTest test = CURRENT_TEST.get();

        @Scheduling.FixedRate(value = "PT0.01S", delayBy = "PT0S")
        void run() {
            this.test.taskStarted.countDown();
            try {
                this.test.releaseTask.await();
            } catch (InterruptedException _) {
                this.test.taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }

    // Main discovers this extension; other tests leave it inactive by not binding CURRENT_TEST.
    public static class StartupFailureExtension implements Extension {
        void discover(@Observes BeforeBeanDiscovery event) {
            if (CURRENT_TEST.isBound()) {
                event.addAnnotatedType(ScheduledBean.class, ScheduledBean.class.getName());
            }
        }

        void startup(@Observes Startup event) throws InterruptedException {
            if (CURRENT_TEST.isBound()) {
                SchedulingStartupFailureTest test = CURRENT_TEST.get();
                Collection<Task> tasks = GlobalServiceRegistry.registry().get(TaskManager.class).tasks();
                assertThat(tasks.size(), is(1));
                test.executor = tasks.iterator().next().executor();
                assertThat(test.taskStarted.await(10, SECONDS), is(true));
                if (test.failStartup) {
                    throw test.startupFailure;
                }
            }
        }
    }
}
