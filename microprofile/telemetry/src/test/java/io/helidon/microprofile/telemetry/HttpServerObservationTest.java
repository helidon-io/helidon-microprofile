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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpServerObservationTest {
    private TelemetrySdk owner;
    private OpenTelemetrySdk sdk;
    private Tracer tracer;
    private HttpServerObservation observation;

    @BeforeEach
    void initialize() {
        owner = TelemetryTestSupport.owner(Map.of());
        owner.initialize();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new TestSpanExporter())).build()).build();
        tracer = HelidonOpenTelemetry.create(sdk, sdk.getTracer("observation-test"), Map.of());
    }

    @AfterEach
    void close() {
        if (observation != null) {
            observation.complete(null);
        }
        owner.close();
        sdk.close();
    }

    @Test
    void concurrentCompletionMeasuresAndEndsOnceInOrder() {
        var events = new CopyOnWriteArrayList<String>();
        observation = new HttpServerObservation(owner.acquire(), _ -> events.add("measure"));
        observation.endSpan(() -> events.add("server-end"));
        assertThat(HttpServerObservation.PROPERTY, is("io.helidon.microprofile.telemetry.HttpMetricsFilter"));
        assertThat(HttpServerObservation.from(observation), sameInstance(observation));
        IntStream.range(0, 100).parallel().forEach(_ -> observation.complete(null));
        assertThat(events, is(List.of("measure", "server-end")));
        owner.close();
    }

    @Test
    void lateEndRegistrationRunsExactlyOnce() {
        var ended = new AtomicInteger();
        observation = new HttpServerObservation(owner.acquire(), _ -> { });
        observation.complete(null);
        observation.endSpan(ended::incrementAndGet);
        observation.complete(null);
        assertThat(ended.get(), is(1));
    }

    @Test
    void registrationRacingWithCompletionDoesNotLoseEndAction() {
        for (int i = 0; i < 100; i++) {
            var ended = new AtomicInteger();
            var current = new HttpServerObservation(owner.acquire(), _ -> { });
            IntStream.range(0, 2).parallel().forEach(index -> {
                if (index == 0) {
                    current.complete(null);
                } else {
                    current.endSpan(ended::incrementAndGet);
                }
            });
            assertThat(ended.get(), is(1));
        }
    }

    @Test
    void measuresBeforeMethodSpanDrainsAndEndsServerSpanAfterward() {
        var events = new CopyOnWriteArrayList<String>();
        observation = new HttpServerObservation(owner.acquire(), _ -> events.add("measure"));
        observation.endSpan(() -> events.add("server-end"));
        var span = tracer.spanBuilder("method").start();
        var completion = observation.startMethodSpan(span, owner.acquire());
        try (var _ = completion.activate()) {
            completion.returned(true, null);
            observation.complete(null);
            assertThat(events, is(List.of("measure")));
            assertThat(span.unwrap(io.opentelemetry.api.trace.Span.class).isRecording(), is(true));
        }
        assertThat(events, is(List.of("measure", "server-end")));
        assertThat(span.unwrap(io.opentelemetry.api.trace.Span.class).isRecording(), is(false));
        observation.complete(null);
        assertThat(events.size(), is(2));
        owner.close();
    }

    @Test
    void lateRegistrationWhileMethodDrainsRunsOnlyAfterItsScopeCloses() {
        var ended = new AtomicInteger();
        observation = new HttpServerObservation(owner.acquire(), _ -> { });
        var completion = observation.startMethodSpan(tracer.spanBuilder("late-end").start(), owner.acquire());
        try (var _ = completion.activate()) {
            completion.returned(true, null);
            observation.complete(null);
            observation.endSpan(ended::incrementAndGet);
            assertThat(ended.get(), is(0));
        }
        assertThat(ended.get(), is(1));
    }

    @Test
    void measurementFailureStillEndsAndReleasesOwnership() {
        var failure = new IllegalStateException("synthetic measurement failure");
        var ended = new AtomicInteger();
        observation = new HttpServerObservation(owner.acquire(), _ -> { throw failure; });
        observation.endSpan(ended::incrementAndGet);
        assertThat(assertThrows(IllegalStateException.class, () -> observation.complete(null)), sameInstance(failure));
        observation.complete(null);
        assertThat(ended.get(), is(1));
        owner.close();
    }

    @Test
    void endCallbackFailureStillReleasesOwnership() {
        var failure = new IllegalStateException("synthetic callback failure");
        var measured = new AtomicInteger();
        observation = new HttpServerObservation(owner.acquire(), _ -> measured.incrementAndGet());
        observation.endSpan(() -> { throw failure; });
        assertThat(assertThrows(IllegalStateException.class, () -> observation.complete(null)), sameInstance(failure));
        observation.complete(null);
        assertThat(measured.get(), is(1));
        owner.close();
    }
}
