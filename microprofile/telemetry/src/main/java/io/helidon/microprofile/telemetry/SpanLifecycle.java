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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.tracing.Scope;
import io.helidon.tracing.Span;

import io.opentelemetry.context.Context;

/**
 * Coordinates completion and thread-bound activation of an already-started span, retaining its
 * {@link TelemetrySdk.Lease} until admitted work has finished. Used for annotated methods and HTTP
 * client and server requests, including spans whose lifetime extends beyond a method or filter invocation.
 * <p>
 * Construction reserves the initial invocation so inline completion cannot end the span during setup.
 * The caller must invoke {@link #returned(boolean, Throwable)} exactly once, normally in a {@code finally}
 * block when the initial invocation exits. Invocation and activation reservations are independent.
 * Each successful {@link #activate()} reserves another activation and makes the span current using the
 * captured parent context. The returned scope must be
 * closed on the thread that opened it, restoring that thread's previous context and releasing its
 * reservation. Keeping the span open does not automatically propagate context to application tasks.
 * <p>
 * The constructor's {@code responseBound} flag selects the terminal completion boundary:
 * <ul>
 *     <li>For a response-bound span, {@link #responseCompleted(Throwable, Runnable)} signals completion
 *         of the HTTP operation. Method return or stage completion alone does not end the span.</li>
 *     <li>Otherwise, a synchronous invocation completes on return; an asynchronous invocation waits for
 *         {@link #completed(Throwable)}, typically registered with its completion stage.</li>
 * </ul>
 * Failure notifications retain the first failure for span termination. Response completion can also
 * supply an action to run after span termination and lease release, allowing enclosing cleanup to follow.
 * An explicit completion timestamp preserves the HTTP response boundary even if draining delays span termination.
 * <p>
 * All lifecycle transitions and activation admission are serialized by a lock. The lifecycle starts in
 * {@code INVOKING}, may enter {@code WAITING}, and enters {@code STOPPING} on a terminal signal.
 * {@code STOPPING} rejects new activations; {@code activate()} then returns {@code null}. Once the initial
 * invocation and all admitted activations have released their reservations, {@code ENDING} protects the
 * termination attempt and cleanup from reentrant completion. Cleanup releases the lease and runs the
 * after-end action before entering {@code CLOSED}. Repeated completion notifications cannot end the span
 * or release its lease again.
 */
final class SpanLifecycle {
    private static final System.Logger LOGGER = System.getLogger(SpanLifecycle.class.getName());
    private final Span span;
    private final TelemetrySdk.Lease lease;
    private final boolean responseBound;
    private final Context parentContext = Context.current();
    private final ReentrantLock lock = new ReentrantLock();
    // The invocation owns the initial reservation, including callback registration and scope closure.
    private int active = 1;
    private State state = State.INVOKING;
    private Throwable failure;
    private Instant endTime;
    private Runnable afterEnd = () -> { };

    SpanLifecycle(Span span, TelemetrySdk.Lease lease, boolean responseBound) {
        this.span = span;
        this.lease = lease;
        this.responseBound = responseBound;
    }

    Span span() {
        return span;
    }

    Scope activate() {
        lock.lock();
        try {
            if (state == State.STOPPING || state == State.ENDING || state == State.CLOSED) {
                return null;
            }
            var contextScope = parentContext.makeCurrent();
            active++;
            try {
                return new Activation(span.activate(), contextScope);
            } catch (RuntimeException | Error e) {
                try {
                    contextScope.close();
                } finally {
                    active--;
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    void returned(boolean asynchronous, Throwable thrown) {
        lock.lock();
        try {
            recordFailure(thrown);
            if (state == State.INVOKING) {
                state = responseBound || asynchronous ? State.WAITING : State.STOPPING;
            }
            active--;
            finish();
        } finally {
            lock.unlock();
        }
    }

    void completed(Throwable thrown) {
        lock.lock();
        try {
            if (state == State.ENDING || state == State.CLOSED) {
                return;
            }
            // Completion can run inline before the invocation returns. Its reservation prevents early close.
            try (Scope _ = activate()) {
                recordFailure(thrown);
                if (!responseBound) {
                    state = State.STOPPING;
                }
            }
            finish();
        } finally {
            lock.unlock();
        }
    }

    void responseCompleted(Throwable thrown, Runnable afterEnd) {
        responseCompleted(null, thrown, afterEnd);
    }

    void responseCompleted(Instant endTime, Throwable thrown, Runnable afterEnd) {
        lock.lock();
        try {
            if (state == State.ENDING || state == State.CLOSED) {
                return;
            }
            recordFailure(thrown);
            this.afterEnd = afterEnd;
            this.endTime = endTime;
            state = State.STOPPING;
            finish();
        } finally {
            lock.unlock();
        }
    }

    void failed(Throwable thrown) {
        lock.lock();
        try {
            if (state != State.ENDING && state != State.CLOSED) {
                recordFailure(thrown);
            }
        } finally {
            lock.unlock();
        }
    }

    private void recordFailure(Throwable thrown) {
        if (failure == null && thrown != null) {
            while ((thrown instanceof CompletionException || thrown instanceof ExecutionException)
                    && thrown.getCause() != null) {
                thrown = thrown.getCause();
            }
            failure = thrown;
        }
    }

    private void finish() {
        if (state != State.STOPPING || active != 0) {
            return;
        }
        // Prevent reentrant completion while callbacks execute and dependencies are still owned.
        state = State.ENDING;
        try {
            if (endTime != null) {
                if (failure == null) {
                    span.end(endTime);
                } else {
                    span.end(endTime, failure);
                }
            } else if (failure == null) {
                span.end();
            } else {
                span.end(failure);
            }
        } catch (RuntimeException | Error e) {
            // Instrumentation must not replace an application failure or prevent remaining request cleanup.
            LOGGER.log(System.Logger.Level.WARNING, "Failed to end span", e);
        } finally {
            try {
                lease.close();
            } finally {
                try {
                    afterEnd.run();
                } finally {
                    state = State.CLOSED;
                }
            }
        }
    }

    private enum State {
        INVOKING, WAITING, STOPPING, ENDING, CLOSED
    }

    private final class Activation implements Scope {
        private final Scope delegate;
        private final io.opentelemetry.context.Scope contextScope;
        private final Thread thread = Thread.currentThread();
        private volatile boolean closed;

        private Activation(Scope delegate, io.opentelemetry.context.Scope contextScope) {
            this.delegate = delegate;
            this.contextScope = contextScope;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("Span activation must be closed by the thread that opened it");
            }
            closed = true;
            try {
                try {
                    delegate.close();
                } finally {
                    contextScope.close();
                }
            } finally {
                lock.lock();
                try {
                    active--;
                    finish();
                } finally {
                    lock.unlock();
                }
            }
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}
