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

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import io.helidon.metrics.api.MeterRegistry;
import io.helidon.service.registry.Services;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;

class MetricFactory {

    private final MeterRegistry meterRegistry;
    private final io.helidon.metrics.api.MetricsFactory metricsFactory;

    MetricFactory(MeterRegistry meterRegistry) {
        this(meterRegistry, meterRegistry.metricsFactory());
    }

    private MetricFactory(MeterRegistry meterRegistry,
                          io.helidon.metrics.api.MetricsFactory metricsFactory) {
        this.meterRegistry = meterRegistry;
        this.metricsFactory = metricsFactory;
    }

    static MetricFactory create() {
        return new MetricFactory(Services.get(MeterRegistry.class));
    }

    static MetricFactory create(MeterRegistry meterRegistry) {
        return new MetricFactory(meterRegistry);
    }

    Counter counter(String scope, Metadata metadata, Tag... tags) {
        return HelidonCounter.create(meterRegistry, metricsFactory, scope, metadata, tags);
    }

    Timer timer(String scope, Metadata metadata, Tag... tags) {
        return HelidonTimer.create(meterRegistry, metricsFactory, scope, metadata, tags);
    }

    Histogram summary(String scope, Metadata metadata, Tag... tags) {
        return HelidonHistogram.create(meterRegistry, metricsFactory, scope, metadata, tags);
    }

    <N extends Number> Gauge<N> gauge(String scope, Metadata metadata, Supplier<N> supplier, Tag... tags) {
        return HelidonGauge.create(meterRegistry, scope, metadata, supplier, tags);
    }

    <T> Gauge<Double> gauge(String scope, Metadata metadata, T stateObject, ToDoubleFunction<T> fn, Tag... tags) {
        return HelidonGauge.create(meterRegistry, scope, metadata, stateObject, fn, tags);
    }
}
