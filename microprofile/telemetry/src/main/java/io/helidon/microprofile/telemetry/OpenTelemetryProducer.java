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
package io.helidon.microprofile.telemetry;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * A producer of {@link io.opentelemetry.api.OpenTelemetry}, {@link io.opentelemetry.api.trace.Tracer},
 * {@link io.opentelemetry.api.trace.Span}, {@link io.opentelemetry.api.metrics.Meter},
 * and {@link io.opentelemetry.api.baggage.Baggage}
 * required for injection into {@code CDI} beans.
 */
@ApplicationScoped
class OpenTelemetryProducer {
    private final TelemetrySdk sdk;
    private final Tracer tracer;
    private final io.helidon.tracing.Tracer helidonTracer;
    private final Tracer callbackEnabledTracer;
    private final Meter meter;

    @Inject
    OpenTelemetryProducer(TelemetrySdk sdk, org.eclipse.microprofile.config.Config config) {
        this.sdk = sdk;
        String name = config.getOptionalValue("mp.telemetry.tracing.name", String.class)
                .or(() -> config.getOptionalValue("otel.exporter.name", String.class))
                .orElse("HELIDON_MICROPROFILE_TELEMETRY");
        this.tracer = sdk.telemetry().getTracer(name);
        this.helidonTracer = HelidonOpenTelemetry.create(sdk.telemetry(), tracer, Map.of());
        this.callbackEnabledTracer = HelidonOpenTelemetry.callbackEnabledFrom(helidonTracer);
        this.meter = sdk.telemetry().getMeter("HELIDON_MICROPROFILE_TELEMETRY");
    }

    @Produces
    Meter meter() {
        return meter;
    }

    /**
     * Get OpenTelemetry.
     *
     * @return OpenTelemetry
     */
    @ApplicationScoped
    @Produces
    OpenTelemetry openTelemetry() {
        return sdk.telemetry();
    }

    /**
     * Provides an instance of the current OpenTelemetry Tracer.
     *
     * @return Tracer.
     */
    @Produces
    Tracer tracer() {
        return tracer;
    }

    /**
     * Produces an instance of the current OpenTelemetry Tracer which provides span builders and spans which support
     * span listener callbacks.
     *
     * @return Tracer capable of life cycle callbacks.
     */
    @Produces
    @CallbackEnabled
    Tracer callbackEnabledTracer() {
        return callbackEnabledTracer;
    }

    /**
     * Provides an instance of the current Helidon API Tracer.
     *
     * @return Tracer.
     */
    @Produces
    io.helidon.tracing.Tracer helidonTracer() {
        return helidonTracer;
    }

    /**
     * Provides an instance of the current Span.
     *
     * @return a {@link io.opentelemetry.api.trace.Span}.
     */
    @Produces
    Span span() {
        return new Span() {
            @Override
            public <T> Span setAttribute(AttributeKey<T> key, T value) {
                return Span.current().setAttribute(key, value);
            }

            @Override
            public Span addEvent(String name, Attributes attributes) {
                return Span.current().addEvent(name, attributes);
            }

            @Override
            public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
                return Span.current().addEvent(name, attributes, timestamp, unit);
            }

            @Override
            public Span setStatus(StatusCode statusCode, String description) {
                return Span.current().setStatus(statusCode, description);
            }

            @Override
            public Span recordException(Throwable exception, Attributes additionalAttributes) {
                return Span.current().recordException(exception, additionalAttributes);
            }

            @Override
            public Span updateName(String name) {
                return Span.current().updateName(name);
            }

            @Override
            public void end() {
                Span.current().end();
            }

            @Override
            public void end(long timestamp, TimeUnit unit) {
                Span.current().end(timestamp, unit);
            }

            @Override
            public SpanContext getSpanContext() {
                return Span.current().getSpanContext();
            }

            @Override
            public boolean isRecording() {
                return Span.current().isRecording();
            }
        };
    }

    /**
     * Produces an OpenTelemetry {@link io.opentelemetry.api.trace.Span} that invokes life cycle callbacks.
     *
     * @return callback-capable {@code Span}
     */
    @Produces
    @CallbackEnabled
    Span callbackEnabledSpan() {
        return (Span) io.helidon.tracing.Span.current()
                .map(HelidonOpenTelemetry::callbackEnabledFrom)
                .orElseGet(() -> HelidonOpenTelemetry.callbackEnabledFrom(span()));
    }

    /**
     * Provides an instance of the current Baggage.
     *
     * @return a {@link io.opentelemetry.api.baggage.Baggage}.
     */
    @Produces
    @ApplicationScoped
    Baggage baggage() {
        return new Baggage() {
            @Override
            public int size() {
                return Baggage.current().size();
            }

            @Override
            public void forEach(BiConsumer<? super String, ? super BaggageEntry> consumer) {
                Baggage.current().forEach(consumer);
            }

            @Override
            public Map<String, BaggageEntry> asMap() {
                return Baggage.current().asMap();
            }

            @Override
            public String getEntryValue(String entryKey) {
                return Baggage.current().getEntryValue(entryKey);
            }

            @Override
            public BaggageBuilder toBuilder() {
                return Baggage.current().toBuilder();
            }
        };
    }

}
