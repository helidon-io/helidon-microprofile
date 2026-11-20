/*
 * Copyright (c) 2024, 2026 Oracle and/or its affiliates.
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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;

public final class SdkTestExporters {
    static final List<LogRecordData> LOGS = new CopyOnWriteArrayList<>();
    static final List<MetricData> METRICS = new CopyOnWriteArrayList<>();

    public static class Logs implements ConfigurableLogRecordExporterProvider {
        @Override
        public String getName() {
            return "sdk-test";
        }

        @Override
        public LogRecordExporter createExporter(ConfigProperties config) {
            return new LogRecordExporter() {
                @Override
                public CompletableResultCode export(Collection<LogRecordData> records) {
                    LOGS.addAll(records);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }
    }

    public static class Metrics implements ConfigurableMetricExporterProvider {
        @Override
        public String getName() {
            return "sdk-test";
        }

        @Override
        public MetricExporter createExporter(ConfigProperties config) {
            return new MetricExporter() {
                @Override
                public AggregationTemporality getAggregationTemporality(InstrumentType type) {
                    return AggregationTemporality.CUMULATIVE;
                }

                @Override
                public CompletableResultCode export(Collection<MetricData> records) {
                    METRICS.addAll(records);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }
    }
}
