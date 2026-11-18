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
import java.util.Deque;
import java.util.LinkedList;

import io.helidon.common.context.Contexts;
import io.helidon.tracing.Scope;
import io.helidon.tracing.Span;
import io.helidon.webserver.http.ServerResponse;

import io.opentelemetry.context.Context;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

/**
 * Coordinates server telemetry across Jersey phases, preserving one shared request observation.
 * Thin adapters supply request, response, and writer callbacks. This component installs terminal
 * hooks and coordinates SDK admission, resource metadata and signal processors; {@link HttpServerObservation}
 * and {@link SpanLifecycle} retain their separate completion and span-activation ownership.
 * <p>
 * This shared listener and its processors carry no mutable per-request state. Request properties and
 * the existing lifecycle objects carry that state, including asynchronous response processing.
 */
@Singleton
final class HttpServerTelemetry implements RequestEventListener {
    private final TelemetrySdk sdk;
    private final HttpServerMetrics metrics;
    private final HttpServerTracing tracing;
    private final TelemetryRequest telemetryRequest;

    @Inject
    HttpServerTelemetry(TelemetrySdk sdk, HttpServerMetrics metrics, HttpServerTracing tracing,
                        TelemetryRequest telemetryRequest) {
        this.sdk = sdk;
        this.metrics = metrics;
        this.tracing = tracing;
        this.telemetryRequest = telemetryRequest;
    }

    RequestEventListener listener(ContainerRequestContext request) {
        return sdk.instrumentationEnabled() || sdk.propagationRequired(request.getHeaders()) ? this : null;
    }

    void beforeMatching(ContainerRequestContext request, ServerResponse response) {
        var lease = sdk.acquire();
        if (lease == null) {
            return;
        }
        long started = System.nanoTime();
        try {
            String method = HttpSemantics.method(request.getMethod());
            String scheme = request.getUriInfo().getRequestUri().getScheme();
            var observation = new HttpServerObservation(lease, metrics.measurement(started, method, scheme));
            request.setProperty(HttpServerObservation.PROPERTY, observation);
            var jerseyRequest = (ContainerRequest) request;
            jerseyRequest.setWriter(new TelemetryResponseWriter(jerseyRequest.getResponseWriter(), observation));
            observation.observeResponse(response);
        } catch (RuntimeException | Error e) {
            lease.close();
            throw e;
        }
    }

    void matched(ContainerRequestContext request, ResourceInfo resourceInfo, ServerResponse response) {
        if (!sdk.instrumentationEnabled()) {
            if (sdk.propagationRequired(request.getHeaders())) {
                Context extracted = tracing.extract(request);
                if (extracted != Context.root()) {
                    var propagation = new HttpServerSpan(extracted);
                    request.setProperty(HttpServerSpan.PROPERTY, propagation);
                    propagation.activate(HttpServerSpan.Phase.REQUEST_FILTERS);
                }
            }
            return;
        }
        var ownerObservation = HttpServerObservation.from(request.getProperty(HttpServerObservation.PROPERTY));
        telemetryRequest.initialize(resourceInfo.getResourceMethod(), ownerObservation);
        if (!tracing.shouldStartSpan(request)) {
            return;
        }
        var lease = sdk.acquire();
        if (lease == null) {
            return;
        }
        Span span = null;
        HttpServerSpan lifecycle = null;
        Throwable failure = null;
        try {
            String route = route(request);
            // Helpers may mutate properties; retain the original owner, but resolve the route recipient again.
            var observation = HttpServerObservation.from(request.getProperty(HttpServerObservation.PROPERTY));
            if (observation != null) {
                observation.route(route);
            }
            span = tracing.start(request, route);
            lifecycle = new HttpServerSpan(span, lease, ownerObservation);
            request.setProperty(HttpServerSpan.PROPERTY, lifecycle);
            HttpServerSpan completion = lifecycle;
            if (ownerObservation == null) {
                response.whenSent(() -> completion.complete(null));
            } else {
                ownerObservation.endSpan(() -> completion.complete(ownerObservation.failure()));
            }
            lifecycle.activate(HttpServerSpan.Phase.REQUEST_FILTERS);
        } catch (RuntimeException | Error e) {
            failure = e;
            if (lifecycle == null) {
                try {
                    if (span != null) {
                        span.end(e);
                    }
                } finally {
                    lease.close();
                }
            } else {
                lifecycle.complete(e);
            }
            throw e;
        } finally {
            if (lifecycle != null) {
                lifecycle.returned(failure);
            }
        }
    }

    void response(ContainerRequestContext request, ContainerResponseContext response) {
        if (request.getProperty(HttpServerSpan.PROPERTY) instanceof HttpServerSpan span && span.tracing()) {
            tracing.response(span.span(), response);
        }
    }

    void write(WriterInterceptorContext context) throws IOException {
        var observation = HttpServerObservation.from(context.getProperty(HttpServerObservation.PROPERTY));
        SpanLifecycle span = observation == null ? null : observation.methodSpan();
        var serverSpan = context.getProperty(HttpServerSpan.PROPERTY) instanceof HttpServerSpan value ? value : null;
        if (span == null && serverSpan == null) {
            context.proceed();
            return;
        }
        try (Scope _ = serverSpan == null ? span.activate() : serverSpan.activateResponse()) {
            try {
                context.proceed();
            } catch (IOException | RuntimeException | Error e) {
                // Record failure before releasing the activation, even if a send notification raced with this write.
                if (serverSpan != null) {
                    serverSpan.failed(e);
                } else {
                    span.failed(e);
                }
                throw e;
            }
        }
    }

    @Override
    public void onEvent(RequestEvent event) {
        var request = event.getContainerRequest();
        var serverSpan = request.getProperty(HttpServerSpan.PROPERTY) instanceof HttpServerSpan value ? value : null;
        if (event.getType() == RequestEvent.Type.ON_EXCEPTION) {
            var observation = HttpServerObservation.from(request.getProperty(HttpServerObservation.PROPERTY));
            var method = observation == null ? null : observation.methodSpan();
            if (method != null) {
                method.failed(event.getException());
            }
        }
        if (serverSpan == null) {
            return;
        }
        switch (event.getType()) {
            case REQUEST_FILTERED -> serverSpan.deactivate(HttpServerSpan.Phase.REQUEST_FILTERS);
            case RESOURCE_METHOD_START -> serverSpan.activate(HttpServerSpan.Phase.METHOD);
            case RESOURCE_METHOD_FINISHED -> serverSpan.deactivate(HttpServerSpan.Phase.METHOD);
            case RESP_FILTERS_START -> serverSpan.activate(HttpServerSpan.Phase.RESPONSE_FILTERS);
            case RESP_FILTERS_FINISHED -> serverSpan.deactivate(HttpServerSpan.Phase.RESPONSE_FILTERS);
            case EXCEPTION_MAPPER_FOUND -> serverSpan.activate(HttpServerSpan.Phase.MAPPING);
            case EXCEPTION_MAPPING_FINISHED -> serverSpan.deactivate(HttpServerSpan.Phase.MAPPING);
            case ON_EXCEPTION -> serverSpan.deactivate(HttpServerSpan.Phase.MAPPING);
            case FINISHED -> {
                serverSpan.deactivate(HttpServerSpan.Phase.MAPPING);
                if (!event.isResponseWritten()) {
                    serverSpan.completeResponse(event.getException());
                }
            }
            default -> { }
        }
    }

    private static Class<?> getRealClass(Class<?> object) {
        Class<?> result = object;
        while (result.isSynthetic()) {
            result = result.getSuperclass();
        }
        return result;
    }

    private String route(ContainerRequestContext requestContext) {
        ExtendedUriInfo extendedUriInfo = (ExtendedUriInfo) requestContext.getUriInfo();

        // Derive the original path (including path parameters) of the matched resource from the bottom up.
        Deque<String> derivedPath = new LinkedList<>();

        Resource resource = extendedUriInfo.getMatchedModelResource();
        while (resource != null) {
            String resourcePath = resource.getPath();
            if (resourcePath != null && !resourcePath.equals("/") && !resourcePath.isBlank()) {
                derivedPath.push(resourcePath);
                if (!resourcePath.startsWith("/")) {
                    derivedPath.push("/");
                }
            }
            resource = resource.getParent();
        }

        derivedPath.push(applicationPath());
        return String.join("", derivedPath);
    }

    private String applicationPath() {
        Application app = Contexts.context()
                .flatMap(it -> it.get(Application.class))
                .orElseThrow(() -> new IllegalStateException("Application missing from context"));

        if (app == null) {
            return "";
        }
        ApplicationPath applicationPath = getRealClass(app.getClass()).getAnnotation(ApplicationPath.class);
        return (applicationPath == null || applicationPath.value().equals("/")) ? "" : applicationPath.value();
    }
}
