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

import java.util.Map;

import io.helidon.logging.common.LogConfig;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit test for {@link HelidonContainerInitializer}.
 */
class HelidonContainerInitializerTest {
    private static ConfigProviderResolver configResolver;
    private static ClassLoader cl;
    private static Config defaultConfig;

    static {
        LogConfig.initClass();
    }

    @BeforeAll
    static void initClass() {
        configResolver = ConfigProviderResolver.instance();
        cl = Thread.currentThread().getContextClassLoader();
        defaultConfig = configResolver.getBuilder().build();
    }

    @AfterEach
    @BeforeEach
    void resetConfig() {
        // restore the config to default (also before first test, as other tests may have broken it)
        configResolver.registerConfig(defaultConfig, cl);
    }

    @Test
    void testRestart() {
        // this is a reproducer for bug 1554
        SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize();

        container.close();

        try {
            Main.main(new String[0]);
        } finally {
            Main.shutdown();
        }
    }

    @Test
    void testRestart2() {
        // this is a reproducer for bug 1554
        SeContainerInitializer seContainerInitializer = SeContainerInitializer.newInstance();
        assertThat(seContainerInitializer, instanceOf(HelidonContainerInitializer.class));
        seContainerInitializer
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize();

        ((SeContainer) CDI.current()).close();

        try {
            Main.main(new String[0]);
        } finally {
            Main.shutdown();
        }
    }

    @Test
    void testSeInitializerWithoutOptIn() {
        assertContainerLifecycle();
    }

    @Test
    void testSeInitializerIgnoresObsoleteProperties() {
        Config config = configResolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("mp.initializer.allow", "false",
                                                           "mp.initializer.no-warn", "false")))
                .build();
        configResolver.registerConfig(config, cl);

        assertContainerLifecycle();
    }

    @Test
    void testUnusedInitializerDoesNotStartServiceRegistry() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
        try {
            SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addBeanClasses(TestBean.class);

            assertThat("preparing an initializer must not start the service registry",
                       GlobalServiceRegistry.configured(),
                       is(false));
        } finally {
            MpServiceRegistry.shutdown();
        }
    }

    @Test
    void testPrepareInitializerWhileContainerIsRunning() {
        SeContainerInitializer nextInitializer;
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize()) {
            nextInitializer = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addBeanClasses(TestBean.class);

            assertThat("preparing another initializer must not replace the running container",
                       HelidonContainer.instance().start(),
                       sameInstance(container));
        }
        assertThat(GlobalServiceRegistry.configured(), is(false));

        try (SeContainer container = nextInitializer.initialize()) {
            assertThat(GlobalServiceRegistry.configured(), is(true));
            assertThat(container.select(TestBean.class).get().test(), is("test"));
        }
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void testStartUninitializedContainer() {
        HelidonContainer container = new HelidonContainerImpl();

        assertThrows(IllegalStateException.class, container::start);

        assertContainerLifecycle();
    }

    @Test
    void testStartClosedContainer() {
        HelidonContainer closedContainer;
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize()) {
            closedContainer = HelidonContainer.instance();
            assertThat(closedContainer.start(), sameInstance(container));
        }

        assertThrows(IllegalStateException.class, closedContainer::start);

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize()) {
            assertThrows(IllegalStateException.class, closedContainer::start);
            assertThat(container.isRunning(), is(true));
            assertThat(container.select(TestBean.class).get().test(), is("test"));
            assertThat(CDI.current(), sameInstance(container));
        }
    }

    @Test
    void testCloseClosedContainer() {
        SeContainer closedContainer = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize();
        closedContainer.close();

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize()) {
            HelidonContainer helidonContainer = HelidonContainer.instance();

            assertThrows(IllegalStateException.class, closedContainer::close);

            assertThat(CDI.current(), sameInstance(container));
            assertThat(HelidonContainer.instance(), sameInstance(helidonContainer));
            assertThat(helidonContainer.start(), sameInstance(container));
            assertThat(container.isRunning(), is(true));
            assertThat(container.select(TestBean.class).get().test(), is("test"));
        }
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    private void assertContainerLifecycle() {
        SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize();
        try (container) {
            assertThat(container.isRunning(), is(true));
            assertThat(container.select(TestBean.class).get().test(), is("test"));
        }
        assertThat(container.isRunning(), is(false));
    }

    @ApplicationScoped
    public static class TestBean {
        public String test() {
            return "test";
        }
    }
}
