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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.helidon.common.testing.junit5.MatcherWithRetry;
import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.microprofile.telemetry.spi.HelidonTelemetryContainerFilterHelper;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@HelidonTest
@AddBean(HttpMetricsTest.Resource.class)
@AddBean(HttpMetricsTest.ObservationRemovingHelper.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "none")
@AddConfig(key = "otel.metrics.exporter", value = "sdk-test")
class HttpMetricsTest {
    @Inject
    WebTarget target;
    @Inject
    TelemetrySdk sdk;

    @Test
    void recordsSuccessErrorAndUnmatchedRequestsOnce() {
        SdkTestExporters.METRICS.clear();
        check("telemetry-metrics/ok", 200);
        check("telemetry-metrics/error", 500);
        check("telemetry-metrics/missing", 404);
        check("telemetry-metrics/helper", 202);
        Map<Long, HistogramPointData> points = MatcherWithRetry.assertThatWithRetry("HTTP status measurements", () -> {
            ((OpenTelemetrySdk) sdk.telemetry()).getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            return SdkTestExporters.METRICS.stream()
                    .filter(metric -> metric.getName().equals("http.server.request.duration"))
                    .flatMap(metric -> metric.getHistogramData().getPoints().stream())
                    .collect(Collectors.toMap(point -> point.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE),
                                              point -> point, (_, latest) -> latest));
        }, aMapWithSize(4), 20, 100);
        points.values().forEach(point -> assertThat(point.getCount(), is(1L)));
        assertThat(points.get(200L).getAttributes().get(HttpAttributes.HTTP_ROUTE), is("/telemetry-metrics/ok"));
        assertThat(points.get(500L).getAttributes().get(ErrorAttributes.ERROR_TYPE), is("500"));
        assertThat(points.get(404L).getAttributes().get(HttpAttributes.HTTP_ROUTE), nullValue());
        assertThat(points.get(202L).getAttributes().get(HttpAttributes.HTTP_ROUTE), nullValue());
        assertThat(points.get(200L).getAttributes(), is(Attributes.builder()
                .put(HttpAttributes.HTTP_REQUEST_METHOD, "GET").put(UrlAttributes.URL_SCHEME, "http")
                .put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, 200).put(HttpAttributes.HTTP_ROUTE, "/telemetry-metrics/ok")
                .build()));
        assertThat(points.get(404L).getAttributes(), is(Attributes.builder()
                .put(HttpAttributes.HTTP_REQUEST_METHOD, "GET").put(UrlAttributes.URL_SCHEME, "http")
                .put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, 404).build()));
        var metric = SdkTestExporters.METRICS.stream()
                .filter(data -> data.getName().equals("http.server.request.duration")).findFirst().orElseThrow();
        assertThat(metric.getUnit(), is("s"));
        assertThat(metric.getDescription(), is("Duration of HTTP server requests"));
        assertThat(points.get(200L).getBoundaries(), is(List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25,
                                                              0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)));
    }

    private void check(String path, int status) {
        try (Response response = target.path(path).request().get()) {
            response.readEntity(String.class);
            assertThat(response.getStatus(), is(status));
        }
    }

    @ApplicationScoped
    public static class ObservationRemovingHelper implements HelidonTelemetryContainerFilterHelper {
        @Override
        public boolean shouldStartSpan(ContainerRequestContext request) {
            if (request.getUriInfo().getPath().endsWith("telemetry-metrics/helper")) {
                request.removeProperty(HttpServerObservation.PROPERTY);
            }
            return true;
        }
    }

    @ApplicationScoped
    @Path("telemetry-metrics")
    public static class Resource {
        @GET
        @Path("ok")
        public String ok() {
            return "ok";
        }

        @GET
        @Path("error")
        public Response error() {
            return Response.serverError().entity("failed").build();
        }

        @GET
        @Path("helper")
        public Response helper() {
            return Response.accepted("accepted").build();
        }
    }
}
