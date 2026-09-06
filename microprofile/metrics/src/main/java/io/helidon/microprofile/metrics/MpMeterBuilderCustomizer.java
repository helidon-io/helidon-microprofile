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
import java.util.Objects;

import io.helidon.metrics.api.Meter;
import io.helidon.metrics.spi.MeterBuilderCustomizer;
import io.helidon.service.registry.Service;

import org.eclipse.microprofile.metrics.MetricRegistry;

@Service.Singleton
final class MpMeterBuilderCustomizer implements MeterBuilderCustomizer {
    private static final Map<String, String> ORIGIN_SCOPES = Map.ofEntries(
            Map.entry("io.helidon.metrics.systemmeters.SystemMetersProvider", MetricRegistry.BASE_SCOPE),
            Map.entry("io.helidon.metrics.systemmeters.VThreadSystemMetersProvider", MetricRegistry.BASE_SCOPE),
            Map.entry("io.helidon.common.concurrency.limits.AimdMetrics", MetricRegistry.VENDOR_SCOPE),
            Map.entry("io.helidon.common.concurrency.limits.SemaphoreMetrics", MetricRegistry.VENDOR_SCOPE),
            Map.entry("io.helidon.dbclient.metrics.hikari.DropwizardMetricsListener", MetricRegistry.VENDOR_SCOPE),
            Map.entry("io.helidon.faulttolerance.FaultTolerance", MetricRegistry.VENDOR_SCOPE),
            Map.entry("io.helidon.webclient.grpc.GrpcClient", MetricRegistry.VENDOR_SCOPE),
            Map.entry("io.helidon.webserver.grpc.GrpcRouting", MetricRegistry.VENDOR_SCOPE),
            Map.entry("io.helidon.webserver.observe.metrics.KeyPerformanceIndicatorMetricsImpls",
                      MetricRegistry.VENDOR_SCOPE));

    @Override
    public void customize(Meter.Builder<?, ?> builder) {
        Objects.requireNonNull(builder);
        if (builder.tags().containsKey(MpScope.TAG_NAME)) {
            throw new IllegalArgumentException("Illegal use of reserved tag name: " + MpScope.TAG_NAME);
        }
        String scope = MpScope.registrationScope()
                .orElseGet(() -> builder.origin()
                        .map(ORIGIN_SCOPES::get)
                        .orElse(MetricRegistry.APPLICATION_SCOPE));
        builder.addTag(MpScope.tag(scope));
    }
}
