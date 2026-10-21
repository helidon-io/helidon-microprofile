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
package io.helidon.microprofile.cdi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;
import io.helidon.config.Config;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.helidon.common.context.Contexts.runInContext;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonContainerTest {
    private static final ScopedValue<HelidonContainerTest> CURRENT_TEST = ScopedValue.newInstance();

    private final List<SeContainer> containers = new ArrayList<>();
    private final IllegalStateException failure = new IllegalStateException("Deliberate startup failure");

    private ServiceRegistryExtension failedRegistryExtension;

    @AfterEach
    void closeContainers() {
        try {
            for (SeContainer container : this.containers.reversed()) {
                if (container.isRunning()) {
                    container.close();
                }
            }
        } finally {
            if (this.failedRegistryExtension != null) {
                this.failedRegistryExtension.shutdown();
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void lookupAndShutdownDoNotInitializeCdi() {
        assertThrows(IllegalStateException.class, CDI::current);
        HelidonContainer helidonContainer = HelidonContainer.instance();
        assertThat(helidonContainer.context(), sameInstance(Contexts.globalContext()));
        helidonContainer.shutdown();
        helidonContainer.shutdown();
        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @SuppressWarnings("deprecation")
    @Test
    void initializesInProperContext() {
        Context context = Context.create();
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        context.register("helidon-registry-static-context", context);
        context.register("helidon-registry", manager.registry());
        Config config = Config.empty();
        runInContext(context, () -> Services.set(Config.class, config));
        HelidonContainer helidonContainer = runInContext(context, HelidonContainer::instance);
        try {
            SeContainer container = retain(helidonContainer.start());
            assertThat(helidonContainer.context(), sameInstance(context));
            assertThat(helidonContainer.start(), sameInstance(container));
            assertThat(container.select(ServiceRegistry.class).get().get(Config.class), sameInstance(config));
            assertThat(GlobalServiceRegistry.configured(), is(false));
            helidonContainer.shutdown();
            assertThat(container.isRunning(), is(false));
        } finally {
            // Close CDI before the caller-owned contextual registry, including on assertion failure.
            closeContainers();
            runInContext(context, manager::shutdown);
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void wrapsRunningContainerAndClosesInCapturedContext() {
        ShutdownObserver observer = new ShutdownObserver();
        SeContainer container = retain(initializer().addExtensions(observer).initialize());
        Context context = Context.create();
        HelidonContainer helidonContainer = runInContext(context, HelidonContainer::instance);
        assertThat(helidonContainer.start(), sameInstance(container));
        runInContext(Context.create(), () -> assertThat(helidonContainer.context(), sameInstance(context)));

        helidonContainer.shutdown();

        assertThat(container.isRunning(), is(false));
        assertThat(observer.context, sameInstance(context));
        assertThrows(IllegalStateException.class, helidonContainer::shutdown);
    }

    @SuppressWarnings("deprecation")
    @Test
    void helidonContainerUsesSeContainerStartedLater() {
        HelidonContainer helidonContainer = HelidonContainer.instance();
        SeContainer seContainer = retain(initializer().initialize());
        assertThat(helidonContainer.start(), sameInstance(seContainer));
        helidonContainer.shutdown();
        assertThat(seContainer.isRunning(), is(false));
    }

    @SuppressWarnings("deprecation")
    @Test
    void pendingHelidonContainersShareOneSeContainer() throws Exception {
        HelidonContainer first = HelidonContainer.instance();
        HelidonContainer second = HelidonContainer.instance();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<SeContainer> firstStart = executor.submit(() -> {
                start.await();
                return first.start();
            });
            Future<SeContainer> secondStart = executor.submit(() -> {
                start.await();
                return second.start();
            });
            start.countDown();
            SeContainer seContainer = retain(firstStart.get(30, SECONDS));
            assertThat(retain(secondStart.get(30, SECONDS)), sameInstance(seContainer));
            second.shutdown();
            assertThat(seContainer.isRunning(), is(false));
            assertThrows(IllegalStateException.class, first::start);
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void closedHelidonContainerDoesNotSelectReplacementContainer() {
        SeContainer first = retain(initializer().initialize());
        HelidonContainer helidonContainer = HelidonContainer.instance();
        first.close();

        SeContainer second = retain(initializer().initialize());
        assertThrows(IllegalStateException.class, helidonContainer::start);
        assertThrows(IllegalStateException.class, helidonContainer::shutdown);
        assertThat(second.isRunning(), is(true));
        assertThat(HelidonContainer.instance().start(), sameInstance(second));
    }

    @SuppressWarnings("deprecation")
    @Test
    void startupFailureClosesOwnedRegistryAndAllowsRetry() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
        HelidonContainer adapter = HelidonContainer.instance();

        ScopedValue.where(CURRENT_TEST, this).run(() ->
                assertThat(assertThrows(IllegalStateException.class, adapter::start), sameInstance(this.failure)));

        assertThat(GlobalServiceRegistry.configured(), is(false));

        SeContainer replacement = retain(adapter.start());
        assertThat(replacement.isRunning(), is(true));
        assertThat(GlobalServiceRegistry.configured(), is(true));
        adapter.shutdown();
        assertThat(replacement.isRunning(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    private static SeContainerInitializer initializer() {
        return SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(TestBean2.class);
    }

    private SeContainer retain(SeContainer container) {
        this.containers.add(container);
        return container;
    }

    // Discovered for the adapter's bootstrap; inactive outside this test's failing initialization.
    public static class StartupFailureExtension implements Extension {
        void startup(@Observes Startup event, BeanManager beanManager) {
            if (CURRENT_TEST.isBound()) {
                HelidonContainerTest test = CURRENT_TEST.get();
                test.failedRegistryExtension = beanManager.getExtension(ServiceRegistryExtension.class);
                assertThat(GlobalServiceRegistry.configured(), is(true));
                throw test.failure;
            }
        }
    }

    static class ShutdownObserver implements Extension {
        private Context context;

        void shutdown(@Observes BeforeShutdown event) {
            this.context = Contexts.context().orElseThrow();
        }
    }
}
