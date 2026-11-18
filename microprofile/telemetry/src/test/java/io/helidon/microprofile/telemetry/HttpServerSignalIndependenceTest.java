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

import java.util.concurrent.TimeUnit;

import io.helidon.microprofile.telemetry.spi.HelidonTelemetryContainerFilterHelper;
import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.semconv.HttpAttributes;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@HelidonTest
@AddBean(TestSpanExporter.class)
@AddBean(HttpServerSignalIndependenceTest.Resource.class)
@AddBean(HttpServerSignalIndependenceTest.Veto.class)
@AddBean(HttpServerSignalIndependenceTest.Abort.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "in-memory")
@AddConfig(key = "otel.metrics.exporter", value = "sdk-test")
class HttpServerSignalIndependenceTest {
    @Inject
    WebTarget target;
    @Inject
    TelemetrySdk sdk;
    @Inject
    TestSpanExporter exporter;

    @Test
    void traceVetoUnmatchedAndPrematchingAbortDoNotSuppressOrDuplicateMeasurements() {
        exporter.clear();
        SdkTestExporters.METRICS.clear();
        check("allowed", 200);
        check("vetoed", 200);
        check("aborted", 429);
        check("missing", 404);
        var spans = exporter.spanData(5);
        assertThat(spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).count(), is(4L));
        var server = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
        assertThat(server.size(), is(1));
        assertThat(server.getFirst().getName(), is("GET /telemetry-independent/allowed"));
        ((OpenTelemetrySdk) sdk.telemetry()).getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
        var duration = SdkTestExporters.METRICS.stream()
                .filter(metric -> metric.getName().equals("http.server.request.duration")).reduce((_, latest) -> latest)
                .orElseThrow();
        var points = duration.getHistogramData().getPoints();
        assertThat(points.stream().filter(point -> "/telemetry-independent/vetoed".equals(
                point.getAttributes().get(HttpAttributes.HTTP_ROUTE))).count(), is(1L));
        assertThat(points.size(), is(4));
        assertThat(points.stream().mapToLong(point -> point.getCount()).sum(), is(4L));
        assertThat(points.stream().filter(point -> point.getAttributes().get(HttpAttributes.HTTP_ROUTE) != null).count(), is(2L));
    }

    private void check(String path, int status) {
        try (var response = target.path("telemetry-independent/" + path).request().get()) {
            response.readEntity(String.class);
            assertThat(response.getStatus(), is(status));
        }
    }

    @ApplicationScoped
    @Path("telemetry-independent")
    public static class Resource {
        @GET
        @Path("allowed")
        public String allowed() {
            return "allowed";
        }

        @GET
        @Path("vetoed")
        public String vetoed() {
            return "vetoed";
        }
    }

    @ApplicationScoped
    public static class Veto implements HelidonTelemetryContainerFilterHelper {
        @Override
        public boolean shouldStartSpan(ContainerRequestContext request) {
            return !request.getUriInfo().getPath().endsWith("/vetoed");
        }
    }

    @Provider
    @PreMatching
    @Priority(Priorities.USER + 1)
    public static class Abort implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            if (request.getUriInfo().getPath().endsWith("/aborted")) {
                request.abortWith(Response.status(429).entity("aborted").build());
            }
        }
    }
}
