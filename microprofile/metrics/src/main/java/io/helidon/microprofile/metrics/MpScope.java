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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.metrics.api.Tag;

import org.eclipse.microprofile.metrics.MetricRegistry;

final class MpScope {
    static final String TAG_NAME = "mp_scope";

    private MpScope() {
    }

    static Tag tag(String scope) {
        return new ScopeTag(TAG_NAME, Objects.requireNonNull(scope));
    }

    static String scope(Meter meter) {
        return meter.id().tagsMap().getOrDefault(TAG_NAME, MetricRegistry.APPLICATION_SCOPE);
    }

    static Iterable<Map.Entry<String, String>> withScopeTag(Iterable<Map.Entry<String, String>> tags, String scope) {
        var result = new ArrayList<Map.Entry<String, String>>();
        tags.forEach(tag -> {
            if (tag.getKey().equals(TAG_NAME)) {
                throw new IllegalArgumentException("Illegal use of reserved tag name: " + TAG_NAME);
            }
            result.add(tag);
        });
        result.add(new AbstractMap.SimpleImmutableEntry<>(TAG_NAME, Objects.requireNonNull(scope)));
        return result;
    }

    @SuppressWarnings({"removal", "unchecked"})
    static <B extends Meter.Builder<B, M>, M extends Meter> M getOrCreate(MeterRegistry meterRegistry,
                                                                          MetricsFactory metricsFactory,
                                                                          String scope,
                                                                          B builder) {
        return isMeterEnabled(metricsFactory.metricsConfig(), scope, builder.name())
                ? meterRegistry.getOrCreate(builder)
                : (M) metricsFactory.noOpMeter(builder);
    }

    @SuppressWarnings("removal")
    static boolean isMeterEnabled(MetricsConfig metricsConfig, String scope, String meterName) {
        var scopeConfig = metricsConfig.scoping().scopes().get(scope);
        return metricsConfig.enabled()
                && (scopeConfig == null
                            || scopeConfig.enabled()
                            && scopeConfig.include().map(pattern -> pattern.matcher(meterName).matches()).orElse(true)
                            && scopeConfig.exclude().map(pattern -> !pattern.matcher(meterName).matches()).orElse(true));
    }

    private record ScopeTag(String key, String value) implements Tag {
        @Override
        public <T> T unwrap(Class<? extends T> type) {
            return type.cast(this);
        }
    }
}
