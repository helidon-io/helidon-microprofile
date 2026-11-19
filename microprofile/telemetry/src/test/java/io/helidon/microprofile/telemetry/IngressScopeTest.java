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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.tracing.Scope;
import io.helidon.tracing.Tracer;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@HelidonTest
@AddBean(TestSpanExporter.class)
@AddBean(IngressScopeTest.Resource.class)
@AddBean(IngressScopeTest.Ingress.class)
@AddBean(IngressScopeTest.BeforeIngress.class)
@AddBean(IngressScopeTest.AfterIngress.class)
@AddBean(IngressScopeTest.Writer.class)
@AddBean(IngressScopeTest.Control.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "in-memory")
class IngressScopeTest {
    @Inject
    WebTarget target;
    @Inject
    TestSpanExporter exporter;
    @Inject
    Control control;

    @Test
    void responseFiltersAndSerializationRestoreTheIngressOwnersContext() throws Exception {
        exporter.clear();
        try (var response = target.path("ingress-scope").request().get()) {
            assertThat(response.readEntity(String.class), is("ok"));
        }
        assertThat(control.written.await(5, TimeUnit.SECONDS), is(true));
        var spans = exporter.spanData(3);
        var server = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        assertThat(server.getParentSpanId(), is(control.ingress));
        assertThat("Earlier request filters regain their own context in the response chain", control.beforeIngress,
                   is(control.ingress));
        assertThat("Closing ingress restores the outer context", control.afterIngressValid, is(false));
        assertThat("Response filtering does not resurrect ingress before serialization", control.beforeWriteValid, is(false));
        assertThat("Serialization restores the context it entered", control.afterWriteValid, is(false));
    }

    @Singleton
    public static class Control {
        final CountDownLatch written = new CountDownLatch(1);
        volatile String ingress;
        volatile String beforeIngress;
        volatile boolean afterIngressValid;
        volatile boolean beforeWriteValid;
        volatile boolean afterWriteValid;
    }

    @ApplicationScoped
    @Path("ingress-scope")
    public static class Resource {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Provider
    @Priority(Priorities.HEADER_DECORATOR)
    public static class Ingress implements ContainerRequestFilter, ContainerResponseFilter {
        @Inject
        Tracer tracer;
        @Inject
        Control control;

        @Override
        public void filter(ContainerRequestContext request) {
            var builder = tracer.spanBuilder("ingress");
            tracer.extract(new RequestContextHeaderProvider(request.getHeaders())).ifPresent(builder::parent);
            var span = builder.start();
            control.ingress = span.context().spanId();
            request.setProperty("test.ingress.span", span);
            request.setProperty("test.ingress.scope", span.activate());
        }

        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            ((Scope) request.getProperty("test.ingress.scope")).close();
            ((io.helidon.tracing.Span) request.getProperty("test.ingress.span")).end();
        }
    }

    @Provider
    @Priority(Priorities.HEADER_DECORATOR + 1)
    public static class BeforeIngress implements ContainerResponseFilter {
        @Inject
        Control control;

        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            control.beforeIngress = Span.current().getSpanContext().getSpanId();
        }
    }

    @Provider
    @Priority(Priorities.AUTHENTICATION - 1)
    public static class AfterIngress implements ContainerResponseFilter {
        @Inject
        Control control;

        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            control.afterIngressValid = Span.current().getSpanContext().isValid();
        }
    }

    @Provider
    @Priority(Integer.MIN_VALUE)
    public static class Writer implements WriterInterceptor {
        @Inject
        Control control;

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            control.beforeWriteValid = Span.current().getSpanContext().isValid();
            try {
                context.proceed();
            } finally {
                control.afterWriteValid = Span.current().getSpanContext().isValid();
                control.written.countDown();
            }
        }
    }
}
