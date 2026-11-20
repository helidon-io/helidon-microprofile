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
package io.helidon.microprofile.telemetry;

import io.helidon.microprofile.config.core.MpConfig;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

class TelemetryProcessorDiscoveryTest {
    @ParameterizedTest
    @ValueSource(classes = {HttpClientTracing.class, HttpServerMetrics.class})
    void extensionRegistersSingletonProcessorsWithoutJersey(Class<?> type) {
        try (var container = SeContainerInitializer.newInstance().disableDiscovery()
                .addExtensions(TelemetryCdiExtension.class).addBeanClasses(Configuration.class).initialize()) {
            var processors = container.select(type);
            assertThat(processors.isResolvable(), is(true));
            assertThat(processors.get(), sameInstance(processors.get()));
            var manager = container.getBeanManager();
            assertThat(manager.resolve(manager.getBeans(type)).getScope(), is(Singleton.class));
            assertThat(container.select(HttpClientTelemetryFilter.class).isUnsatisfied(), is(true));
        }
    }

    @Dependent
    public static class Configuration {
        @Produces
        Config config() {
            return ConfigProviderResolver.instance().getBuilder().build();
        }

        @Produces
        io.helidon.config.Config helidonConfig(Config config) {
            return MpConfig.toHelidonConfig(config);
        }
    }
}
