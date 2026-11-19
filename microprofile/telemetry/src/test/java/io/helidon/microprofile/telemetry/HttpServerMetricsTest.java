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

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpServerMetricsTest {
    private TelemetrySdk sdk;

    @BeforeEach
    void initialize() {
        SdkTestExporters.METRICS.clear();
        sdk = TelemetryTestSupport.owner(Map.of("otel.metrics.exporter", "sdk-test"));
        sdk.initialize();
    }

    @AfterEach
    void close() {
        sdk.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "failure", "before-headers", "header-write", "prepared-only"})
    void transportFailureReportsOnlyASentStatusAndIsMeasuredOnce(String notification) {
        var failure = new IllegalStateException("Synthetic transport failure with private details");
        var observation = new HttpServerObservation(sdk.acquire(),
                new HttpServerMetrics(sdk).measurement(System.nanoTime(), "GET", "http"));
        var sentBytes = new AtomicLong();
        ServerResponse response = (ServerResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ServerResponse.class}, (proxy, method, _) -> switch (method.getName()) {
                    case "whenSent" -> proxy;
                    case "status" -> Status.OK_200;
                    case "isSent" -> false;
                    case "bytesWritten" -> sentBytes.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        observation.observeResponse(response);
        var delegate = (ContainerResponseWriter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ContainerResponseWriter.class}, (_, method, _) -> {
                    if (method.getName().equals("writeResponseStatusAndHeaders")) {
                        if (notification.equals("header-write")) {
                            throw failure;
                        }
                        return OutputStream.nullOutputStream();
                    }
                    if (method.getName().equals("commit")) {
                        throw failure;
                    }
                    if (method.getName().equals("failure")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var writer = new TelemetryResponseWriter(delegate, observation);
        try {
            if (notification.equals("header-write")) {
                assertThat(assertThrows(IllegalStateException.class,
                        () -> writer.writeResponseStatusAndHeaders(0, okResponse())),
                           sameInstance(failure));
                writer.failure(failure);
            } else {
                if (!notification.equals("before-headers")) {
                    writer.writeResponseStatusAndHeaders(0, okResponse());
                }
                if (notification.equals("commit") || notification.equals("failure")) {
                    sentBytes.set(128);
                }
                if (notification.equals("commit")) {
                    assertThat(assertThrows(IllegalStateException.class, writer::commit), sameInstance(failure));
                } else {
                    writer.failure(failure);
                }
            }
        } finally {
            observation.complete(null);
        }
        ((OpenTelemetrySdk) sdk.telemetry()).getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
        var duration = SdkTestExporters.METRICS.stream()
                .filter(metric -> metric.getName().equals("http.server.request.duration"))
                .reduce((_, latest) -> latest).orElseThrow();
        var points = duration.getHistogramData().getPoints();
        assertThat(points.size(), is(1));
        var point = points.iterator().next();
        assertThat(point.getCount(), is(1L));
        if (notification.equals("before-headers") || notification.equals("header-write")
                || notification.equals("prepared-only")) {
            assertThat("An unsent response status is absent", point.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE),
                       nullValue());
        } else {
            assertThat(point.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE), is(200L));
        }
        assertThat(point.getAttributes().get(ErrorAttributes.ERROR_TYPE), is(IllegalStateException.class.getName()));
    }

    private ContainerResponse okResponse() {
        var request = new ContainerRequest(URI.create("http://example.test/"), URI.create("http://example.test/route"),
                                           "GET", null, new MapPropertiesDelegate());
        return new ContainerResponse(request, Response.ok().build());
    }
}
