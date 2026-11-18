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

import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.client.spi.PostInvocationInterceptor;
import org.glassfish.jersey.client.spi.PreInvocationInterceptor;

/**
 * Adapts Jersey client invocation, request, response and failure callbacks to HTTP client telemetry.
 * Tracing and propagation are provided by {@link HttpClientTracing}; no per-request state is retained here.
 * <p>
 * The pre-invocation hook captures context before asynchronous scheduling. Response filtering updates
 * span metadata; the later invocation success or failure hook ends the span.
 */
@Provider
@ConstrainedTo(RuntimeType.CLIENT)
class HttpClientTelemetryFilter implements ClientRequestFilter, ClientResponseFilter,
                                           PreInvocationInterceptor, PostInvocationInterceptor {
    private final HttpClientTracing tracing;

    @Inject
    HttpClientTelemetryFilter(HttpClientTracing tracing) {
        this.tracing = tracing;
    }

    @Override
    public void beforeRequest(ClientRequestContext request) {
        tracing.captureParent(request);
    }

    @Override
    public void filter(ClientRequestContext request) {
        tracing.request(request);
    }

    @Override
    public void filter(ClientRequestContext request, ClientResponseContext response) {
        tracing.response(request, response);
    }

    @Override
    public void afterRequest(ClientRequestContext request, ClientResponseContext response) {
        tracing.complete(request, null);
    }

    @Override
    public void onException(ClientRequestContext request, ExceptionContext exception) {
        tracing.complete(request, exception.getThrowables().peekFirst());
    }
}
