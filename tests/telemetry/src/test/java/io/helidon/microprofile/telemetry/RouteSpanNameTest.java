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

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfigBlock;
import io.helidon.microprofile.testing.AddExtension;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that automatic span names include the HTTP method and use the matched route template.
 */
@HelidonTest
@AddBean(RouteSpanNameTest.SpanResource.class)
@AddBean(InMemorySpanExporter.class)
@AddBean(InMemorySpanExporterProvider.class)
@AddExtension(TelemetryCdiExtension.class)
@AddConfigBlock("""
        otel.service.name=helidon-mp-telemetry
        otel.sdk.disabled=false
        otel.traces.exporter=in-memory
        """)
public class RouteSpanNameTest {

    @Inject
    WebTarget webTarget;

    @Inject
    InMemorySpanExporter spanExporter;

    @BeforeEach
    void setup() {
        spanExporter.reset();
    }

    @Test
    void spanNamesUseMethodAndRouteTemplate() {
        for (String name : List.of("first", "second")) {
            try (Response response = webTarget.path("named/" + name).request().get()) {
                assertThat(response.getStatus(), is(Response.Status.OK.getStatusCode()));
            }
        }

        List<String> names = spanExporter.getFinishedSpanItems(4)
                .stream()
                .map(SpanData::getName)
                .toList();

        assertThat(names, containsInAnyOrder("GET /named/{name}", "GET /named/{name}", "GET", "GET"));
    }

    @Path("/")
    @ApplicationScoped
    public static class SpanResource {

        @GET
        @Path("named/{name}")
        public Response mixedSpan() {
            return Response.ok().build();
        }

    }
}
