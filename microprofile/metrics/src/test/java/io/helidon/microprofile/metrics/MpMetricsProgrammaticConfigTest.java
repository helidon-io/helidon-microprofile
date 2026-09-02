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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.microprofile.config.core.MpConfigSources;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class MpMetricsProgrammaticConfigTest {

    @Test
    void appliesMpTagsAndAppNameToMetricsConfig() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        org.eclipse.microprofile.config.Config originalConfig = ConfigProvider.getConfig();
        org.eclipse.microprofile.config.Config mpConfig = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of(
                        "mp.metrics.tags", "region=west,env=prod",
                        "mp.metrics.appName", "orders")))
                .build();
        Config rootConfig = Config.just(ConfigSources.create(Map.of(
                "metrics.tags", "ignored=tag",
                "metrics.app-name", "ignored-app")));

        resolver.releaseConfig(originalConfig);
        try {
            resolver.registerConfig(mpConfig, classLoader);
            MetricsConfig metricsConfig = new MpMetricsProgrammaticConfig()
                    .apply(MetricsConfig.create(rootConfig.get("metrics")));
            Map<String, String> tags = new HashMap<>();
            metricsConfig.tags().forEach(tag -> tags.put(tag.key(), tag.value()));

            assertThat("MP global tags override the SE metrics tags",
                       tags,
                       allOf(hasEntry("region", "west"),
                             hasEntry("env", "prod"),
                             not(hasEntry("ignored", "tag"))));
            assertThat("MP app name", metricsConfig.appName(), is(Optional.of("orders")));
            assertThat("MP application tag name", metricsConfig.appTagName(), is(Optional.of("mp_app")));
            assertThat("MP scope tag name", metricsConfig.scoping().tagName(), is(Optional.of("mp_scope")));
            assertThat("MP default scope", metricsConfig.scoping().defaultValue(), is(Optional.of(Meter.Scope.DEFAULT)));
        } finally {
            resolver.releaseConfig(mpConfig);
            resolver.registerConfig(originalConfig, classLoader);
        }
    }
}
