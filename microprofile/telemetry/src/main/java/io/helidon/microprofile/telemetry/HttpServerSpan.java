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

import io.helidon.tracing.Scope;
import io.helidon.tracing.Span;

import io.opentelemetry.context.Context;

/**
 * Keeps the server span alive through send, with thread-confined activations for Jersey processing phases.
 * Each phase's terminal event runs on the thread that entered that phase, including async resumption.
 */
final class HttpServerSpan {
    static final String PROPERTY = HttpServerSpan.class.getName();
    private static final Runnable NO_OP = () -> { };
    // Clear values as phases exit. Idle slots retain no request or deployment objects.
    private static final ThreadLocal<Activation> ACTIVATIONS = new ThreadLocal<>();

    private final SpanLifecycle completion;
    private final HttpServerObservation observation;
    private final Context propagated;

    HttpServerSpan(Span span, TelemetrySdk.Lease lease, HttpServerObservation observation) {
        this.completion = new SpanLifecycle(span, lease, true);
        this.observation = observation;
        this.propagated = null;
    }

    HttpServerSpan(Context propagated) {
        this.completion = null;
        this.observation = null;
        this.propagated = propagated;
    }

    boolean tracing() {
        return completion != null;
    }

    Span span() {
        return completion.span();
    }

    void activate(Phase phase) {
        Scope scope = completion == null ? new PropagationScope(propagated.makeCurrent())
                : phase == Phase.METHOD || phase == Phase.REQUEST_FILTERS ? completion.activate() : activateResponse();
        if (scope != null) {
            ACTIVATIONS.set(new Activation(this, phase, scope, ACTIVATIONS.get()));
        }
    }

    Scope activateResponse() {
        if (completion == null) {
            return new PropagationScope(propagated.makeCurrent());
        }
        var method = observation == null ? null : observation.methodSpan();
        return method == null ? completion.activate() : method.activate();
    }

    void returned(Throwable failure) {
        if (completion != null) {
            completion.returned(true, failure);
        }
    }

    void complete(Throwable failure) {
        if (completion != null) {
            completion.responseCompleted(failure, NO_OP);
        }
    }

    void completeResponse(Throwable failure) {
        if (observation == null) {
            complete(failure);
        } else {
            observation.complete(failure);
        }
    }

    void failed(Throwable failure) {
        if (completion != null) {
            completion.failed(failure);
        }
        var method = observation == null ? null : observation.methodSpan();
        if (method != null) {
            method.failed(failure);
        }
    }

    void deactivate(Phase phase) {
        var current = ACTIVATIONS.get();
        if (current != null && current.owner() == this && current.phase() == phase) {
            ACTIVATIONS.set(current.previous());
            current.scope().close();
        }
    }

    enum Phase {
        REQUEST_FILTERS, METHOD, RESPONSE_FILTERS, MAPPING
    }

    private static final class PropagationScope implements Scope {
        private final io.opentelemetry.context.Scope scope;
        private boolean closed;

        private PropagationScope(io.opentelemetry.context.Scope scope) {
            this.scope = scope;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                scope.close();
            }
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private record Activation(HttpServerSpan owner, Phase phase, Scope scope, Activation previous) {
    }
}
