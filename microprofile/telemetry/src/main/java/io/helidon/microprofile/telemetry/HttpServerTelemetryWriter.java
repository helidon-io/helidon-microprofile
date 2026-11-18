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

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

/**
 * Adapts response serialization to the coordinator's thread-bound activation and failure handling.
 */
@Provider
@ConstrainedTo(RuntimeType.SERVER)
@Priority(Priorities.ENTITY_CODER - 100)
class HttpServerTelemetryWriter implements WriterInterceptor {
    private final HttpServerTelemetry telemetry;

    @Inject
    HttpServerTelemetryWriter(HttpServerTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
        telemetry.write(context);
    }
}
