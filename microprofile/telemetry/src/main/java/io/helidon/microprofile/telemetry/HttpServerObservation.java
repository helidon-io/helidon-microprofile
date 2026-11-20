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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import io.helidon.tracing.Span;
import io.helidon.webserver.http.ServerResponse;

/**
 * Owns shared HTTP completion independently of tracing and metric emission policy.
 * OPEN accepts the first completion notification; COMPLETING admits no second completion and
 * retains the SDK lease while a deferred method span drains. RELEASED means the end action
 * has been attempted and the lease has been released. A shared monotonic interval supplies both
 * the measurement and the server span's timestamps, independently of deferred draining and export.
 * Callback failures propagate after the existing cleanup.
 */
final class HttpServerObservation {
    // Preserve the property name used before this component was extracted from the metrics filter.
    static final String PROPERTY = "io.helidon.microprofile.telemetry.HttpMetricsFilter";

    private final ReentrantLock lock = new ReentrantLock();
    private final TelemetrySdk.Lease lease;
    private final Consumer<HttpServerObservation> measure;
    // Anchor the monotonic duration to epoch time once, without allowing wall-clock adjustments to skew it.
    private final Instant started = Instant.now();
    private final long startedNanos = System.nanoTime();
    private volatile SpanLifecycle methodSpan;
    private volatile String route;
    private volatile ServerResponse response;
    private volatile Throwable failure;
    private volatile Instant ended;
    private volatile long elapsedNanos;
    private Runnable endSpan;
    private boolean endReady;
    private State state = State.OPEN;

    HttpServerObservation(TelemetrySdk.Lease lease, Consumer<HttpServerObservation> measure) {
        this.lease = lease;
        this.measure = measure;
    }

    static HttpServerObservation from(Object property) {
        return property instanceof HttpServerObservation observation ? observation : null;
    }

    Instant startTime() {
        return started;
    }

    Instant endTime() {
        return ended;
    }

    double durationSeconds() {
        return elapsedNanos / 1_000_000_000.0;
    }

    void observeResponse(ServerResponse response) {
        // Retain the concrete fluent return value, not a request-scoped proxy, for deferred completion.
        this.response = response.whenSent(() -> complete(null));
    }

    int status() {
        // A selected status is not evidence that a response was written. Include it only after
        // transport output began, or after a complete response (including an empty response) was sent.
        return response != null && (response.isSent() || response.bytesWritten() > 0) ? response.status().code() : 0;
    }

    String route() {
        return route;
    }

    void route(String route) {
        this.route = route;
    }

    void endSpan(Runnable action) {
        boolean run;
        lock.lock();
        try {
            if (endSpan != null) {
                return;
            }
            endSpan = action;
            run = endReady;
        } finally {
            lock.unlock();
        }
        if (run) {
            action.run();
        }
    }

    SpanLifecycle methodSpan() {
        return methodSpan;
    }

    SpanLifecycle startMethodSpan(Span span, TelemetrySdk.Lease lease) {
        lock.lock();
        try {
            boolean resourceInvocation = state == State.OPEN && methodSpan == null;
            var completion = new SpanLifecycle(span, lease, resourceInvocation);
            // A recursive resource invocation is a regular method span, not a second response owner.
            if (resourceInvocation) {
                methodSpan = completion;
            }
            return completion;
        } finally {
            lock.unlock();
        }
    }

    void complete(Throwable failure) {
        SpanLifecycle methodSpan;
        lock.lock();
        try {
            if (state != State.OPEN) {
                return;
            }
            // Registration and terminal notification must agree on which span owns response completion.
            elapsedNanos = System.nanoTime() - startedNanos;
            ended = started.plusNanos(elapsedNanos);
            state = State.COMPLETING;
            methodSpan = this.methodSpan;
            this.failure = failure;
        } finally {
            lock.unlock();
        }
        try {
            measure.accept(this);
        } finally {
            Runnable finish = () -> {
                Runnable action;
                lock.lock();
                try {
                    endReady = true;
                    action = endSpan;
                } finally {
                    lock.unlock();
                }
                try {
                    if (action != null) {
                        action.run();
                    }
                } finally {
                    lease.close();
                    lock.lock();
                    try {
                        state = State.RELEASED;
                    } finally {
                        lock.unlock();
                    }
                }
            };
            if (methodSpan == null) {
                finish.run();
            } else {
                methodSpan.responseCompleted(failure, finish);
            }
        }
    }

    Throwable failure() {
        return failure;
    }

    private enum State {
        OPEN, COMPLETING, RELEASED
    }
}
