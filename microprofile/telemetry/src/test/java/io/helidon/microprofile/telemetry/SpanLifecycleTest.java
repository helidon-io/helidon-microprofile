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

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import io.helidon.tracing.SpanListener;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpanLifecycleTest {
    private final TestSpanExporter exporter = new TestSpanExporter();
    private final AtomicInteger ended = new AtomicInteger();
    private TelemetrySdk owner;
    private OpenTelemetrySdk telemetry;
    private Tracer tracer;

    @BeforeEach
    void initialize() {
        owner = TelemetryTestSupport.owner(Map.of());
        owner.initialize();
        telemetry = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
        tracer = HelidonOpenTelemetry.create(telemetry, telemetry.getTracer("test"), Map.of());
        tracer.register(new SpanListener() {
            @Override
            public void ended(io.helidon.tracing.Span span) {
                ended.incrementAndGet();
            }

            @Override
            public void ended(io.helidon.tracing.Span span, Throwable failure) {
                ended.incrementAndGet();
            }
        });
    }

    @AfterEach
    void close() {
        owner.close();
        telemetry.close();
    }

    @Test
    void responseBeforeReturnWaitsForInvocationAndWriterScopes() {
        var span = tracer.spanBuilder("early-response").start();
        var completion = new SpanLifecycle(span, owner.acquire(), true);
        var scope = completion.activate();
        var afterEnd = new AtomicInteger();
        try {
            completion.responseCompleted(null, afterEnd::incrementAndGet);
            completion.returned(false, null);
            assertThat(span.unwrap(io.opentelemetry.api.trace.Span.class).isRecording(), is(true));
            assertThat(afterEnd.get(), is(0));
            assertThat(completion.activate(), nullValue());
        } finally {
            scope.close();
        }
        scope.close();
        completion.responseCompleted(null, afterEnd::incrementAndGet);
        assertThat(exporter.spanData(1).getFirst().getName(), is("early-response"));
        assertThat(ended.get(), is(1));
        assertThat(afterEnd.get(), is(1));
    }

    @Test
    void concurrentNotificationsEndSpanAndReleaseLeaseOnce() {
        var span = tracer.spanBuilder("concurrent").start();
        var completion = new SpanLifecycle(span, owner.acquire(), true);
        completion.returned(true, null);
        IntStream.range(0, 100).parallel().forEach(_ -> {
            completion.completed(null);
            completion.responseCompleted(null, () -> { });
        });
        assertThat(exporter.spanData(1).getFirst().getName(), is("concurrent"));
        assertThat(ended.get(), is(1));
    }

    @Test
    void writerFailureRacingWithSendIsRecordedBeforeWriterReleasesOwnership() {
        var completion = new SpanLifecycle(tracer.spanBuilder("write-race").start(), owner.acquire(), true);
        completion.returned(true, null);
        try (var _ = completion.activate()) {
            completion.responseCompleted(null, () -> { });
            completion.failed(new IllegalStateException("Writer failed"));
        }
        completion.completed(null);
        assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(),
                   is(io.opentelemetry.api.trace.StatusCode.ERROR));
        assertThat(ended.get(), is(1));
    }

    @Test
    void activationRestoresCapturedContextAndCallingThreadContext() {
        ContextKey<String> key = ContextKey.named("async-test");
        SpanLifecycle completion;
        try (var _ = Context.current().with(key, "captured").makeCurrent()) {
            completion = new SpanLifecycle(tracer.spanBuilder("context").start(), owner.acquire(), false);
        }
        try (var _ = Context.current().with(key, "caller").makeCurrent()) {
            try (var activation = completion.activate()) {
                assertThat(Context.current().get(key), is("captured"));
            }
            assertThat(Context.current().get(key), is("caller"));
        }
        completion.returned(true, null);
        completion.completed(null);
        assertThat(ended.get(), is(1));
    }

    @Test
    void wrongThreadCloseLeavesActivationOwnedUntilItsThreadExits() throws Exception {
        var openAfterRejectedClose = new AtomicBoolean();
        var rejected = new AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            var span = tracer.spanBuilder("thread-bound").start();
            var completion = new SpanLifecycle(span, owner.acquire(), true);
            var scope = completion.activate();
            try {
                completion.returned(true, null);
                completion.responseCompleted(null, () -> { });
                Thread offender = Thread.ofVirtual().start(() -> {
                    try {
                        scope.close();
                    } catch (IllegalStateException failure) {
                        rejected.set(failure);
                    }
                });
                try {
                    offender.join();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                openAfterRejectedClose.set(span.unwrap(io.opentelemetry.api.trace.Span.class).isRecording() && !scope.isClosed());
            } finally {
                scope.close();
            }
        });
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive(), is(false));
        assertThat(rejected.get() instanceof IllegalStateException, is(true));
        assertThat(openAfterRejectedClose.get(), is(true));
        assertThat(exporter.spanData(1).getFirst().getName(), is("thread-bound"));
        assertThat(ended.get(), is(1));
    }

    @Test
    void interruptedShutdownRetainsSdkUntilWriterActuallyExits() throws Exception {
        var span = tracer.spanBuilder("drain").start();
        var completion = new SpanLifecycle(span, owner.acquire(), true);
        var writer = completion.activate();
        var interrupted = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        Thread closer = null;
        try {
            completion.returned(true, null);
            completion.responseCompleted(null, () -> { });
            closer = Thread.ofVirtual().start(() -> {
                entered.countDown();
                try {
                    owner.close();
                } catch (IllegalStateException _) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS), is(true));
            closer.interrupt();
            closer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(closer.isAlive(), is(false));
            assertThat(interrupted.get(), is(true));
            assertThat(owner.acquire(), nullValue());
            assertThat(span.unwrap(io.opentelemetry.api.trace.Span.class).isRecording(), is(true));
        } finally {
            writer.close();
            if (closer != null) {
                closer.join(TimeUnit.SECONDS.toMillis(5));
            }
        }
        owner.close();
        owner.close();
        assertThat(ended.get(), is(1));
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportFailuresEndSpansAndReleaseLeasesWithoutMaskingOriginal(boolean commit) {
        var primary = new IllegalStateException("Transport failed");
        var cleanup = new IllegalStateException("Measurement failed");
        var observation = new HttpServerObservation(owner.acquire(), _ -> { throw cleanup; });
        var completion = observation.startMethodSpan(tracer.spanBuilder("failed-write").start(), owner.acquire());
        completion.returned(true, null);
        var writer = new TelemetryResponseWriter(new FailedWriter(primary), observation);
        var thrown = assertThrows(IllegalStateException.class, () -> {
            if (commit) {
                writer.commit();
            } else {
                writer.failure(primary);
            }
        });
        assertThat(thrown, sameInstance(primary));
        assertThat(primary.getSuppressed().length, is(1));
        assertThat(primary.getSuppressed()[0], sameInstance(cleanup));
        observation.complete(null);
        assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(),
                   is(io.opentelemetry.api.trace.StatusCode.ERROR));
        assertThat(ended.get(), is(1));
        owner.close();
    }

    @Test
    void responseCompletingBeforeInvocationDoesNotLeaveAnOrphanedSpan() {
        var observation = new HttpServerObservation(owner.acquire(), _ -> { });
        observation.complete(null);
        var completion = observation.startMethodSpan(tracer.spanBuilder("late-invocation").start(), owner.acquire());
        completion.returned(false, null);
        assertThat(exporter.spanData(1).getFirst().getName(), is("late-invocation"));
        assertThat(ended.get(), is(1));
    }

    @Test
    void registrationRacingWithResponseCompletionAlwaysReleasesOwnership() throws Exception {
        for (int i = 0; i < 25; i++) {
            var observation = new HttpServerObservation(owner.acquire(), _ -> { });
            var sending = Thread.ofVirtual().start(() -> observation.complete(null));
            var completion = observation.startMethodSpan(tracer.spanBuilder("registration-race").start(), owner.acquire());
            completion.returned(false, null);
            sending.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(sending.isAlive(), is(false));
        }
        exporter.spanData(25);
        assertThat(ended.get(), is(25));
    }

    private static class FailedWriter implements ContainerResponseWriter {
        private final IllegalStateException failure;

        private FailedWriter(IllegalStateException failure) {
            this.failure = failure;
        }

        @Override
        public OutputStream writeResponseStatusAndHeaders(long length, ContainerResponse response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean suspend(long timeout, TimeUnit unit, TimeoutHandler handler) {
            return false;
        }

        @Override
        public void setSuspendTimeout(long timeout, TimeUnit unit) {
        }

        @Override
        public void commit() {
            throw failure;
        }

        @Override
        public void failure(Throwable thrown) {
            throw failure;
        }

        @Override
        public boolean enableResponseBuffering() {
            return false;
        }
    }

}
