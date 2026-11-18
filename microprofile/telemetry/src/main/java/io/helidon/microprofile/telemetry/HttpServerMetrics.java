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

import java.util.function.Consumer;

import io.opentelemetry.api.metrics.DoubleHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Records HTTP duration using final response facts supplied by the shared request observation.
 * The coordinator supplies the measurement start and owns request admission and completion hooks.
 */
@Singleton
final class HttpServerMetrics {
    private static final System.Logger LOGGER = System.getLogger(HttpServerMetrics.class.getName());
    private final DoubleHistogram duration;

    @Inject
    HttpServerMetrics(TelemetrySdk sdk) {
        this.duration = sdk.telemetry().getMeter("HELIDON_MICROPROFILE_TELEMETRY")
                .histogramBuilder(HttpSemantics.SERVER_DURATION_NAME)
                .setDescription(HttpSemantics.SERVER_DURATION_DESCRIPTION)
                .setUnit(HttpSemantics.SERVER_DURATION_UNIT)
                .setExplicitBucketBoundariesAdvice(HttpSemantics.SERVER_DURATION_BOUNDARIES)
                .build();
    }

    Consumer<HttpServerObservation> measurement(long started, String method, String scheme) {
        return observed -> {
            if (!duration.isEnabled()) {
                return;
            }
            try {
                var attributes = HttpSemantics.serverDurationAttributes(method, scheme, observed.status(),
                                                                         observed.route(), observed.failure());
                duration.record((System.nanoTime() - started) / 1_000_000_000.0, attributes.build());
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to record HTTP request duration", e);
            }
        };
    }
}
