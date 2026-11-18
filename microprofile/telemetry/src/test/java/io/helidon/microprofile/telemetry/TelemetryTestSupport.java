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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.helidon.microprofile.config.core.MpConfig;

import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;

final class TelemetryTestSupport {
    private TelemetryTestSupport() {
    }

    static TelemetrySdk owner(Map<String, String> properties) {
        Map<String, String> settings = new HashMap<>(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "none",
                                                          "otel.metrics.exporter", "none", "otel.logs.exporter", "none"));
        settings.putAll(properties);
        ConfigSource source = new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return settings;
            }

            @Override
            public Set<String> getPropertyNames() {
                return settings.keySet();
            }

            @Override
            public String getValue(String name) {
                return settings.get(name);
            }

            @Override
            public String getName() {
                return "telemetry-lifecycle-test";
            }
        };
        var config = ConfigProviderResolver.instance().getBuilder().withSources(source).build();
        return new TelemetrySdk(MpConfig.toHelidonConfig(config), config);
    }
}
