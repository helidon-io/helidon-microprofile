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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.helidon.microprofile.telemetry.spi.HelidonTelemetryContainerFilterHelper;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import jakarta.enterprise.inject.Instance;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ContainerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class HttpServerParentTest {
    private final AtomicReference<SpanContext> parent = new AtomicReference<>();
    private TelemetrySdk owner;
    private OpenTelemetrySdk sdk;
    private HttpServerTracing tracing;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void initialize() {
        owner = TelemetryTestSupport.owner(Map.of());
        owner.initialize();
        Sampler sampler = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context context, String traceId, String name, SpanKind kind,
                                               Attributes attributes, List<LinkData> links) {
                parent.set(Span.fromContext(context).getSpanContext());
                if (name.equals("record-only")) {
                    return SamplingResult.recordOnly();
                }
                return Sampler.parentBased(Sampler.alwaysOn()).shouldSample(context, traceId, name, kind, attributes, links);
            }

            @Override
            public String getDescription() {
                return "parent-observer";
            }
        };
        sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().setSampler(sampler)
                .addSpanProcessor(SimpleSpanProcessor.create(new TestSpanExporter())).build()).build();
        var tracer = HelidonOpenTelemetry.create(sdk, sdk.getTracer("server-parent-test"), Map.of());
        var helpers = (Instance<HelidonTelemetryContainerFilterHelper>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instance.class}, (_, method, _) -> {
                    if (method.getName().equals("stream")) {
                        return Stream.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        tracing = new HttpServerTracing(tracer, helpers, owner);
    }

    @AfterEach
    void close() {
        sdk.close();
        owner.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsUnsampledIngressParentAndSamplingDecision(boolean incoming) {
        var ingress = SpanContext.create("11111111111111111111111111111111", "2222222222222222",
                                         TraceFlags.getDefault(), TraceState.getDefault());
        var request = request();
        if (incoming) {
            request.getHeaders().putSingle("traceparent", "00-" + ingress.getTraceId() + "-3333333333333333-00");
        }
        try (var _ = Context.root().with(Span.wrap(ingress)).makeCurrent()) {
            var server = tracing.start(request, "/route", null);
            try {
                assertThat("The current ingress span is the server parent", parent.get(), is(ingress));
                assertThat(server.context().traceId(), is(ingress.getTraceId()));
                assertThat("An unsampled ingress does not restart sampling", server.context().sampled(), is(false));
            } finally {
                server.end();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sampled", "record-only"})
    void rejectsAnEndedRecordingParent(String name) {
        var ended = sdk.getTracer("server-parent-test").spanBuilder(name).setNoParent().startSpan();
        ended.end();
        try (var _ = ended.makeCurrent()) {
            var server = tracing.start(request(), "/route", null);
            server.end();
        }
        assertThat(parent.get().isValid(), is(false));
    }

    @Test
    void prefersIncomingContextFromADifferentTrace() {
        var ingress = SpanContext.create("11111111111111111111111111111111", "2222222222222222",
                                         TraceFlags.getDefault(), TraceState.getDefault());
        var request = request();
        request.getHeaders().putSingle("traceparent", "00-44444444444444444444444444444444-5555555555555555-01");
        try (var _ = Context.root().with(Span.wrap(ingress)).makeCurrent()) {
            var server = tracing.start(request, "/route", null);
            server.end();
        }
        assertThat(parent.get().getSpanId(), is("5555555555555555"));
    }

    private ContainerRequest request() {
        return new ContainerRequest(URI.create("http://example.test/"), URI.create("http://example.test/route"),
                                    "GET", null, new MapPropertiesDelegate());
    }
}
