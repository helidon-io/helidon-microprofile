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

package io.helidon.microprofile.config.core;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.ConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

class MpConfigProviderResolverTest {

    @Test
    void reRegisterConfigAfterServiceRegistryAccess() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Config original = resolver.getConfig(classLoader);
        if (original instanceof MpConfigProviderResolver.ConfigDelegate delegate) {
            original = delegate.delegate();
        }

        try {
            Config first = resolver.getBuilder()
                    .withSources(MpConfigSources.create(io.helidon.config.Config.builder()
                                                                .sources(ConfigSources.create(Map.of("foo", "bar")))
                                                                .build()))
                    .build();
            Config second = resolver.getBuilder()
                    .withSources(MpConfigSources.create(io.helidon.config.Config.builder()
                                                                .sources(ConfigSources.create(Map.of("foo", "baz")))
                                                                .build()))
                    .build();

            resolver.registerConfig(first, classLoader);
            io.helidon.config.Config registryConfig = Services.get(io.helidon.config.Config.class);
            resolver.registerConfig(second, classLoader);

            Config current = resolver.getConfig(classLoader);
            assertThat(current, sameInstance(registryConfig));
            assertThat(current.getValue("foo", String.class), is("baz"));
            assertThat(registryConfig.get("foo").asString().get(), is("baz"));
        } finally {
            resolver.registerConfig(original, classLoader);
            shutdownRegistry();
        }
    }

    @Test
    void serviceRegistryStartupAndConfigRegistrationAreAtomic() throws Exception {
        MpConfigProviderResolver.ConfigDelegate config =
                (MpConfigProviderResolver.ConfigDelegate) ConfigProviderResolver.instance().getConfig();
        shutdownRegistry();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<ServiceRegistryManager> activeManager = new AtomicReference<>();
        try {
            for (int i = 0; i < 25; i++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<ServiceRegistryManager> startFuture = executor.submit(() -> {
                    barrier.await();
                    return MpServiceRegistryBootstrap.start();
                });
                Future<?> configFuture = executor.submit(() -> {
                    barrier.await();
                    MpServiceRegistryBootstrap.configure(config);
                    return null;
                });

                ServiceRegistryManager manager = startFuture.get(10, TimeUnit.SECONDS);
                activeManager.set(manager);
                configFuture.get(10, TimeUnit.SECONDS);
                assertThat(GlobalServiceRegistry.registry(), sameInstance(manager.registry()));
                MpServiceRegistryBootstrap.shutdown(manager);
                activeManager.set(null);
            }
        } finally {
            ServiceRegistryManager manager = activeManager.get();
            if (manager != null) {
                MpServiceRegistryBootstrap.shutdown(manager);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS), is(true));
            shutdownRegistry();
        }
    }

    private static void shutdownRegistry() {
        ServiceRegistryManager manager = MpServiceRegistryBootstrap.start();
        MpServiceRegistryBootstrap.shutdown(manager);
    }
}
