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
import java.util.Optional;

import io.helidon.microprofile.telemetry.spi.HelidonTelemetryContainerFilterHelper;
import io.helidon.tracing.Span;
import io.helidon.tracing.SpanContext;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.trace.ReadableSpan;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Applies incoming HTTP propagation, helper decisions, and server-span emission policy.
 * Shared by the server coordinator; per-request span ownership remains in {@link HttpServerSpan}.
 */
@Singleton
final class HttpServerTracing {
    private static final System.Logger LOGGER = System.getLogger(HttpServerTracing.class.getName());
    private static final String HELPER_START_SPAN_PROPERTY = HelidonTelemetryContainerFilterHelper.class + ".startSpan";
    private static final TextMapGetter<MultivaluedMap<String, String>> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(MultivaluedMap<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(MultivaluedMap<String, String> carrier, String key) {
            List<String> values = carrier.get(key);
            if (values == null) {
                return null;
            }
            return values.size() == 1 ? values.getFirst() : String.join(",", values);
        }
    };

    private final Tracer helidonTracer;
    private final List<HelidonTelemetryContainerFilterHelper> helpers;
    private final TextMapPropagator propagator;

    @Inject
    HttpServerTracing(Tracer helidonTracer, Instance<HelidonTelemetryContainerFilterHelper> helpers, TelemetrySdk sdk) {
        this.helidonTracer = helidonTracer;
        this.helpers = helpers.stream().toList();
        this.propagator = sdk.telemetry().getPropagators().getTextMapPropagator();
    }

    boolean shouldStartSpan(ContainerRequestContext request) {
        boolean start = true;
        for (var helper : helpers) {
            if (!helper.shouldStartSpan(request)) {
                start = false;
                break;
            }
        }
        request.setProperty(HELPER_START_SPAN_PROPERTY, start);
        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(System.Logger.Level.TRACE, start ? "Starting Span in a Container Request"
                    : "Container filter helper(s) voted to not start a span for " + request);
        }
        return start;
    }

    Context extract(ContainerRequestContext request) {
        return propagator.extract(Context.root(), request.getHeaders(), HEADER_GETTER);
    }

    Span start(ContainerRequestContext request, String route, Instant started) {
        Optional<SpanContext> parent = parentSpanContext(request);
        String method = HttpSemantics.method(request.getMethod());
        var spanBuilder = helidonTracer.spanBuilder(HttpSemantics.serverSpanName(method, route))
                .kind(Span.Kind.SERVER)
                .update(builder -> HttpSemantics.serverRequest(builder, method, request.getUriInfo().getRequestUri(),
                                                               request.getUriInfo().getBaseUri(), route))
                .update(builder -> {
                    parent.ifPresent(builder::parent);
                    HttpSemantics.serverQuery(builder, request.getUriInfo().getRequestUri());
                    HttpSemantics.originalMethod(builder, method, request.getMethod());
                });
        // Matching decides whether to create a span, but its interval includes pre-matching work.
        return started == null ? spanBuilder.start() : spanBuilder.start(started);
    }

    void response(Span span, ContainerResponseContext response) {
        HttpSemantics.serverResponse(span, response.getStatus(), response.getStatusInfo().getFamily());
    }

    private Optional<SpanContext> parentSpanContext(ContainerRequestContext requestContext) {
        // Each incoming request starts from its remote context, never an unrelated thread's current span.
        Context extracted = propagator.extract(Context.root(), requestContext.getHeaders(), HEADER_GETTER);
        var remote = io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext();
        var local = io.opentelemetry.api.trace.Span.current();
        // Non-recording spans can still be active parents, including unsampled ingress spans.
        // Reject a span only when its implementation exposes that it has already ended.
        boolean eligibleParent = local.getSpanContext().isValid()
                && (local.isRecording() || !(local instanceof ReadableSpan readable) || !readable.hasEnded());
        if (eligibleParent && (!remote.isValid() || local.getSpanContext().getTraceId().equals(remote.getTraceId()))) {
            return Optional.of(HelidonOpenTelemetry.create(Context.current()));
        }
        return Optional.of(HelidonOpenTelemetry.create(extracted));
    }

}
