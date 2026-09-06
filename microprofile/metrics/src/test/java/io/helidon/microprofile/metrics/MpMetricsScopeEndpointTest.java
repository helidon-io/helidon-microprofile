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
package io.helidon.microprofile.metrics;

import java.util.Set;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.AddConfigBlock;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.service.registry.Services;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@HelidonTest
@AddConfig(key = "metrics.permit-all", value = "true")
@AddConfigBlock("""
        metrics.scoping.scopes.0.name=base
        metrics.scoping.scopes.0.filter.exclude=thread[.]count
        """)
class MpMetricsScopeEndpointTest {
    private static final String APPLICATION_METER = "scope.application";
    private static final String BASE_METER = "scope.base";
    private static final String VENDOR_METER = "scope.vendor";
    private static final String DISABLED_BASE_METER = "thread.count";
    private static final String UNKNOWN_SCOPE = "unknown-scope";

    @Inject
    private WebTarget webTarget;

    @BeforeEach
    void createMeters() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE).counter(APPLICATION_METER);
        registryFactory.getRegistry(MetricRegistry.BASE_SCOPE).counter(BASE_METER);
        registryFactory.getRegistry(MetricRegistry.VENDOR_SCOPE).counter(VENDOR_METER);
    }

    @Test
    void isolatesMetricsUsingLegacyScopePaths() {
        assertOnlyExpectedMeter(textAt("metrics/application"), APPLICATION_METER, BASE_METER, VENDOR_METER);
        assertOnlyExpectedMeter(textAt("metrics/base"), BASE_METER, APPLICATION_METER, VENDOR_METER);
        assertOnlyExpectedMeter(textAt("metrics/vendor"), VENDOR_METER, APPLICATION_METER, BASE_METER);

        String namedBase = textAt("metrics/base/" + BASE_METER);
        assertThat(namedBase, containsString(prometheusName(BASE_METER)));
        assertThat(namedBase, not(containsString(prometheusName(APPLICATION_METER))));
    }

    @Test
    void filtersMetricsUsingScopeAndNameQueries() {
        String aggregate = textAt("metrics");
        assertThat(aggregate, containsString(prometheusName(APPLICATION_METER)));
        assertThat(aggregate, containsString(prometheusName(BASE_METER)));
        assertThat(aggregate, containsString(prometheusName(VENDOR_METER)));

        String base = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.BASE_SCOPE)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertOnlyExpectedMeter(base, BASE_METER, APPLICATION_METER, VENDOR_METER);

        String applicationAndVendor = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.APPLICATION_SCOPE, MetricRegistry.VENDOR_SCOPE)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertThat(applicationAndVendor, containsString(prometheusName(APPLICATION_METER)));
        assertThat(applicationAndVendor, containsString(prometheusName(VENDOR_METER)));
        assertThat(applicationAndVendor, not(containsString(prometheusName(BASE_METER))));

        String namedBase = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.BASE_SCOPE)
                .queryParam("name", BASE_METER)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertOnlyExpectedMeter(namedBase, BASE_METER, APPLICATION_METER, VENDOR_METER);
    }

    @Test
    void formatsFlatJsonUsingMpScopeTags() {
        JsonObject aggregate = webTarget.path("metrics")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);
        assertThat(aggregate.containsKey(jsonName(APPLICATION_METER, MetricRegistry.APPLICATION_SCOPE)), is(true));
        assertThat(aggregate.containsKey(jsonName(BASE_METER, MetricRegistry.BASE_SCOPE)), is(true));
        assertThat(aggregate.containsKey(jsonName(VENDOR_METER, MetricRegistry.VENDOR_SCOPE)), is(true));

        JsonObject base = webTarget.path("metrics/base")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);
        assertThat(base.containsKey(jsonName(BASE_METER, MetricRegistry.BASE_SCOPE)), is(true));
        assertThat(base.containsKey(jsonName(APPLICATION_METER, MetricRegistry.APPLICATION_SCOPE)), is(false));
        assertThat(base.containsKey(jsonName(VENDOR_METER, MetricRegistry.VENDOR_SCOPE)), is(false));
    }

    @Test
    void mergesOpenMetricsOutputWithOneTerminalMarker() {
        String output = webTarget.path("metrics")
                .request()
                .accept(MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())
                .get(String.class);

        assertThat(output, containsString(prometheusName(APPLICATION_METER)));
        assertThat(output, containsString(prometheusName(BASE_METER)));
        assertThat(output, containsString(prometheusName(VENDOR_METER)));
        assertThat(output, endsWith("# EOF\n"));
        assertThat(output.indexOf("# EOF"), is(output.lastIndexOf("# EOF")));
    }

    @Test
    void unknownScopeDoesNotCreateRegistry() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        Set<String> scopesBeforeRequest = registryFactory.scopes();

        try (Response response = webTarget.path("metrics")
                .queryParam("scope", UNKNOWN_SCOPE)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get()) {
            assertThat("Unknown scope status", response.getStatus(), is(404));
        }

        assertThat(registryFactory.scopes(), is(scopesBeforeRequest));
    }

    @Test
    void hidesScopeDisabledCoreMeterAndReturnsNotFoundByName() {
        boolean coreMeterExists = Services.get(io.helidon.metrics.api.MeterRegistry.class)
                .meters()
                .stream()
                .anyMatch(meter -> meter.id().name().equals(DISABLED_BASE_METER));
        assertThat("Core-origin thread count meter exists", coreMeterExists, is(true));

        String base = textAt("metrics/base");
        assertThat(base, not(containsString("thread_count{")));

        try (Response response = webTarget.path("metrics/base/" + DISABLED_BASE_METER)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get()) {
            assertThat("Disabled metric path status", response.getStatus(), is(404));
        }

        try (Response response = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.BASE_SCOPE)
                .queryParam("name", DISABLED_BASE_METER)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get()) {
            assertThat("Disabled metric query status", response.getStatus(), is(404));
        }
    }

    private static void assertOnlyExpectedMeter(String output,
                                                String expected,
                                                String firstUnexpected,
                                                String secondUnexpected) {
        assertThat(output, containsString(prometheusName(expected)));
        assertThat(output, not(containsString(prometheusName(firstUnexpected))));
        assertThat(output, not(containsString(prometheusName(secondUnexpected))));
    }

    private static String prometheusName(String meterName) {
        return meterName.replace('.', '_') + "_total";
    }

    private static String jsonName(String meterName, String scope) {
        return meterName + ";" + MpScope.TAG_NAME + "=" + scope;
    }

    private String textAt(String path) {
        return webTarget.path(path)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
    }
}
