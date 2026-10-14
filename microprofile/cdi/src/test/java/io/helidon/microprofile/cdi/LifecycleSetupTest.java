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

import java.io.ObjectInputFilter;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.helidon.common.Version;
import io.helidon.common.features.HelidonFeatures;
import io.helidon.common.features.api.HelidonFlavor;
import io.helidon.microprofile.config.core.MpConfigSources;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;

// The CDI module forks a fresh JVM for each test class, isolating the process-wide setup and feature report.
class LifecycleSetupTest {
    @Test
    void testSetupBeforeRegistryAcquisitionAndReportDuringStartup() throws InterruptedException {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Config original = resolver.getConfig(loader);
        Config details = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("features.print-details", "true")))
                .build();
        Logger weldLogger = Logger.getLogger("org.jboss");
        Level originalLevel = weldLogger.getLevel();
        weldLogger.setLevel(Level.FINE);
        Logger featureLogger = Logger.getLogger(HelidonFeatures.class.getName());
        FeatureHandler handler = new FeatureHandler();
        Extension verification = new Extension() {
            void beforeDiscovery(@Observes @Priority(PLATFORM_BEFORE) BeforeBeanDiscovery event) {
                // The same priority is used for service registry acquisition.
                assertThat(ObjectInputFilter.Config.getSerialFilter(), notNullValue());
                assertThat(weldLogger.getLevel(), is(Level.INFO)); // From the test's logging.properties.
                featureLogger.addHandler(handler);
            }

            void initialized(@Observes @Initialized(ApplicationScoped.class) Object event) {
                // Emulate the web server's attempt to select and report the SE flavor.
                HelidonFeatures.flavor(HelidonFlavor.SE);
                HelidonFeatures.print(HelidonFlavor.SE, Version.VERSION, false);
            }

            void startup(@Observes Startup event) {
                assertThat(handler.messages, empty());
                resolver.registerConfig(details, loader);
            }
        };

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TestBean.class)
                .addExtensions(new CdiExtension(), verification)
                .initialize()) {
            assertThat(container.isRunning(), is(true));
            // Feature reporting is asynchronous; wait for its observable output.
            assertThat(handler.messages.poll(10, SECONDS), startsWith("Helidon MP " + Version.VERSION));
            assertThat(handler.messages.poll(10, SECONDS), is("Detailed feature tree:"));
        } finally {
            featureLogger.removeHandler(handler);
            weldLogger.setLevel(originalLevel);
            resolver.registerConfig(original, loader);
        }
    }

    @Dependent
    static class TestBean {
    }

    private static final class FeatureHandler extends Handler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (message.startsWith("Helidon ") || message.equals("Detailed feature tree:")) {
                messages.add(message);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
