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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.scheduling.FixedRateInvocation;
import io.helidon.scheduling.Scheduling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

class SchedulingLifecycleTest {

    private ConfigProviderResolver resolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private Config config;
    private SeContainer container;
    private ScheduledBean bean;

    @BeforeEach
    void setUp() {
        resolver = ConfigProviderResolver.instance();
        classLoader = Thread.currentThread().getContextClassLoader();
        originalConfig = resolver.getConfig(classLoader).unwrap(Config.class);
        config = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of(
                        "lifecycle.interval", "PT0.1S",
                        "schedule.thread-name-prefix", "lifecycle-scheduled-",
                        ScheduledBean.class.getName() + ".run.schedule.delay-by", "PT0S")))
                .build();
        Extension configure = new Extension() {
            void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
                resolver.registerConfig(config, classLoader);
            }
        };

        // Scheduling must work without io.helidon.microprofile.cdi.CdiExtension; note it is not present in the
        // addExtensions() call.
        container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(new SchedulingCdiExtension(), configure)
                .addBeanClasses(ScheduledBean.class)
                .initialize();
        bean = container.select(ScheduledBean.class).get();
    }

    @AfterEach
    void tearDown() {
        try {
            if (container != null) {
                container.close();
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

    @Test
    void usesConfigurationFromBeanDiscovery() throws Exception {
        FixedRateInvocation invocation = bean.invocation().get(10, SECONDS);
        assertThat(invocation.interval(), is(Duration.ofMillis(100)));
        assertThat(invocation.delayBy(), is(Duration.ZERO));
        assertThat(bean.threadName().get(10, SECONDS), startsWith("lifecycle-scheduled-"));
    }

    @ApplicationScoped
    static class ScheduledBean {
        private final CompletableFuture<FixedRateInvocation> invocation = new CompletableFuture<>();
        private final CompletableFuture<String> threadName = new CompletableFuture<>();

        @Scheduling.FixedRate(value = "${lifecycle.interval}", delayBy = "PT1H")
        void run(FixedRateInvocation invocation) {
            threadName.complete(Thread.currentThread().getName());
            this.invocation.complete(invocation);
        }

        CompletableFuture<FixedRateInvocation> invocation() {
            return invocation;
        }

        CompletableFuture<String> threadName() {
            return threadName;
        }
    }
}
