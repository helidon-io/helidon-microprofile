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

import io.helidon.config.ConfigSources;
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
        }
    }
}
