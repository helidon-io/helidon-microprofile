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
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.tracing.Scope;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpServerSpanTest {
    private TelemetrySdk owner;
    private OpenTelemetrySdk sdk;
    private Tracer tracer;

    @BeforeEach
    void initialize() {
        owner = TelemetryTestSupport.owner(Map.of());
        owner.initialize();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new TestSpanExporter())).build()).build();
        tracer = HelidonOpenTelemetry.create(sdk, sdk.getTracer("server-span-test"), Map.of());
    }

    @AfterEach
    void close() {
        owner.close();
        sdk.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUEST_FILTERS", "METHOD", "RESPONSE_FILTERS", "MAPPING", "nested"})
    void finishedRequestDrainsRemainingActivationsOnTheirOwningThread(String phase) {
        var outer = sdk.getTracer("server-span-test").spanBuilder("outer").startSpan();
        try (var _ = outer.makeCurrent()) {
            var span = tracer.spanBuilder("server").start();
            var lifecycle = new HttpServerSpan(span, owner.acquire(), null);
            if (phase.equals("nested")) {
                lifecycle.activate(HttpServerSpan.Phase.REQUEST_FILTERS);
                lifecycle.activate(HttpServerSpan.Phase.METHOD);
            } else {
                lifecycle.activate(HttpServerSpan.Phase.valueOf(phase));
            }
            lifecycle.returned(null);
            try {
                var event = finishedEvent(lifecycle);
                var telemetry = new HttpServerTelemetry(owner, new HttpServerMetrics(owner), null, new TelemetryRequest());
                telemetry.onEvent(event);
                assertThat("FINISHED restores the enclosing context", Span.current().getSpanContext(), is(outer.getSpanContext()));
                assertThat("FINISHED ends the span after releasing its activations", span.unwrap(Span.class).isRecording(), is(false));
                telemetry.onEvent(event);
            } finally {
                lifecycle.deactivate(HttpServerSpan.Phase.MAPPING);
                lifecycle.deactivate(HttpServerSpan.Phase.RESPONSE_FILTERS);
                lifecycle.deactivate(HttpServerSpan.Phase.METHOD);
                lifecycle.deactivate(HttpServerSpan.Phase.REQUEST_FILTERS);
                lifecycle.complete(null);
            }
        } finally {
            outer.end();
        }
    }
    @Test
    void failedScopeCloseStillDrainsRemainingActivationsAndCompletesRequest() {
        var underlying = tracer.spanBuilder("failed-close").start();
        var failure = new IllegalStateException("Synthetic activation close failure");
        var failClose = new AtomicBoolean(true);
        var span = (io.helidon.tracing.Span) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {io.helidon.tracing.Span.class}, (_, method, args) -> {
                    if (method.getName().equals("activate")) {
                        Scope delegate = underlying.activate();
                        return new Scope() {
                            @Override
                            public void close() {
                                delegate.close();
                                if (failClose.getAndSet(false)) {
                                    throw failure;
                                }
                            }

                            @Override
                            public boolean isClosed() {
                                return delegate.isClosed();
                            }
                        };
                    }
                    return method.invoke(underlying, args);
                });
        var lifecycle = new HttpServerSpan(span, owner.acquire(), null);
        lifecycle.activate(HttpServerSpan.Phase.REQUEST_FILTERS);
        lifecycle.activate(HttpServerSpan.Phase.METHOD);
        lifecycle.returned(null);
        try {
            var telemetry = new HttpServerTelemetry(owner, new HttpServerMetrics(owner), null, new TelemetryRequest());
            assertThat(assertThrows(IllegalStateException.class, () -> telemetry.onEvent(finishedEvent(lifecycle))),
                       sameInstance(failure));
            assertThat(underlying.unwrap(Span.class).isRecording(), is(false));
            assertThat(Span.current().getSpanContext().isValid(), is(false));
            telemetry.onEvent(finishedEvent(lifecycle));
        } finally {
            lifecycle.deactivate(HttpServerSpan.Phase.METHOD);
            lifecycle.deactivate(HttpServerSpan.Phase.REQUEST_FILTERS);
            lifecycle.complete(null);
        }
    }

    private RequestEvent finishedEvent(HttpServerSpan lifecycle) {
        var request = new ContainerRequest(URI.create("http://example.test/"), URI.create("http://example.test/route"),
                                           "GET", null, new MapPropertiesDelegate());
        request.setProperty(HttpServerSpan.PROPERTY, lifecycle);
        return (RequestEvent) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {RequestEvent.class},
                (_, method, _) -> switch (method.getName()) {
                    case "getType" -> RequestEvent.Type.FINISHED;
                    case "getContainerRequest" -> request;
                    case "isResponseWritten" -> false;
                    case "getException" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

}
