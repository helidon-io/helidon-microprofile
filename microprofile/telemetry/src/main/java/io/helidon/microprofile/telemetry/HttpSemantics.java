/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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
import java.util.List;
import java.util.Set;

import io.helidon.tracing.Span;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import jakarta.ws.rs.core.Response;

/**
 * Current HTTP emission policy, independent of propagation, span lifetime, and SDK ownership.
 */
final class HttpSemantics {
    static final String SERVER_DURATION_NAME = "http.server.request.duration";
    static final String SERVER_DURATION_DESCRIPTION = "Duration of HTTP server requests";
    static final String SERVER_DURATION_UNIT = "s";
    static final List<Double> SERVER_DURATION_BOUNDARIES = List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25,
                                                                 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT",
                                                      "OPTIONS", "TRACE", "PATCH");
    private static final Set<Response.Status.Family> CLIENT_ERRORS = Set.of(Response.Status.Family.CLIENT_ERROR,
                                                                          Response.Status.Family.SERVER_ERROR);

    private HttpSemantics() {
    }

    static String method(String method) {
        return METHODS.contains(method) ? method : "_OTHER";
    }

    static String clientSpanName(String method) {
        return spanMethod(method);
    }

    static String serverSpanName(String method, String route) {
        return spanMethod(method) + " " + route;
    }

    static void clientRequest(Span.Builder<?> builder, String method, URI uri) {
        builder.tag(HttpAttributes.HTTP_REQUEST_METHOD.getKey(), method)
                .tag(UrlAttributes.URL_SCHEME.getKey(), uri.getScheme())
                .tag(UrlAttributes.URL_FULL.getKey(), uri.toString())
                .tag(ServerAttributes.SERVER_ADDRESS.getKey(), uri.getHost())
                .tag(ServerAttributes.SERVER_PORT.getKey(), uri.getPort());
    }

    static void serverRequest(Span.Builder<?> builder, String method, URI uri, URI baseUri, String route) {
        builder.tag(HttpAttributes.HTTP_REQUEST_METHOD.getKey(), method)
                .tag(UrlAttributes.URL_SCHEME.getKey(), uri.getScheme())
                .tag(UrlAttributes.URL_PATH.getKey(), uri.getPath())
                .tag(HttpAttributes.HTTP_ROUTE.getKey(), route)
                .tag(ServerAttributes.SERVER_ADDRESS.getKey(), baseUri.getHost())
                .tag(ServerAttributes.SERVER_PORT.getKey(), baseUri.getPort());
    }

    static void serverQuery(Span.Builder<?> builder, URI uri) {
        String query = uri.getRawQuery();
        if (query != null) {
            builder.tag(UrlAttributes.URL_QUERY.getKey(), query);
        }
    }

    static void originalMethod(Span.Builder<?> builder, String method, String original) {
        if (method.equals("_OTHER")) {
            builder.tag(HttpAttributes.HTTP_REQUEST_METHOD_ORIGINAL.getKey(), original);
        }
    }

    static void clientResponse(Span span, int status, Response.Status.Family family) {
        span.tag(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey(), status);
        if (CLIENT_ERRORS.contains(family)) {
            span.status(Span.Status.ERROR);
            span.tag(ErrorAttributes.ERROR_TYPE.getKey(), Integer.toString(status));
        }
    }

    static void serverResponse(Span span, int status, Response.Status.Family family) {
        span.tag(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey(), status);
        if (family == Response.Status.Family.SERVER_ERROR) {
            span.status(Span.Status.ERROR);
            span.tag(ErrorAttributes.ERROR_TYPE.getKey(), Integer.toString(status));
        }
    }

    static AttributesBuilder serverDurationAttributes(String method, String scheme, int status,
                                                      String route, Throwable failure) {
        var attributes = Attributes.builder()
                .put(HttpAttributes.HTTP_REQUEST_METHOD, method)
                .put(UrlAttributes.URL_SCHEME, scheme)
                .put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, status);
        if (route != null) {
            attributes.put(HttpAttributes.HTTP_ROUTE, route);
        }
        // A transport failure can occur even after a successful response status has been selected.
        if (failure != null) {
            attributes.put(ErrorAttributes.ERROR_TYPE, failure.getClass().getName());
        } else if (status >= 500) {
            attributes.put(ErrorAttributes.ERROR_TYPE, Integer.toString(status));
        }
        return attributes;
    }

    private static String spanMethod(String method) {
        return method.equals("_OTHER") ? "HTTP" : method;
    }
}
