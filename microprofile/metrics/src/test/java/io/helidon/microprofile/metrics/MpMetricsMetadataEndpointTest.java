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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.AddConfigBlock;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@HelidonTest
@AddConfig(key = "metrics.permit-all", value = "true")
@AddConfigBlock("""
        metrics.scoping.scopes.0.name=application
        metrics.scoping.scopes.0.filter.exclude=metadata[.]hidden
        metrics.scoping.scopes.1.name=metadata-excluded
        metrics.scoping.scopes.1.enabled=false
        """)
class MpMetricsMetadataEndpointTest {
    private static final String DIFFERENT_TYPES_METER = "metadata.shared.different.types";
    private static final String SAME_TYPE_METER = "metadata.shared.same.type";
    private static final String TAGGED_METER = "metadata.shared.tags";
    private static final String SINGLE_SCOPE_METER = "metadata.application.only";
    private static final String EXCLUDED_METER = "metadata.hidden";
    private static final String EXCLUDED_SCOPE = "metadata-excluded";
    private static final String UNKNOWN_SCOPE = "metadata-unknown";

    @Inject
    private WebTarget webTarget;

    @BeforeEach
    void createMeters() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        MetricRegistry application = registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE);
        MetricRegistry vendor = registryFactory.getRegistry(MetricRegistry.VENDOR_SCOPE);
        application.timer(Metadata.builder()
                                  .withName(DIFFERENT_TYPES_METER)
                                  .withDescription("Application timing")
                                  .build());
        vendor.histogram(Metadata.builder()
                                 .withName(DIFFERENT_TYPES_METER)
                                 .withDescription("Vendor sizes")
                                 .withUnit(MetricUnits.BYTES)
                                 .build());
        application.counter(Metadata.builder()
                                    .withName(SAME_TYPE_METER)
                                    .withDescription("Application bytes")
                                    .withUnit(MetricUnits.BYTES)
                                    .build());
        vendor.counter(Metadata.builder()
                               .withName(SAME_TYPE_METER)
                               .withDescription("Vendor bytes")
                               .withUnit(MetricUnits.BYTES)
                               .build());

        Metadata taggedApplication = Metadata.builder()
                .withName(TAGGED_METER)
                .withDescription("Tagged application requests")
                .build();
        application.counter(taggedApplication, new Tag("color", "blue"), new Tag("shape", "circle"));
        application.counter(taggedApplication, new Tag("color", "red"), new Tag("shape", "square"));
        vendor.counter(Metadata.builder()
                               .withName(TAGGED_METER)
                               .withDescription("Tagged vendor requests")
                               .build(),
                       new Tag("color", "green"), new Tag("shape", "triangle"));
        application.counter(Metadata.builder()
                                    .withName(SINGLE_SCOPE_METER)
                                    .withDescription("Application only")
                                    .withUnit(MetricUnits.BYTES)
                                    .build());
        application.counter(EXCLUDED_METER);
        registryFactory.getRegistry(EXCLUDED_SCOPE).counter(EXCLUDED_METER);
    }

    @Test
    void retainsDifferentTypesAcrossScopesInScopeOrder() {
        JsonObject metadata = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.VENDOR_SCOPE, MetricRegistry.APPLICATION_SCOPE)
                .queryParam("name", DIFFERENT_TYPES_METER)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .options(JsonObject.class);

        assertThat("Name filter excludes unrelated meters", metadata.keySet(), is(Set.of(DIFFERENT_TYPES_METER)));
        JsonArray records = metadataRecords(metadata, DIFFERENT_TYPES_METER);
        JsonObject application = assertMetadata(records.get(0), "timer", "SECONDS", "Application timing");
        JsonObject vendor = assertMetadata(records.get(1), "distribution_summary", MetricUnits.BYTES, "Vendor sizes");
        assertThat("Application record is first", tagGroups(application),
                   containsInAnyOrder(Set.of("mp_scope=application")));
        assertThat("Vendor record is second", tagGroups(vendor), containsInAnyOrder(Set.of("mp_scope=vendor")));
    }

    @Test
    void retainsSameTypeDescriptionsAcrossScopes() {
        JsonObject metadata = webTarget.path("metrics")
                .queryParam("name", SAME_TYPE_METER)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .options(JsonObject.class);

        assertThat("Name filter excludes unrelated meters", metadata.keySet(), is(Set.of(SAME_TYPE_METER)));
        JsonArray records = metadataRecords(metadata, SAME_TYPE_METER);
        JsonObject application = assertMetadata(records.get(0), "counter", MetricUnits.BYTES, "Application bytes");
        JsonObject vendor = assertMetadata(records.get(1), "counter", MetricUnits.BYTES, "Vendor bytes");
        assertThat("Application metadata retains its scope", tagGroups(application),
                   containsInAnyOrder(Set.of("mp_scope=application")));
        assertThat("Vendor metadata retains its scope", tagGroups(vendor),
                   containsInAnyOrder(Set.of("mp_scope=vendor")));
    }

    @Test
    void retainsEveryTagGroupWithinEachScope() {
        JsonObject metadata = webTarget.path("metrics")
                .queryParam("name", TAGGED_METER)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .options(JsonObject.class);

        JsonArray records = metadataRecords(metadata, TAGGED_METER);
        JsonObject application = metadataObject(records.get(0), "Application metadata");
        JsonObject vendor = metadataObject(records.get(1), "Vendor metadata");
        assertThat("Every application tag combination is retained", tagGroups(application),
                   containsInAnyOrder(Set.of("mp_scope=application", "color=blue", "shape=circle"),
                                      Set.of("mp_scope=application", "color=red", "shape=square")));
        assertThat("Vendor tags remain separate", tagGroups(vendor),
                   containsInAnyOrder(Set.of("mp_scope=vendor", "color=green", "shape=triangle")));

        JsonObject applicationOnly = webTarget.path("metrics/application/" + TAGGED_METER)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .options(JsonObject.class);
        assertThat("Single-scope metadata retains all tag combinations",
                   metadataObject(applicationOnly.get(TAGGED_METER), "Single-scope metadata"), is(application));
    }

    @Test
    void preservesObjectShapeForSingleScopeSelections() {
        List<WebTarget> targets = List.of(webTarget.path("metrics/application/" + DIFFERENT_TYPES_METER),
                                         webTarget.path("metrics/application")
                                                 .queryParam("name", DIFFERENT_TYPES_METER),
                                         webTarget.path("metrics")
                                                 .queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                                                 .queryParam("name", DIFFERENT_TYPES_METER));
        for (WebTarget target : targets) {
            JsonObject metadata = target.request(MediaType.APPLICATION_JSON_TYPE).options(JsonObject.class);
            assertThat("Only the selected meter is returned: " + target.getUri(),
                       metadata.keySet(), is(Set.of(DIFFERENT_TYPES_METER)));
            JsonObject application = assertMetadata(metadata.get(DIFFERENT_TYPES_METER),
                                                    "timer", "SECONDS", "Application timing");
            assertThat("Only application tags are returned: " + target.getUri(), tagGroups(application),
                       containsInAnyOrder(Set.of("mp_scope=application")));
        }

        JsonObject aggregate = webTarget.path("metrics")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .options(JsonObject.class);
        assertMetadata(aggregate.get(SINGLE_SCOPE_METER), "counter", MetricUnits.BYTES, "Application only");
    }

    @Test
    void preservesLegacyTagEscaping() {
        String name = "metadata.escaped";
        RegistryFactory.getInstance().getRegistry(MetricRegistry.APPLICATION_SCOPE)
                .counter(name, new Tag("value", "semi;\b\f\n\r\t\"\\"));
        JsonObject metadata = webTarget.path("metrics/application/" + name)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .options(JsonObject.class);
        assertThat(tagGroups(metadataObject(metadata.get(name), "Escaped metadata")),
                   containsInAnyOrder(Set.of("mp_scope=application", "value=semi_\\b\\f\\n\\r\\t\\\"\\\\")));
    }

    @Test
    void returnsNotFoundForExcludedAndUnknownScopesWithoutCreatingRegistries() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        Set<String> scopesBeforeRequest = registryFactory.scopes();
        assertThat("Unknown scope starts absent", scopesBeforeRequest, not(hasItem(UNKNOWN_SCOPE)));
        List<WebTarget> targets = List.of(webTarget.path("metrics/" + UNKNOWN_SCOPE),
                                         webTarget.path("metrics").queryParam("scope", UNKNOWN_SCOPE),
                                         webTarget.path("metrics/" + EXCLUDED_SCOPE),
                                         webTarget.path("metrics").queryParam("scope", EXCLUDED_SCOPE),
                                         webTarget.path("metrics/application/" + EXCLUDED_METER),
                                         webTarget.path("metrics")
                                                 .queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                                                 .queryParam("name", EXCLUDED_METER));
        for (WebTarget target : targets) {
            try (Response response = target.request(MediaType.APPLICATION_JSON_TYPE).options()) {
                assertThat("Excluded or unknown metadata status: " + target.getUri(), response.getStatus(), is(404));
            }
        }
        assertThat("Metadata requests do not create registries", registryFactory.scopes(), is(scopesBeforeRequest));
    }

    private static JsonArray metadataRecords(JsonObject metadata, String name) {
        JsonValue value = metadata.get(name);
        assertThat("Same-name metadata has a per-scope array for " + name, value, instanceOf(JsonArray.class));
        JsonArray records = value.asJsonArray();
        assertThat("Exactly one metadata record per selected scope for " + name, records.size(), is(2));
        return records;
    }

    private static JsonObject assertMetadata(JsonValue value, String type, String unit, String description) {
        JsonObject metadata = metadataObject(value, description);
        assertThat(description + " type", metadata.get("type"), is(Json.createValue(type)));
        assertThat(description + " unit", metadata.get("unit"), is(Json.createValue(unit)));
        assertThat(description + " description", metadata.get("description"), is(Json.createValue(description)));
        return metadata;
    }

    private static JsonObject metadataObject(JsonValue value, String description) {
        assertThat(description + " is a metadata object", value, instanceOf(JsonObject.class));
        return value.asJsonObject();
    }

    private static List<Set<String>> tagGroups(JsonObject metadata) {
        JsonValue groups = metadata.get("tags");
        assertThat("Metadata tags are an array: " + metadata, groups, instanceOf(JsonArray.class));
        List<Set<String>> result = new ArrayList<>();
        for (JsonValue group : groups.asJsonArray()) {
            assertThat("Each tag group is an array", group, instanceOf(JsonArray.class));
            Set<String> tags = new HashSet<>();
            for (JsonValue tag : group.asJsonArray()) {
                assertThat("Each metadata tag is a string", tag, instanceOf(JsonString.class));
                tags.add(((JsonString) tag).getString());
            }
            result.add(tags);
        }
        return result;
    }
}
