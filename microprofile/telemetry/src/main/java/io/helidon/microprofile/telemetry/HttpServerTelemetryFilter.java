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

import io.helidon.webserver.http.ServerResponse;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * Adapts matched resources and response metadata to the shared server telemetry coordinator.
 * Kept separate from the pre-matching adapter so resource and route availability retain their timing.
 * Runs before ordinary request filters and after ordinary response filters. Its response callback closes
 * telemetry's response-filter activation before filters that opened an enclosing request scope run.
 */
@Provider
@Priority(Priorities.USER - 1)
@ConstrainedTo(RuntimeType.SERVER)
class HttpServerTelemetryFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private final HttpServerTelemetry telemetry;

    @Context
    private ResourceInfo resourceInfo;

    @Context
    private ServerResponse response;

    @Inject
    HttpServerTelemetryFilter(HttpServerTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        telemetry.matched(request, resourceInfo, response);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        telemetry.response(request, response);
    }
}
