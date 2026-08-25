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
package io.helidon.microprofile.metrics;

import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.config.core.MpServiceRegistryBootstrap;
import io.helidon.microprofile.server.JaxRsCdiExtension;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryFactoryLifecycleTest {

    @Test
    void staticAccessAfterShutdownDoesNotRestoreGlobalServiceRegistry() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Config originalConfig = resolver.getConfig(classLoader);
        Config config = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("server.port", "0",
                                                           "mp.initializer.allow", "true")))
                .build();
        resolver.registerConfig(config, classLoader);

        try {
            try (SeContainer _ = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addExtensions(MetricsCdiExtension.class, ServerCdiExtension.class, JaxRsCdiExtension.class)
                    .initialize()) {
                assertThat(GlobalServiceRegistry.configured(), is(true));
                assertThat(RegistryFactory.getInstance(), notNullValue());
            }

            assertThat(GlobalServiceRegistry.configured(), is(false));
            IllegalStateException e = assertThrows(IllegalStateException.class, RegistryFactory::getInstance);
            assertThat(e.getMessage(), containsString("only while a Helidon MP container is running"));
            assertThat("static access must not restore the global service registry",
                       GlobalServiceRegistry.configured(),
                       is(false));
        } finally {
            resolver.registerConfig(originalConfig, classLoader);
            ServiceRegistryManager manager = MpServiceRegistryBootstrap.start();
            MpServiceRegistryBootstrap.shutdown(manager);
        }
    }
}
