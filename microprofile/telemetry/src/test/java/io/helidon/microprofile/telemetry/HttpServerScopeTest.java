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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@HelidonTest
@AddBean(TestSpanExporter.class)
@AddBean(HttpServerScopeTest.Control.class)
@AddBean(HttpServerScopeTest.Resource.class)
@AddBean(HttpServerScopeTest.Probe.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "in-memory")
class HttpServerScopeTest {
    @Inject
    WebTarget target;
    @Inject
    Control control;
    @Inject
    TestSpanExporter exporter;

    @ParameterizedTest
    @ValueSource(strings = {"plain", "annotated", "inline"})
    void responsePhasesActivateOnTheirOwnThreadAndRestoreTheWorker(String path) throws Exception {
        exporter.clear();
        control.stage = new CompletableFuture<>();
        control.entered = new CountDownLatch(1);
        control.methodSpan = null;
        var result = target.path("server-scope/" + path).request().async().get();
        assertThat(control.entered.await(5, TimeUnit.SECONDS), is(true));
        var cleanWorker = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            // Deliberately do not wrap this task in an application OpenTelemetry context.
            control.stage.complete("ok");
            cleanWorker.set(!Span.current().getSpanContext().isValid());
        });
        try (var response = result.get(5, TimeUnit.SECONDS)) {
            assertThat(response.readEntity(String.class), is("ok"));
        }
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive(), is(false));
        assertThat(cleanWorker.get(), is(true));
        var spans = exporter.spanData(path.equals("plain") ? 4 : 5);
        var parent = path.equals("plain")
                ? spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow()
                : spans.stream().filter(span -> span.getName().equals("resource-scope")).findFirst().orElseThrow();
        assertThat(control.responseSpan, is(parent.getSpanId()));
        assertThat(control.writerSpan, is(parent.getSpanId()));
        for (var child : spans.stream().filter(span -> span.getName().startsWith("probe-")).toList()) {
            assertThat(child.getParentSpanId(), is(parent.getSpanId()));
        }
        assertThat(Span.current().getSpanContext().isValid(), is(false));
        if (path.equals("inline")) {
            assertThat(control.openAfterResume, is(true));
        }
    }

    @Singleton
    public static class Control {
        CompletableFuture<String> stage;
        CountDownLatch entered;
        volatile Span methodSpan;
        volatile String responseSpan;
        volatile String writerSpan;
        volatile boolean openAfterResume;
    }

    @ApplicationScoped
    @Path("server-scope")
    public static class Resource {
        @Inject
        Control control;

        @GET
        @Path("plain")
        public CompletionStage<String> plain() {
            control.entered.countDown();
            return control.stage;
        }

        @GET
        @Path("annotated")
        @WithSpan("resource-scope")
        public CompletionStage<String> annotated() {
            control.methodSpan = Span.current();
            return plain();
        }

        @GET
        @Path("inline")
        @WithSpan("resource-scope")
        public void inline(@Suspended AsyncResponse response) {
            control.methodSpan = Span.current();
            response.resume("ok");
            control.openAfterResume = control.methodSpan.isRecording();
            control.entered.countDown();
        }
    }

    @Provider
    public static class Probe implements ContainerResponseFilter, WriterInterceptor {
        @Inject
        Control control;
        @Inject
        OpenTelemetry telemetry;

        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            control.responseSpan = Span.current().getSpanContext().getSpanId();
            telemetry.getTracer("test").spanBuilder("probe-response").startSpan().end();
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            control.writerSpan = Span.current().getSpanContext().getSpanId();
            telemetry.getTracer("test").spanBuilder("probe-writer").startSpan().end();
            context.proceed();
        }
    }
}
