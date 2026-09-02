/*
 * Copyright (c) 2019, 2026 Oracle and/or its affiliates.
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * Unit test for {@link Main}.
 */
class MainTest {
    @Test
    void testCdiStarted() {
        Main.main(new String[0]);

        Instance<TestBean> select = CDI.current().select(TestBean.class);
        TestBean bean = select.get();
        assertThat(bean, notNullValue());
        TestBean2 testBean2 = bean.getTestBean2();
        assertThat(testBean2, notNullValue());
        assertThat(testBean2.message(), is("Hello"));

        Main.shutdown();
    }

    @Test
    void testPublishesConfigAfterInstallingApplicationRegistry() {
        RegistryLifecycleService.PRE_DESTROY.set(0);
        Config config = ConfigProviderResolver.instance().getConfig();
        assertThat("config access must not initialize the global registry",
                   GlobalServiceRegistry.configured(),
                   is(false));

        try {
            Main.main(new String[0]);
            assertThat(GlobalServiceRegistry.registry().get(io.helidon.config.Config.class), sameInstance(config));
            GlobalServiceRegistry.registry().get(RegistryLifecycleService.class);
        } finally {
            Main.shutdown();
        }
        assertThat("application registry is closed", RegistryLifecycleService.PRE_DESTROY.get(), is(1));
    }

    @Test
    void testConfigAccessDuringRegistryShutdownDoesNotRestoreGlobal() {
        RegistryLifecycleService.ACCESS_CONFIG_ON_DESTROY.set(true);
        try {
            Main.main(new String[0]);
            GlobalServiceRegistry.registry().get(RegistryLifecycleService.class);
            Main.shutdown();
        } finally {
            RegistryLifecycleService.ACCESS_CONFIG_ON_DESTROY.set(false);
            Main.shutdown();
        }
        assertThat("config access during service cleanup must not restore the global registry",
                   GlobalServiceRegistry.configured(),
                   is(false));
    }

    @Test
    void testShutdownClosesAndReplacesGlobalServiceRegistry() {
        RegistryCleanup.PRE_DESTROY.set(0);
        RegistryLifecycleService.PRE_DESTROY.set(0);

        Main.main(new String[0]);
        CDI.current().select(RegistryCleanup.class).get().activate();
        ServiceRegistry firstRegistry = GlobalServiceRegistry.registry();
        assertThat(firstRegistry.get(RegistryLifecycleService.class), notNullValue());

        Main.shutdown();

        assertThat("CDI cleanup used the first service registry", RegistryCleanup.PRE_DESTROY.get(), is(1));
        assertThat("first service registry is closed", RegistryLifecycleService.PRE_DESTROY.get(), is(1));

        try {
            Main.main(new String[0]);
            CDI.current().select(RegistryCleanup.class).get().activate();
            ServiceRegistry secondRegistry = GlobalServiceRegistry.registry();
            assertThat(secondRegistry, not(sameInstance(firstRegistry)));
            assertThat(secondRegistry.get(RegistryLifecycleService.class), notNullValue());
        } finally {
            Main.shutdown();
        }
        assertThat("CDI cleanup used the second service registry", RegistryCleanup.PRE_DESTROY.get(), is(2));
        assertThat("second service registry is closed", RegistryLifecycleService.PRE_DESTROY.get(), is(2));
    }

    @Test
    void testEvents() {
        // build time
        HelidonContainer instance = HelidonContainer.instance();

        BeanManager beanManager = CDI.current().getBeanManager();
        TestExtension extension = beanManager.getExtension(TestExtension.class);

        assertThat(extension.runtimeConfig(), nullValue());
        assertThat(extension.events(), contains(TestExtension.BUILD_TIME_START,
                                                TestExtension.BUILD_TIME_END));

        Config config = ConfigProviderResolver.instance()
                .getBuilder()
                .withSources(MpConfigSources.create(Map.of("key", "value")))
                .build();

        ConfigProviderResolver.instance()
                .registerConfig(config, Thread.currentThread().getContextClassLoader());

        instance.start();

        Object runtimeConfig = extension.runtimeConfig();
        assertThat(runtimeConfig, instanceOf(Config.class));
        Config mpConfig = (Config) runtimeConfig;
        try {
            mpConfig = ((Config) runtimeConfig).unwrap(Config.class);
        } catch (Exception ignored) {
        }
        assertThat(mpConfig, sameInstance(config));

        instance.shutdown();
        assertThat(extension.events(), is(List.of(TestExtension.BUILD_TIME_START,
                                                  TestExtension.BUILD_TIME_END,
                                                  TestExtension.RUNTIME_INIT,
                                                  TestExtension.APPLICATION_INIT,
                                                  TestExtension.APPLICATION_BEFORE_DESTROYED,
                                                  TestExtension.APPLICATION_DESTROYED)));
    }

    @ApplicationScoped
    static class RegistryCleanup {
        private static final AtomicInteger PRE_DESTROY = new AtomicInteger();

        private final ServiceRegistry registry;

        @Inject
        RegistryCleanup(ServiceRegistry registry) {
            this.registry = registry;
        }

        void activate() {
        }

        @PreDestroy
        void preDestroy() {
            registry.get(io.helidon.config.Config.class);
            PRE_DESTROY.incrementAndGet();
        }
    }

    @Service.Singleton
    static class RegistryLifecycleService {
        private static final AtomicBoolean ACCESS_CONFIG_ON_DESTROY = new AtomicBoolean();
        private static final AtomicInteger PRE_DESTROY = new AtomicInteger();

        @Service.PreDestroy
        void preDestroy() {
            if (ACCESS_CONFIG_ON_DESTROY.get()) {
                ConfigProviderResolver.instance().getConfig();
            }
            PRE_DESTROY.incrementAndGet();
        }
    }
}
