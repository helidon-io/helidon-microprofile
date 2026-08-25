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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.config.core.MpServiceRegistryBootstrap;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistryManager;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ServiceRegistryShutdownDeadlockTest {
    private static final CountDownLatch PRE_DESTROY_ENTERED = new CountDownLatch(1);

    @Test
    void configRegistrationDoesNotDeadlockRegistryShutdown() throws Exception {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Config originalConfig = resolver.getConfig(classLoader);
        Config runtimeConfig = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("mp.initializer.allow", "true")))
                .build();
        resolver.registerConfig(runtimeConfig, classLoader);

        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

        try {
            Main.main(new String[0]);
            GlobalServiceRegistry.registry().get(ConfigReadingService.class);

            Config blockingConfig = blockingConfig(runtimeConfig, registrationEntered, allowRegistration);
            Future<?> registration = executor.submit(() -> resolver.registerConfig(blockingConfig, classLoader));
            assertThat("Config registration should enter conversion while holding the resolver lock",
                       registrationEntered.await(10, TimeUnit.SECONDS),
                       is(true));

            Future<?> shutdown = executor.submit(Main::shutdown);
            assertThat("Service cleanup should begin",
                       PRE_DESTROY_ENTERED.await(10, TimeUnit.SECONDS),
                       is(true));

            allowRegistration.countDown();
            registration.get(10, TimeUnit.SECONDS);
            shutdown.get(10, TimeUnit.SECONDS);
        } finally {
            allowRegistration.countDown();
            executor.shutdownNow();
            Main.shutdown();
            resolver.registerConfig(originalConfig, classLoader);
            ServiceRegistryManager manager = MpServiceRegistryBootstrap.start();
            MpServiceRegistryBootstrap.shutdown(manager);
        }
    }

    private static Config blockingConfig(Config delegate,
                                         CountDownLatch registrationEntered,
                                         CountDownLatch allowRegistration) {
        return (Config) Proxy.newProxyInstance(ServiceRegistryShutdownDeadlockTest.class.getClassLoader(),
                                               new Class<?>[] {Config.class},
                                               (proxy, method, args) -> {
                                                   if (method.getName().equals("getConfigSources")
                                                           && method.getParameterCount() == 0) {
                                                       registrationEntered.countDown();
                                                       if (!allowRegistration.await(10, TimeUnit.SECONDS)) {
                                                           throw new IllegalStateException("Config registration was not released");
                                                       }
                                                   }
                                                   try {
                                                       return method.invoke(delegate, args);
                                                   } catch (InvocationTargetException e) {
                                                       throw e.getCause();
                                                   }
                                               });
    }

    @Service.Singleton
    static class ConfigReadingService {
        @Service.PreDestroy
        void preDestroy() {
            PRE_DESTROY_ENTERED.countDown();
            ConfigProvider.getConfig();
        }
    }
}
