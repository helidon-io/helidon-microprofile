/*
 * Copyright (c) 2024, 2026 Oracle and/or its affiliates.
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

package io.helidon.microprofile.grpc.tracing;

import io.helidon.config.Config;
import io.helidon.microprofile.config.core.MpConfig;
import io.helidon.microprofile.grpc.server.spi.GrpcMpContext;
import io.helidon.microprofile.grpc.server.spi.GrpcMpExtension;
import io.helidon.webserver.grpc.spi.GrpcServerService;
import io.helidon.webserver.grpc.tracing.GrpcTracingConfig;
import io.helidon.webserver.grpc.tracing.GrpcTracingServiceProvider;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * gRPC MP extension that adds the tracing interceptor if configured.
 */
public class GrpcMpTracingExtension implements GrpcMpExtension {

    private static final String GRPC_TRACING_ROOT = "tracing.grpc";

    @Override
    public void configure(GrpcMpContext context) {
        Config config = MpConfig.toHelidonConfig(ConfigProvider.getConfig());
        Config tracingConfig = config.get(GRPC_TRACING_ROOT);
        if (!GrpcTracingConfig.create(tracingConfig).enabled()) {
            return;
        }
        GrpcServerService tracingService = new GrpcTracingServiceProvider().create(tracingConfig, "tracing");
        tracingService.interceptors().forEach(interceptor -> context.routing().intercept(interceptor));
    }
}
