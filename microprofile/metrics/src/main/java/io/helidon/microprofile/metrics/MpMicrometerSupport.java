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

import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsConfig;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;

final class MpMicrometerSupport {
    private MpMicrometerSupport() {
    }

    static void configure(MeterRegistry meterRegistry, MetricsConfig metricsConfig) {
        try {
            Class.forName("io.micrometer.core.instrument.MeterRegistry", false, MpMicrometerSupport.class.getClassLoader());
        } catch (ClassNotFoundException _) {
            return;
        }
        ScopeFilter.configure(meterRegistry, MpScope.defaultScope(metricsConfig));
    }

    // Load the Micrometer types only when that optional implementation is available.
    private static class ScopeFilter implements MeterFilter {
        private final Tag defaultScopeTag;

        private ScopeFilter(String defaultScope) {
            defaultScopeTag = Tag.of(MpScope.TAG_NAME, defaultScope);
        }

        static void configure(MeterRegistry meterRegistry, String defaultScope) {
            io.micrometer.core.instrument.MeterRegistry nativeRegistry;
            try {
                nativeRegistry = meterRegistry.unwrap(io.micrometer.core.instrument.MeterRegistry.class);
            } catch (ClassCastException _) {
                return;
            }
            nativeRegistry.config().meterFilter(new ScopeFilter(defaultScope));
        }

        @Override
        public Meter.Id map(Meter.Id id) {
            return id.getTag(MpScope.TAG_NAME) == null
                    ? id.withTag(defaultScopeTag)
                    : id;
        }
    }
}
