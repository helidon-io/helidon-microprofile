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

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.common.media.type.MediaType;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MeterRegistryFormatter;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.spi.MeterRegistryFormatterProvider;
import io.helidon.service.registry.Service;

@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT + 1000)
public final class TestMeterRegistryFormatterProvider implements MeterRegistryFormatterProvider {
    private static final AtomicBoolean RECORDING = new AtomicBoolean();
    private static final AtomicBoolean ALL_SELECTIONS_EMPTY = new AtomicBoolean();
    private static final AtomicInteger INVOCATION_COUNT = new AtomicInteger();

    static void startRecording() {
        INVOCATION_COUNT.set(0);
        ALL_SELECTIONS_EMPTY.set(true);
        RECORDING.set(true);
    }

    static void stopRecording() {
        RECORDING.set(false);
    }

    static int invocationCount() {
        return INVOCATION_COUNT.get();
    }

    static boolean allSelectionsEmpty() {
        return ALL_SELECTIONS_EMPTY.get();
    }

    @Override
    public Optional<MeterRegistryFormatter> formatter(MediaType mediaType,
                                                      MetricsConfig metricsConfig,
                                                      MeterRegistry meterRegistry,
                                                      Map<String, Collection<String>> tagSelections,
                                                      Iterable<String> nameSelection) {
        if (RECORDING.get()) {
            INVOCATION_COUNT.incrementAndGet();
            if (!tagSelections.isEmpty() || nameSelection.iterator().hasNext()) {
                ALL_SELECTIONS_EMPTY.set(false);
            }
        }
        return Optional.empty();
    }

    @Override
    @Deprecated(forRemoval = true, since = "27.0.0")
    public Optional<MeterRegistryFormatter> formatter(MediaType mediaType,
                                                      MetricsConfig metricsConfig,
                                                      MeterRegistry meterRegistry,
                                                      Optional<String> scopeTagName,
                                                      Iterable<String> scopeSelection,
                                                      Iterable<String> nameSelection) {
        return Optional.empty();
    }
}
