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
package io.helidon.microprofile.telemetry;

import java.util.List;

import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Lookup;
import io.helidon.service.registry.ServiceInfo;
import io.helidon.service.registry.Services;
import io.helidon.tracing.Tracer;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

@HelidonTest
@AddConfig(key = "otel.sdk.disabled", value = "false")
class TestTracerAtStartup {

    @Test
    void checkForFullFeaturedTracerAtStartup() {
        Tracer telemetryTracer = TestExtension.globalTracerAtStartup;
        Tracer serviceRegistryTracer = Services.get(Tracer.class);

        assertThat("Global tracer from start-up extension",
                   telemetryTracer.unwrap(io.opentelemetry.api.trace.Tracer.class).getClass().getName(),
                   not(containsString("Default")));
        assertThat("Static tracer lookup",
                   serviceRegistryTracer,
                   sameInstance(telemetryTracer));
        assertThat("Static tracer delegate",
                   serviceRegistryTracer.unwrap(io.opentelemetry.api.trace.Tracer.class).getClass().getName(),
                   not(containsString("Default")));

        List<ServiceInfo> tracerServices = GlobalServiceRegistry.registry().lookupServices(Lookup.create(Tracer.class));
        assertThat("Application tracer supplier descriptor",
                   tracerServices.stream()
                           .anyMatch(it -> it.serviceType().fqName().equals(ApplicationTracerSupplier.class.getName())),
                   is(true));
        assertThat("No CDI producer descriptor for the telemetry tracer",
                   tracerServices.stream()
                           .noneMatch(it -> it.serviceType().fqName().equals(OpenTelemetryProducer.class.getName())),
                   is(true));
    }
}
