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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.concurrent.TimeUnit;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.webserver.http.ServerResponse;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

@HelidonTest
@AddBean(TestSpanExporter.class)
@AddBean(AsyncWithSpanTest.Resource.class)
@AddBean(AsyncWithSpanTest.Control.class)
@AddBean(AsyncWithSpanTest.WriteProbe.class)
@AddBean(AsyncWithSpanTest.SendProbe.class)
@AddBean(AsyncWithSpanTest.FailureMapper.class)
@AddBean(AsyncWithSpanTest.AsyncBean.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "in-memory")
class AsyncWithSpanTest {
    @Inject
    WebTarget target;
    @Inject
    Control control;
    @Inject
    AsyncBean bean;
    @Inject
    TestSpanExporter exporter;

    @BeforeEach
    void reset() {
        exporter.clear();
        control.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage", "future", "completed", "suspended", "failed"})
    void resourceSpanRemainsOpenThroughResponseWriting(String path) throws Exception {
        if (path.equals("completed")) {
            control.stage.complete("done");
        }
        var response = target.path("async-with-span/" + path).request().async().get();
        Thread completing = null;
        try {
            assertThat(control.entered.await(5, TimeUnit.SECONDS), is(true));
            assertThat("Span remains open after resource return", control.span.isRecording(), is(true));
            completing = Thread.ofVirtual().start(control.context.wrap(() -> {
                if (path.equals("suspended")) {
                    control.response.resume("done");
                } else if (path.equals("failed")) {
                    control.stage.completeExceptionally(new TestFailure());
                } else {
                    control.stage.complete("done");
                }
            }));
            assertThat(control.writing.await(5, TimeUnit.SECONDS), is(true));
            assertThat("Span remains open while serialization is blocked", control.span.isRecording(), is(true));
            assertThat("Writer sees resource method context", control.writerSpanId, is(control.span.getSpanContext().getSpanId()));
            control.allowWrite.countDown();
            try (Response result = response.get(10, TimeUnit.SECONDS)) {
                assertThat(result.getStatus(), is(path.equals("failed") ? 503 : 200));
                assertThat(result.readEntity(String.class), is(path.equals("failed") ? "failed" : "done"));
            }
            List<SpanData> spans = exporter.spanData(4);
            SpanData method = spans.stream().filter(span -> span.getName().equals("async-resource")).findFirst().orElseThrow();
            SpanData server = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
            SpanData serialization = spans.stream().filter(span -> span.getName().equals("serialization")).findFirst().orElseThrow();
            assertThat(serialization.getParentSpanId(), is(method.getSpanId()));
            assertThat(method.getParentSpanId(), is(server.getSpanId()));
            assertThat(method.getEndEpochNanos() >= serialization.getEndEpochNanos(), is(true));
            assertThat(server.getEndEpochNanos() >= method.getEndEpochNanos(), is(true));
            assertThat("Method events: " + method.getEvents(), method.getStatus().getStatusCode(),
                       is(path.equals("failed") ? StatusCode.ERROR : StatusCode.UNSET));
            assertThat("Span remains open at the response-sent boundary", control.recordingAtSend, is(true));
            assertThat(control.span.isRecording(), is(false));
        } finally {
            control.release();
            if (completing != null) {
                completing.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
    }

    @Test
    void suspendedCancellationEndsAfterResponseWithoutEntity() throws Exception {
        var response = target.path("async-with-span/suspended").request().async().get();
        assertThat(control.entered.await(5, TimeUnit.SECONDS), is(true));
        assertThat(control.span.isRecording(), is(true));
        assertThat(control.response.cancel(), is(true));
        try (Response result = response.get(10, TimeUnit.SECONDS)) {
            assertThat(result.getStatus(), is(503));
        }
        exporter.spanData(3);
        assertThat(control.span.isRecording(), is(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "cancel", "already-completed"})
    void standaloneStageOwnsSpanUntilCompletion(String outcome) {
        var future = new CompletableFuture<String>();
        if (outcome.equals("already-completed")) {
            future.complete("done");
        }
        try {
            assertThat(bean.run(future), sameInstance(future));
            assertThat(control.span.isRecording(), is(!future.isDone()));
            switch (outcome) {
                case "failure" -> future.completeExceptionally(new TestFailure());
                case "cancel" -> future.cancel(false);
                default -> future.complete("done");
            }
            var span = exporter.spanData(1).getFirst();
            assertThat(span.getName(), is("async-cdi"));
            assertThat(span.getStatus().getStatusCode(),
                       is(outcome.equals("failure") || outcome.equals("cancel") ? StatusCode.ERROR : StatusCode.UNSET));
            assertThat(control.span.isRecording(), is(false));
            assertThat("Caller activation restored", Span.current().getSpanContext().isValid(), is(false));
        } finally {
            future.cancel(false);
        }
    }

    @Singleton
    public static class Control {
        CompletableFuture<String> stage;
        CountDownLatch entered;
        CountDownLatch writing;
        CountDownLatch allowWrite;
        volatile Span span;
        volatile Context context;
        volatile AsyncResponse response;
        volatile String writerSpanId;
        volatile boolean recordingAtSend;

        void reset() {
            stage = new CompletableFuture<>();
            entered = new CountDownLatch(1);
            writing = new CountDownLatch(1);
            allowWrite = new CountDownLatch(1);
            recordingAtSend = false;
            span = null;
            context = null;
            response = null;
            writerSpanId = null;
        }

        void release() {
            allowWrite.countDown();
            stage.complete("cleanup");
            AsyncResponse pending = response;
            if (pending != null && pending.isSuspended()) {
                pending.cancel();
            }
        }

        void capture() {
            span = Span.current();
            context = Context.current();
            entered.countDown();
        }
    }

    @Test
    void failedCompletionRegistrationDoesNotLeakSpanOrReplaceFailure() {
        var future = new CompletableFuture<String>() {
            @Override
            public CompletableFuture<String> whenComplete(BiConsumer<? super String, ? super Throwable> action) {
                throw new RejectedExecutionException("Completion callback rejected");
            }
        };
        assertThrows(RejectedExecutionException.class, () -> bean.run(future));
        assertThat(control.span.isRecording(), is(false));
        assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.ERROR));
    }

    @ApplicationScoped
    public static class AsyncBean {
        @Inject
        Control control;

        @WithSpan("async-cdi")
        CompletableFuture<String> run(CompletableFuture<String> future) {
            control.capture();
            return future;
        }
    }

    @ApplicationScoped
    @Path("async-with-span")
    @Produces(MediaType.TEXT_PLAIN)
    public static class Resource {
        @Inject
        Control control;

        @GET
        @Path("stage")
        @WithSpan("async-resource")
        public CompletionStage<String> stage() {
            control.capture();
            return control.stage;
        }

        @GET
        @Path("future")
        @WithSpan("async-resource")
        public CompletableFuture<String> future() {
            control.capture();
            return control.stage;
        }

        @GET
        @Path("completed")
        @WithSpan("async-resource")
        public CompletionStage<String> completed() {
            return stage();
        }

        @GET
        @Path("failed")
        @WithSpan("async-resource")
        public CompletionStage<String> failed() {
            return stage();
        }

        @GET
        @Path("suspended")
        @WithSpan("async-resource")
        public void suspended(@Suspended AsyncResponse response) {
            control.response = response;
            control.capture();
        }
    }

    @Provider
    @PreMatching
    @Priority(Interceptor.Priority.PLATFORM_BEFORE - 1)
    public static class SendProbe implements ContainerRequestFilter {
        @Inject
        Control control;
        @jakarta.ws.rs.core.Context
        ServerResponse response;

        @Override
        public void filter(ContainerRequestContext request) {
            // Observe the send boundary before telemetry's platform-priority completion callback ends the span.
            response.whenSent(() -> control.recordingAtSend = control.span.isRecording());
        }
    }

    @Provider
    @Priority(Priorities.ENTITY_CODER)
    public static class WriteProbe implements WriterInterceptor {
        @Inject
        Control control;
        @Inject
        OpenTelemetry telemetry;

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            control.writerSpanId = Span.current().getSpanContext().getSpanId();
            control.writing.countDown();
            try {
                if (!control.allowWrite.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Response writer was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Response writer interrupted", e);
            }
            var child = telemetry.getTracer("test").spanBuilder("serialization").startSpan();
            try (var _ = child.makeCurrent()) {
                context.proceed();
            } finally {
                child.end();
            }
        }
    }

    static class TestFailure extends RuntimeException {
    }

    @Provider
    public static class FailureMapper implements ExceptionMapper<TestFailure> {
        @Override
        public Response toResponse(TestFailure failure) {
            return Response.status(503).type(MediaType.TEXT_PLAIN).entity("failed").build();
        }
    }
}
