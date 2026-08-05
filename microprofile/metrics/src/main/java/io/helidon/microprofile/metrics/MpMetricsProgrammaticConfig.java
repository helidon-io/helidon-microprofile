/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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
import java.util.Optional;

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.spi.MetricsProgrammaticConfig;
import io.helidon.service.registry.Service;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * MP implementation of metrics programmatic settings.
 */
@Service.Singleton
public class MpMetricsProgrammaticConfig implements MetricsProgrammaticConfig {

    /**
     * Required public constructor for {@link java.util.ServiceLoader}.
     */
    @Api.Internal
    public MpMetricsProgrammaticConfig() {
    }

    @Override
    public Optional<String> scopeTagName() {
        return Optional.of("mp_scope");
    }

    @Override
    public Optional<String> appTagName() {
        return Optional.of("mp_app");
    }

    @Override
    public Optional<String> scopeDefaultValue() {
        return Optional.of(Meter.Scope.DEFAULT);
    }

    @Override
    public MetricsConfig.Builder apply(MetricsConfig.Builder builder) {
        MetricsProgrammaticConfig.super.apply(builder);

        org.eclipse.microprofile.config.Config mpConfig = ConfigProvider.getConfig();
        mpConfig.getOptionalValue("mp.metrics.tags", String.class)
                .ifPresent(tags -> {
                    Config tagsConfig = Config.just(ConfigSources.create(Map.of("tags", tags)));
                    builder.tags(MetricsConfig.create(tagsConfig).tags());
                });
        mpConfig.getOptionalValue("mp.metrics.appName", String.class)
                .ifPresent(builder::appName);

        return builder;
    }
}
