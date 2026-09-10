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

import java.util.List;
import java.util.function.UnaryOperator;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.LazyValue;
import io.helidon.common.Weighted;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.observe.metrics.MetricsObserver;
import io.helidon.webserver.observe.metrics.MetricsObserverConfig;
import io.helidon.webserver.observe.spi.Observer;
import io.helidon.webserver.spi.ServerFeature;

final class MpMetricsObserver implements Observer, RuntimeType.Api<MetricsObserverConfig> {
    private final MetricsObserver delegate;
    private final MetricsObserverConfig config;
    private final LazyValue<MpMetricsFeature> metricsFeature;

    private MpMetricsObserver(MetricsObserverConfig config) {
        this.config = config;
        this.delegate = MetricsObserver.create(config);
        this.metricsFeature = LazyValue.create(() -> new MpMetricsFeature(config));
    }

    static MpMetricsObserver create(MetricsObserverConfig config) {
        return new MpMetricsObserver(config);
    }

    @Override
    public MetricsObserverConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return "metrics";
    }

    @Override
    public void register(ServerFeature.ServerFeatureContext featureContext,
                         List<HttpRouting.Builder> observeEndpointRouting,
                         UnaryOperator<String> endpointFunction) {
        delegate.register(featureContext, observeEndpointRouting, endpointFunction);

        String endpoint = endpointFunction.apply(config.endpoint());
        for (HttpRouting.Builder routing : observeEndpointRouting) {
            routing.addFeature(new MpMetricsHttpFeature(endpoint, metricsFeature.get()));
        }
    }

    void configureVendorMetrics(HttpRouting.Builder rules) {
        delegate.configureVendorMetrics(rules);
    }

    private record MpMetricsHttpFeature(String endpoint, MpMetricsFeature metricsFeature)
            implements HttpFeature, Weighted {

        @Override
        public void setup(HttpRouting.Builder routing) {
            metricsFeature.register(routing, endpoint);
        }

        @Override
        public double weight() {
            return Weighted.DEFAULT_WEIGHT + 1;
        }
    }
}
