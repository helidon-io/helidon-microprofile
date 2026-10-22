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

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import io.helidon.logging.common.LogConfig;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.config.WeldConfiguration;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.jboss.weld.config.ConfigurationKey.CONCURRENT_DEPLOYMENT;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit test for {@link HelidonContainerInitializer}.
 */
@Deprecated(since = "28.0.0")
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
        assertThat(seContainerInitializer, instanceOf(Weld.class));
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
        SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class);

        assertThat("preparing an initializer must not start the service registry",
                   GlobalServiceRegistry.configured(),
                   is(false));
    }

    @Test
    void testAdapterReadsConfigurationAtInitialization() {
        SeContainerInitializer initializer = new HelidonContainerInitializer()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .addProperty(CONCURRENT_DEPLOYMENT.get(), true);
        Config config = configResolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("cdi." + CONCURRENT_DEPLOYMENT.get(), "false")))
                .build();
        configResolver.registerConfig(config, cl);

        try (SeContainer container = initializer.initialize()) {
            WeldConfiguration configuration = BeanManagerProxy.unwrap(container.getBeanManager())
                    .getServices().get(WeldConfiguration.class);
            assertThat(configuration.getBooleanProperty(CONCURRENT_DEPLOYMENT), is(false));
        }
    }

    @Test
    void testAdapterClosesOwnedRegistryAfterStartupFailure() {
        ServiceRegistryExtension registryExtension = new ServiceRegistryExtension();
        StartupFailureExtension failureExtension = new StartupFailureExtension();
        SeContainerInitializer initializer = new HelidonContainerInitializer()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .addExtensions(registryExtension, failureExtension);
        assertThat(assertThrows(IllegalStateException.class, initializer::initialize),
                   sameInstance(failureExtension.failure));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @Test
    void testAdapterUsesSuppliedClassLoaderForDiscovery() {
        try (SeContainer container = new HelidonContainerInitializer().initialize()) {
            assertThat(container.select(TestBean2.class).isResolvable(), is(true));
        }
        ClassLoader loader = new ClassLoader(cl) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return "META-INF/services/jakarta.enterprise.inject.spi.Extension".equals(name)
                        ? Collections.emptyEnumeration()
                        : super.getResources(name);
            }
        };
        try (SeContainer container = new HelidonContainerInitializer()
                .setClassLoader(loader)
                .addBeanClasses(TestBean.class)
                .initialize()) {
            assertThat(container.select(TestBean.class).get().test(), is("test"));
            assertThat("the supplied loader hides TestExtension, which registers TestBean2",
                       container.select(TestBean2.class).isUnsatisfied(),
                       is(true));
        }
    }

    @SuppressWarnings("unchecked") // generic array creation via addExtensions
    @Test
    void testContainerLookupDoesNotInitialize() {
        HelidonContainer.instance().shutdown();
        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));

        SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(ServiceRegistryExtension.class)
                .addBeanClasses(TestBean.class);

        HelidonContainer.instance().shutdown();
        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));

        try (SeContainer container = initializer.initialize()) {
            assertThat(HelidonContainer.instance().start(), sameInstance(container));
            assertThat(CDI.current(), sameInstance(container));
            assertThat(GlobalServiceRegistry.configured(), is(true));
        }

        HelidonContainer.instance().shutdown();
        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @SuppressWarnings("unchecked") // generic array creation via addExtensions
    @Test
    void testPrepareInitializerWhileContainerIsRunning() {
        SeContainerInitializer nextInitializer;
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize()) {
            nextInitializer = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addExtensions(ServiceRegistryExtension.class)
                    .addBeanClasses(TestBean.class);

            assertThat("preparing another initializer must not replace the running container",
                       CDI.current(),
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
    void testIndependentContainers() {
        try (SeContainer first = new HelidonContainerInitializer()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .initialize();
             SeContainer second = new HelidonContainerInitializer()
                .disableDiscovery()
                .addBeanClasses(TestBean2.class)
                .initialize()) {
            assertThat(first.select(TestBean.class).get().test(), is("test"));
            assertThat(second.select(TestBean2.class).get().message(), is("Hello"));
            assertThat(HelidonContainer.instance().start(), sameInstance(CDI.current()));
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
            assertThrows(IllegalStateException.class, closedContainer::close);

            assertThat(CDI.current(), sameInstance(container));
            assertThat(HelidonContainer.instance().start(), sameInstance(container));
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
            assertThrows(IllegalArgumentException.class,
                         () -> container.getBeanManager().getExtension(ServiceRegistryExtension.class));
            assertThat("discovery-disabled startup must not install a registry",
                       GlobalServiceRegistry.configured(),
                       is(false));
        }
        assertThat(container.isRunning(), is(false));
    }

    static class StartupFailureExtension implements Extension {
        private final IllegalStateException failure = new IllegalStateException("Deliberate startup failure");

        void startup(@Observes Startup event) {
            assertThat(GlobalServiceRegistry.configured(), is(true));
            throw this.failure;
        }
    }

    @ApplicationScoped
    public static class TestBean {
        public String test() {
            return "test";
        }
    }

}
