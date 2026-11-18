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

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.helidon.common.testing.junit5.MatcherWithRetry;
import io.helidon.microprofile.telemetry.spi.HelidonTelemetryClientFilterHelper;
import io.helidon.tracing.Scope;
import io.helidon.tracing.SpanListener;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientLifecycleTest {
    private final TestSpanExporter exporter = new TestSpanExporter();
    private final ControlledConnector connector = new ControlledConnector();
    private List<HelidonTelemetryClientFilterHelper> helpers = List.of();
    private OpenTelemetrySdk telemetry;
    private TelemetrySdk owner;
    private Tracer tracer;
    private ExecutorService executor;
    private io.opentelemetry.context.Scope root;

    @BeforeEach
    void initialize() {
        root = Context.root().makeCurrent();
        telemetry = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(telemetry);
        owner = TelemetryTestSupport.owner(Map.of("io.helidon.telemetry.otel.use-existing-instance", "true"));
        owner.initialize();
        tracer = HelidonOpenTelemetry.create(telemetry, telemetry.getTracer("client-lifecycle"), Map.of());
    }

    @AfterEach
    void close() {
        ClientRequest request = connector.request;
        if (request != null) {
            var scope = (Scope) request.getProperty(Scope.class.getName());
            if (scope != null) {
                try {
                    scope.close();
                } catch (IllegalStateException _) {
                    // A broken implementation might have opened it on a different thread.
                }
            }
            var span = (io.helidon.tracing.Span) request.getProperty(io.helidon.tracing.Span.class.getName());
            if (span != null && span.unwrap(Span.class).isRecording()) {
                span.end();
            }
        }
        owner.close();
        GlobalOpenTelemetry.resetForTest();
        telemetry.close();
        root.close();
    }

    @Test
    void transportFailureEndsSpanAndRestoresCaller() {
        connector.failure = true;
        try (var client = client()) {
            assertThrows(ProcessingException.class, () -> client.target("http://example.invalid/test").request().get());
            assertThat("Caller context restored after transport failure", Span.current().getSpanContext().isValid(), is(false));
            assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.ERROR));
        }
    }

    @Test
    void asynchronousResponseOnAnotherThreadRestoresBothContexts() throws Exception {
        try (var client = client()) {
            var parent = telemetry.getTracer("test").spanBuilder("parent").startSpan();
            try {
                Future<Response> result;
                try (var _ = parent.makeCurrent()) {
                    result = client.target("http://example.invalid/test").request().async().get();
                }
                try (Response response = result.get(5, TimeUnit.SECONDS)) {
                    assertThat(response.getStatus(), is(200));
                }
                assertThat(connector.finished.await(5, TimeUnit.SECONDS), is(true));
                assertThat(connector.workerContextValid, is(false));
                assertThat(Span.current().getSpanContext().isValid(), is(false));
                assertThat(exporter.spanData(1).getFirst().getParentSpanId(), is(parent.getSpanContext().getSpanId()));
            } finally {
                parent.end();
            }
        }
    }

    @Test
    void laterRequestFilterFailureStillEndsTheSpan() {
        try (var client = client()) {
            client.register((ClientRequestFilter) _ -> { throw new ProcessingException("Synthetic filter failure"); },
                            Priorities.USER + 100);
            assertThrows(ProcessingException.class, () -> client.target("http://example.invalid/test").request().get());
            assertThat(Span.current().getSpanContext().isValid(), is(false));
            assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.ERROR));
        }
    }

    @Test
    void asynchronousTransportFailureEndsSpanOnCompletionThread() throws Exception {
        connector.failure = true;
        try (var client = client()) {
            var result = client.target("http://example.invalid/test").request().async().get();
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertThat(connector.finished.await(5, TimeUnit.SECONDS), is(true));
            assertThat(connector.workerContextValid, is(false));
            assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.ERROR));
        }
    }

    @Test
    void helperVetoSkipsClientSpanAndPreservesCallerContext() {
        var invoked = new AtomicInteger();
        helpers = List.of(request -> {
            invoked.incrementAndGet();
            request.getHeaders().putSingle("X-Test-Helper", "visited");
            return false;
        });
        var started = countStarted();
        var parent = telemetry.getTracer("test").spanBuilder("parent").startSpan();
        try (var client = client(); var _ = parent.makeCurrent();
             var response = client.target("http://example.invalid/test").request().get()) {
            assertThat(response.getStatus(), is(200));
            assertThat(connector.request.getHeaderString("X-Test-Helper"), is("visited"));
            assertThat(Span.current().getSpanContext(), is(parent.getSpanContext()));
            assertThat(started.get(), is(0));
            assertThat(invoked.get(), is(1));
        } finally {
            parent.end();
        }
        assertThat(Span.current().getSpanContext().isValid(), is(false));
        assertThat(exporter.spanData(1).getFirst().getName(), is("parent"));
    }

    @Test
    void laterResponseFilterFailureStillEndsTheSpan() {
        try (var client = client()) {
            client.register((ClientResponseFilter) (_, _) -> { throw new ProcessingException("Synthetic response failure"); },
                            Priorities.USER - 100);
            assertThrows(ProcessingException.class, () -> client.target("http://example.invalid/test").request().get());
            assertThat(Span.current().getSpanContext().isValid(), is(false));
            assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.ERROR));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"baggage", "none"})
    void disabledSignalsHonorConfiguredPropagationWithoutCreatingSpans(String propagators) throws Exception {
        owner.close();
        owner = TelemetryTestSupport.owner(Map.of("otel.sdk.disabled", "true", "otel.propagators", propagators));
        owner.initialize();
        var started = countStarted();
        try (var client = client()) {
            Future<Response> result;
            try (var _ = Baggage.builder().put("test-key", "value").build().makeCurrent()) {
                result = client.target("http://example.invalid/test").request().async().get();
            }
            try (var response = result.get(5, TimeUnit.SECONDS)) {
                assertThat(response.getStatus(), is(200));
            }
            assertThat(connector.request.getHeaders().containsKey("baggage"), is(propagators.equals("baggage")));
            assertThat(started.get(), is(0));
            assertThat(Baggage.current().isEmpty(), is(true));
        }
    }

    @Test
    void unsampledParentsStillCreateAndPropagateClientContext() {
        String traceId = "11111111111111111111111111111111";
        var parent = Span.wrap(SpanContext.createFromRemoteParent(traceId, "2222222222222222",
                                                                TraceFlags.getDefault(), TraceState.getDefault()));
        var started = countStarted();
        try (var client = client(); var _ = parent.makeCurrent();
             var response = client.target("http://example.invalid/test").request().get()) {
            assertThat(response.getStatus(), is(200));
            String traceparent = connector.request.getHeaderString("traceparent");
            assertThat(traceparent.substring(3, 35), is(traceId));
            assertThat(traceparent.endsWith("-00"), is(true));
            assertThat(started.get(), is(1));
        }
    }

    @Test
    void agentOwnedClientInstrumentationCreatesNoIntegrationSpan() {
        owner.close();
        owner = TelemetryTestSupport.owner(Map.of(HelidonOpenTelemetry.OTEL_AGENT_PRESENT_PROPERTY, "true"));
        owner.initialize();
        var started = countStarted();
        connector.agentInstrumentation = () -> telemetry.getTracer("agent").spanBuilder("agent-client")
                .setSpanKind(SpanKind.CLIENT).startSpan().end();
        try (var client = client(); var response = client.target("http://example.invalid/test").request().get()) {
            assertThat(response.getStatus(), is(200));
            assertThat(started.get(), is(0));
            assertThat(exporter.spanData(1).getFirst().getName(), is("agent-client"));
        }
    }

    @Test
    void rejectionBeforeRequestExecutionLeavesNoSdkReservation() {
        executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var started = countStarted();
        try (var client = client()) {
            assertThrows(RuntimeException.class, () -> client.target("http://example.invalid/test").request().async().get());
        }
        owner.close();
        assertThat(started.get(), is(0));
    }

    @Test
    void cancellationBeforeExecutionDoesNotReserveSdkOrStartSpanAfterShutdown() throws Exception {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(running.await(5, TimeUnit.SECONDS), is(true));
        var started = countStarted();
        try (var client = client()) {
            try {
                var result = client.target("http://example.invalid/test").request().async().get();
                assertThat(result.cancel(false), is(true));
                owner.close();
            } finally {
                release.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS), is(true));
            }
        } finally {
            release.countDown();
            executor.close();
        }
        assertThat(started.get(), is(0));
    }

    @Test
    void canceledClientFutureDoesNotReleaseWorkBeforeConnectorActuallyCompletes() throws Exception {
        connector.deferred = true;
        var closed = new CompletableFuture<Void>();
        Thread closer = Thread.ofVirtual().unstarted(() -> {
            try {
                owner.close();
                closed.complete(null);
            } catch (Throwable failure) {
                closed.completeExceptionally(failure);
            }
        });
        try (var client = client()) {
            try {
                var result = client.target("http://example.invalid/test").request().async().get();
                assertThat(connector.entered.await(5, TimeUnit.SECONDS), is(true));
                assertThat(result.cancel(false), is(true));
                closer.start();
                MatcherWithRetry.assertThatWithRetry("SDK shutdown closes admission", owner::instrumentationEnabled,
                                                     is(false), 50, 100);
                MatcherWithRetry.assertThatWithRetry("SDK shutdown reaches its drain wait", () -> {
                    if (closed.isCompletedExceptionally()) {
                        closed.join();
                    }
                    return closer.getState();
                }, is(Thread.State.WAITING), 50, 100);
                assertThat("Pending connector work retains the SDK", closed.isDone(), is(false));
            } finally {
                connector.respond();
                closer.join(TimeUnit.SECONDS.toMillis(5));
                assertThat(closer.isAlive(), is(false));
            }
            closed.get(5, TimeUnit.SECONDS);
        }
        assertThat(exporter.spanData(1).size(), is(1));
        // Canceling the consumer future did not stop this connector's eventual successful HTTP operation.
        assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.UNSET));
    }

    private AtomicInteger countStarted() {
        var started = new AtomicInteger();
        tracer.register(new SpanListener() {
            @Override
            public void started(io.helidon.tracing.Span span) {
                started.incrementAndGet();
            }
        });
        return started;
    }

    @SuppressWarnings("unchecked")
    private Client client() {
        Instance<HelidonTelemetryClientFilterHelper> helperInstances = (Instance<HelidonTelemetryClientFilterHelper>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instance.class}, (_, method, _) -> {
                    if (method.getName().equals("stream")) {
                        return helpers.stream();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var filter = new HttpClientTelemetryFilter(new HttpClientTracing(tracer, helperInstances, owner));
        var config = new ClientConfig().property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true).register(filter);
        config.connectorProvider((_, _) -> connector);
        var builder = ClientBuilder.newBuilder().withConfig(config);
        if (executor != null) {
            builder.executorService(executor);
        }
        return builder.build();
    }

    private static class ControlledConnector implements Connector {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile ClientRequest request;
        private volatile AsyncConnectorCallback callback;
        private volatile boolean workerContextValid;
        private boolean deferred;
        private boolean failure;
        private Runnable agentInstrumentation = () -> { };

        @Override
        public ClientResponse apply(ClientRequest request) {
            this.request = request;
            agentInstrumentation.run();
            if (failure) {
                throw new ProcessingException("Synthetic transport failure");
            }
            return response();
        }

        @Override
        public Future<?> apply(ClientRequest request, AsyncConnectorCallback callback) {
            this.request = request;
            this.callback = callback;
            entered.countDown();
            if (!deferred) {
                Thread.ofVirtual().start(this::respond);
            }
            return CompletableFuture.completedFuture(null);
        }

        void respond() {
            AsyncConnectorCallback current = callback;
            if (current != null) {
                try {
                    if (failure) {
                        current.failure(new ProcessingException("Synthetic async transport failure"));
                    } else {
                        current.response(response());
                    }
                } finally {
                    workerContextValid = Span.current().getSpanContext().isValid();
                    finished.countDown();
                }
            }
        }

        private ClientResponse response() {
            var response = new ClientResponse(Response.Status.OK, request);
            response.getHeaders().putSingle("Content-Type", "text/plain");
            response.setEntityStream(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)));
            return response;
        }

        @Override
        public String getName() {
            return "controlled-test";
        }

        @Override
        public void close() {
        }
    }
}
