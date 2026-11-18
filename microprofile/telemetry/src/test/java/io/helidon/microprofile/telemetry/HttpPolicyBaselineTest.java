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

import java.net.URI;
import java.util.Map;
import java.util.Set;

import io.helidon.tracing.Span;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/** Characterizes existing emission; these assertions do not define full OTel conformance. */
class HttpPolicyBaselineTest {
    private final TestSpanExporter exporter = new TestSpanExporter();
    private OpenTelemetrySdk sdk;
    private Tracer tracer;

    @BeforeEach
    void initialize() {
        sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
        tracer = HelidonOpenTelemetry.create(sdk, sdk.getTracer("policy-baseline"), Map.of());
    }

    @AfterEach
    void close() {
        sdk.close();
    }

    @Test
    void preservesExistingKnownMethods() {
        for (String method : Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH")) {
            assertThat(HttpSemantics.method(method), is(method));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BREW", "get", "QUERY"})
    void preservesUnknownMethodNamesAndOriginalAttribute(String original) {
        String method = HttpSemantics.method(original);
        var builder = tracer.spanBuilder(HttpSemantics.clientSpanName(method)).kind(Span.Kind.CLIENT);
        HttpSemantics.clientRequest(builder, method, URI.create("https://example.test/"));
        HttpSemantics.originalMethod(builder, method, original);
        builder.start().end();
        var server = tracer.spanBuilder(HttpSemantics.serverSpanName(method, "/route")).kind(Span.Kind.SERVER);
        HttpSemantics.serverRequest(server, method, URI.create("https://example.test/route"),
                                    URI.create("https://example.test/"), "/route");
        HttpSemantics.originalMethod(server, method, original);
        server.start().end();
        var spans = exporter.spanData(2);
        assertThat(spans.getFirst().getName(), is("_OTHER"));
        assertThat(spans.getLast().getName(), is("_OTHER /route"));
        for (var span : spans) {
            assertThat(span.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD), is("_OTHER"));
            assertThat(span.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD_ORIGINAL), is(original));
        }
    }

    @Test
    void preservesClientUrlAndImplicitPort() {
        var uri = URI.create("https://synthetic:example@example.test/a%2Fb?sig=synthetic&x=a%2Fb");
        var builder = tracer.spanBuilder(HttpSemantics.clientSpanName("GET")).kind(Span.Kind.CLIENT);
        HttpSemantics.clientRequest(builder, "GET", uri);
        HttpSemantics.originalMethod(builder, "GET", "GET");
        var span = builder.start();
        HttpSemantics.clientResponse(span, 200, Response.Status.Family.SUCCESSFUL);
        span.end();
        var data = exporter.spanData(1).getFirst();
        assertThat(data.getName(), is("GET"));
        assertThat(data.getAttributes().get(UrlAttributes.URL_FULL), is(uri.toString()));
        assertThat(data.getAttributes().get(ServerAttributes.SERVER_ADDRESS), is("example.test"));
        assertThat(data.getAttributes().get(ServerAttributes.SERVER_PORT), is(-1L));
        assertThat(data.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE), is(200L));
        assertThat(data.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD_ORIGINAL), nullValue());
        assertThat(data.getAttributes().get(HttpAttributes.HTTP_ROUTE), nullValue());
        assertThat(data.getAttributes().get(ErrorAttributes.ERROR_TYPE), nullValue());
    }

    @Test
    void preservesDecodedServerPathRawQueryAndRoute() {
        var uri = URI.create("https://example.test:8443/a%2Fb?sig=synthetic&x=a%2Fb");
        var builder = tracer.spanBuilder(HttpSemantics.serverSpanName("GET", "/a/{id}")).kind(Span.Kind.SERVER);
        HttpSemantics.serverRequest(builder, "GET", uri, URI.create("https://example.test:8443/"), "/a/{id}");
        HttpSemantics.serverQuery(builder, uri);
        builder.start().end();
        var data = exporter.spanData(1).getFirst();
        assertThat(data.getName(), is("GET /a/{id}"));
        assertThat(data.getAttributes().get(UrlAttributes.URL_PATH), is("/a/b"));
        assertThat(data.getAttributes().get(UrlAttributes.URL_QUERY), is("sig=synthetic&x=a%2Fb"));
        assertThat(data.getAttributes().get(HttpAttributes.HTTP_ROUTE), is("/a/{id}"));
        assertThat(data.getAttributes().get(ServerAttributes.SERVER_PORT), is(8443L));
        assertThat(data.getAttributes().get(UrlAttributes.URL_FULL), nullValue());
    }

    @Test
    void omitsAbsentQuery() {
        var builder = tracer.spanBuilder("GET /route").kind(Span.Kind.SERVER);
        HttpSemantics.serverQuery(builder, URI.create("http://example.test/route"));
        builder.start().end();
        assertThat(exporter.spanData(1).getFirst().getAttributes().get(UrlAttributes.URL_QUERY), nullValue());
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 200, 302, 400, 404, 500, 503, 600})
    void preservesDifferentClientServerAndMetricErrorRules(int status) {
        var family = Response.Status.Family.familyOf(status);
        var client = tracer.spanBuilder("GET").kind(Span.Kind.CLIENT).start();
        HttpSemantics.clientResponse(client, status, family);
        client.end();
        var server = tracer.spanBuilder("GET /route").kind(Span.Kind.SERVER).start();
        HttpSemantics.serverResponse(server, status, family);
        server.end();
        var spans = exporter.spanData(2);
        boolean clientError = status >= 400 && status < 600;
        boolean serverError = status >= 500 && status < 600;
        assertThat(spans.getFirst().getStatus().getStatusCode(), is(clientError ? StatusCode.ERROR : StatusCode.UNSET));
        assertThat(spans.getLast().getStatus().getStatusCode(), is(serverError ? StatusCode.ERROR : StatusCode.UNSET));
        assertThat(spans.getFirst().getAttributes().get(ErrorAttributes.ERROR_TYPE),
                   is(clientError ? Integer.toString(status) : null));
        assertThat(spans.getLast().getAttributes().get(ErrorAttributes.ERROR_TYPE),
                   is(serverError ? Integer.toString(status) : null));
        assertThat(spans.getFirst().getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE), is((long) status));
        assertThat(spans.getLast().getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE), is((long) status));
        var metric = HttpSemantics.serverDurationAttributes("GET", "http", status, null).build();
        assertThat(metric.get(ErrorAttributes.ERROR_TYPE), is(status >= 500 ? Integer.toString(status) : null));
        assertThat(metric.get(HttpAttributes.HTTP_ROUTE), nullValue());
    }

    @Test
    void responseClassificationUsesTheSuppliedFamily() {
        var span = tracer.spanBuilder("GET").start();
        HttpSemantics.clientResponse(span, 503, Response.Status.Family.OTHER);
        span.end();
        assertThat(exporter.spanData(1).getFirst().getStatus().getStatusCode(), is(StatusCode.UNSET));
    }
}
