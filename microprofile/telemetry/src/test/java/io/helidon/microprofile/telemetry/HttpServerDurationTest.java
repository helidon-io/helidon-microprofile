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

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.helidon.microprofile.telemetry.spi.HelidonTelemetryContainerFilterHelper;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.enterprise.inject.Instance;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ContainerRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class HttpServerDurationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportedDurationRemainsEqualWhileMethodAndServerActivationsDrain(boolean failed) {
        SdkTestExporters.METRICS.clear();
        var owner = TelemetryTestSupport.owner(Map.of("otel.metrics.exporter", "sdk-test"));
        owner.initialize();
        var exporter = new TestSpanExporter();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build()) {
            var tracer = HelidonOpenTelemetry.create(sdk, sdk.getTracer("duration-test"), Map.of());
            var observation = new HttpServerObservation(owner.acquire(),
                    new HttpServerMetrics(owner).measurement("GET", "http"));
            var request = new ContainerRequest(URI.create("http://example.test/"), URI.create("http://example.test/route"),
                                               "GET", null, new MapPropertiesDelegate());
            request.setProperty(HttpServerObservation.PROPERTY, observation);
            var server = new HttpServerTracing(tracer, helpers(), owner).start(request, "/route", observation.startTime());
            var lifecycle = new HttpServerSpan(server, owner.acquire(), observation);
            observation.endSpan(() -> lifecycle.complete(observation.failure()));
            lifecycle.returned(null);
            try (var _ = lifecycle.activateResponse()) {
                var method = observation.startMethodSpan(tracer.spanBuilder("method").start(), owner.acquire());
                try (var _ = method.activate()) {
                    method.returned(true, null);
                    observation.complete(failed ? new IllegalStateException("Synthetic response failure") : null);
                    assertThat("The method activation still owns the server span", server.unwrap(Span.class).isRecording(), is(true));
                    assertThat("The response is measured before activations drain", point(owner).getCount(), is(1L));
                }
                assertThat("The remaining server activation still owns the span", server.unwrap(Span.class).isRecording(), is(true));
            }
            var data = exporter.spanData(2).stream().filter(span -> span.getName().equals("GET /route")).findFirst().orElseThrow();
            assertThat(data.getStatus().getStatusCode(), is(failed ? StatusCode.ERROR : StatusCode.UNSET));
            var measured = point(owner);
            assertThat("Histogram and span describe exactly the same interval", measured.getSum(),
                       is((data.getEndEpochNanos() - data.getStartEpochNanos()) / 1_000_000_000.0));
            observation.complete(null);
            assertThat("Repeated completion records no second measurement", point(owner).getCount(), is(1L));
        } finally {
            owner.close();
        }
    }

    private static HistogramPointData point(TelemetrySdk owner) {
        ((OpenTelemetrySdk) owner.telemetry()).getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
        return SdkTestExporters.METRICS.stream().filter(metric -> metric.getName().equals("http.server.request.duration"))
                .reduce((_, latest) -> latest).orElseThrow().getHistogramData().getPoints().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static Instance<HelidonTelemetryContainerFilterHelper> helpers() {
        return (Instance<HelidonTelemetryContainerFilterHelper>) Proxy.newProxyInstance(HttpServerDurationTest.class.getClassLoader(),
                new Class<?>[] {Instance.class}, (_, method, _) -> {
                    if (method.getName().equals("stream")) {
                        return Stream.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
