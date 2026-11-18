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

import java.util.List;
import java.util.Optional;

import io.helidon.microprofile.telemetry.spi.HelidonTelemetryClientFilterHelper;
import io.helidon.tracing.HeaderConsumer;
import io.helidon.tracing.HeaderProvider;
import io.helidon.tracing.Scope;
import io.helidon.tracing.Span;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Implements tracing and propagation for outgoing HTTP requests.
 * A shared instance retains deployment dependencies only; request properties and {@link SpanLifecycle}
 * carry per-invocation state, including the submitting thread's captured parent context.
 * <p>
 * Span activation is confined to request setup. Response filtering updates span metadata,
 * and the invocation's success or failure notification completes the span and releases its SDK lease.
 */
@Singleton
final class HttpClientTracing {
    // Preserve request-property names independently of implementation class names.
    private static final String SPAN = "io.helidon.microprofile.telemetry.HelidonTelemetryClientFilter.span";
    private static final String PARENT_CONTEXT = "io.helidon.microprofile.telemetry.HelidonTelemetryClientFilter.parent";
    private static final Runnable NO_OP = () -> { };
    private static final TextMapSetter<MultivaluedMap<String, Object>> HEADER_SETTER = MultivaluedMap::putSingle;
    private static final String HELPER_START_SPAN_PROPERTY = HelidonTelemetryClientFilterHelper.class.getName() + ".startSpan";

    private final Tracer helidonTracer;
    private final TelemetrySdk sdk;
    private final List<HelidonTelemetryClientFilterHelper> helpers;

    @Inject
    HttpClientTracing(Tracer helidonTracer, Instance<HelidonTelemetryClientFilterHelper> helpersInstance, TelemetrySdk sdk) {
        this.helidonTracer = helidonTracer;
        this.sdk = sdk;
        helpers = helpersInstance.stream().toList();
    }

    void captureParent(ClientRequestContext request) {
        if (sdk.propagationEnabled()) {
            // Jersey invokes this on the submitting thread, before an asynchronous request is scheduled.
            Context context = Context.current();
            if (context != Context.root()) {
                request.setProperty(PARENT_CONTEXT, context);
            }
        }
    }

    void request(ClientRequestContext request) {
        if (!sdk.instrumentationEnabled()) {
            if (sdk.propagationEnabled()) {
                Context parent = parent(request);
                if (parent != Context.root()) {
                    sdk.propagator().inject(parent, request.getHeaders(), HEADER_SETTER);
                }
            }
            return;
        }
        for (var helper : helpers) {
            if (!helper.shouldStartSpan(request)) {
                request.setProperty(HELPER_START_SPAN_PROPERTY, false);
                return;
            }
        }
        var lease = sdk.acquire();
        if (lease == null) {
            return;
        }
        Span span = null;
        SpanLifecycle completion = null;
        Context parent = parent(request);
        try (var _ = parent.makeCurrent()) {
            String method = HttpSemantics.method(request.getMethod());
            span = helidonTracer.spanBuilder(HttpSemantics.clientSpanName(method))
                    .kind(Span.Kind.CLIENT)
                    .parent(HelidonOpenTelemetry.create(parent))
                    .update(builder -> HttpSemantics.clientRequest(builder, method, request.getUri()))
                    .update(builder -> HttpSemantics.originalMethod(builder, method, request.getMethod()))
                    .start();
            completion = new SpanLifecycle(span, lease, true);
            try {
                request.setProperty(SPAN, completion);
                try (Scope _ = completion.activate()) {
                    helidonTracer.inject(span.context(), HeaderProvider.empty(),
                                         new RequestContextHeaderInjector(request.getHeaders()));
                }
            } catch (RuntimeException | Error failure) {
                completion.responseCompleted(failure, NO_OP);
                throw failure;
            } finally {
                completion.returned(true, null);
            }
        } catch (RuntimeException | Error failure) {
            if (completion == null) {
                try {
                    if (span != null) {
                        span.end(failure);
                    }
                } finally {
                    lease.close();
                }
            }
            throw failure;
        }
    }

    void response(ClientRequestContext request, ClientResponseContext response) {
        if (request.getProperty(SPAN) instanceof SpanLifecycle completion) {
            HttpSemantics.clientResponse(completion.span(), response.getStatus(), response.getStatusInfo().getFamily());
        }
    }

    void complete(ClientRequestContext request, Throwable failure) {
        try {
            if (request.getProperty(SPAN) instanceof SpanLifecycle completion) {
                completion.responseCompleted(failure, NO_OP);
            }
        } finally {
            request.removeProperty(SPAN);
            request.removeProperty(PARENT_CONTEXT);
            request.removeProperty(HELPER_START_SPAN_PROPERTY);
        }
    }

    private static Context parent(ClientRequestContext request) {
        Context current = Context.current();
        if (io.opentelemetry.api.trace.Span.fromContext(current).getSpanContext().isValid()) {
            return current;
        }
        return request.getProperty(PARENT_CONTEXT) instanceof Context captured ? captured : current;
    }

    private static class RequestContextHeaderInjector implements HeaderConsumer {

        private final MultivaluedMap<String, Object> requestHeaders;

        private RequestContextHeaderInjector(MultivaluedMap<String, Object> headers) {
            requestHeaders = headers;
        }

        @Override
        public void setIfAbsent(String key, String... values) {
            requestHeaders.computeIfAbsent(key, k -> List.of(values));
        }

        @Override
        public void set(String key, String... values) {
            requestHeaders.put(key, List.of(values));
        }

        @Override
        public Iterable<String> keys() {
            return requestHeaders.keySet();
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable((String) requestHeaders.getFirst(key));
        }

        @Override
        public Iterable<String> getAll(String key) {
            return requestHeaders.get(key).stream().map(o -> (String) o).toList();
        }

        @Override
        public boolean contains(String key) {
            return requestHeaders.containsKey(key);
        }
    }
}
