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
import java.util.concurrent.TimeUnit;

import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;

/**
 * Completes telemetry on terminal transport failures, for which the normal response-sent callback may never run.
 */
final class TelemetryResponseWriter implements ContainerResponseWriter {
    private final ContainerResponseWriter delegate;
    private final HttpServerObservation observation;

    TelemetryResponseWriter(ContainerResponseWriter delegate, HttpServerObservation observation) {
        this.delegate = delegate;
        this.observation = observation;
    }

    @Override
    public OutputStream writeResponseStatusAndHeaders(long length, ContainerResponse response) {
        return delegate.writeResponseStatusAndHeaders(length, response);
    }

    @Override
    public boolean suspend(long timeout, TimeUnit unit, TimeoutHandler handler) {
        return delegate.suspend(timeout, unit, handler);
    }

    @Override
    public void setSuspendTimeout(long timeout, TimeUnit unit) {
        delegate.setSuspendTimeout(timeout, unit);
    }

    @Override
    public void commit() {
        try {
            delegate.commit();
        } catch (RuntimeException | Error e) {
            completeFailure(e);
            throw e;
        }
    }

    @Override
    public void failure(Throwable failure) {
        try {
            delegate.failure(failure);
        } finally {
            completeFailure(failure);
        }
    }

    @Override
    public boolean enableResponseBuffering() {
        return delegate.enableResponseBuffering();
    }

    private void completeFailure(Throwable failure) {
        try {
            observation.complete(failure);
        } catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
