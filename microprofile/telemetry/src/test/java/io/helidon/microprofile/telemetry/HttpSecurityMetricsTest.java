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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.helidon.common.testing.junit5.MatcherWithRetry;
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
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@HelidonTest
@AddBean(TestSpanExporter.class)
@AddBean(HttpSecurityMetricsTest.Authentication.class)
@AddBean(HttpSecurityMetricsTest.Resource.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "in-memory")
@AddConfig(key = "otel.metrics.exporter", value = "sdk-test")
class HttpSecurityMetricsTest {
    @Inject
    WebTarget target;
    @Inject
    TelemetrySdk sdk;
    @Inject
    TestSpanExporter exporter;
    @Inject
    Authentication authentication;

    @Test
    void measuresPrematchingAuthenticationAbortAndFailureExactlyOnce() {
        SdkTestExporters.METRICS.clear();
        try (var response = target.path("security-metrics/unauthorized").request().get()) {
            response.readEntity(String.class);
            assertThat(response.getStatus(), is(401));
        }
        try (var response = target.path("security-metrics/forbidden").request().get()) {
            response.readEntity(String.class);
            assertThat(response.getStatus(), is(403));
        }
        var points = MatcherWithRetry.assertThatWithRetry("HTTP duration measurements include authentication rejections", () -> {
            ((OpenTelemetrySdk) sdk.telemetry()).getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            return SdkTestExporters.METRICS.stream()
                    .filter(metric -> metric.getName().equals("http.server.request.duration"))
                    .reduce((_, latest) -> latest)
                    .map(metric -> metric.getHistogramData().getPoints().stream()
                            .filter(point -> point.getAttributes().get(HttpAttributes.HTTP_ROUTE) == null).toList())
                    .orElse(List.of());
        }, hasSize(2), 20, 100);
        assertThat(points.stream().mapToLong(point -> point.getCount()).sum(), is(2L));
        assertThat(points.stream().map(point -> point.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE))
                .collect(Collectors.toSet()), is(Set.of(401L, 403L)));
        assertThat(points.stream().anyMatch(point -> point.getAttributes().get(HttpAttributes.HTTP_ROUTE) != null), is(false));
    }

    @Test
    void successfulAuthenticationHasMatchingSpanAndMetricDuration() {
        exporter.clear();
        try (var response = target.path("security-metrics/success").request().get()) {
            assertThat(response.readEntity(String.class), is("authenticated"));
            assertThat(response.getStatus(), is(200));
        }
        var span = exporter.spanData(2).stream().filter(data -> data.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        var started = authentication.startTime();
        assertThat("The span starts at the observation boundary seen by the authentication filter", span.getStartEpochNanos(),
                   is(TimeUnit.SECONDS.toNanos(started.getEpochSecond()) + started.getNano()));
        var points = MatcherWithRetry.assertThatWithRetry("Successful request duration is recorded", () -> {
            ((OpenTelemetrySdk) sdk.telemetry()).getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            return SdkTestExporters.METRICS.stream()
                    .filter(metric -> metric.getName().equals("http.server.request.duration"))
                    .reduce((_, latest) -> latest)
                    .map(metric -> metric.getHistogramData().getPoints().stream()
                            .filter(point -> point.getAttributes().get(HttpAttributes.HTTP_ROUTE) != null).toList())
                    .orElse(List.of());
        }, hasSize(1), 20, 100);
        var point = points.getFirst();
        assertThat(point.getCount(), is(1L));
        assertThat(point.getSum(), is((span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000_000.0));
    }

    @ApplicationScoped
    @Path("security-metrics/{result}")
    public static class Resource {
        @GET
        public String authenticated() {
            return "authenticated";
        }
    }

    @ApplicationScoped
    @Provider
    @PreMatching
    @Priority(Priorities.AUTHENTICATION)
    public static class Authentication implements ContainerRequestFilter {
        private volatile Instant started;

        Instant startTime() {
            return started;
        }

        @Override
        public void filter(ContainerRequestContext request) {
            if (request.getUriInfo().getPath().endsWith("/success")) {
                started = HttpServerObservation.from(request.getProperty(HttpServerObservation.PROPERTY)).startTime();
            } else if (request.getUriInfo().getPath().endsWith("/unauthorized")) {
                request.abortWith(Response.status(401).build());
            } else if (request.getUriInfo().getPath().endsWith("/forbidden")) {
                throw new ForbiddenException();
            }
        }
    }
}
