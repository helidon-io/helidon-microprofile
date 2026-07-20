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

package io.helidon.jersey.media.json.binding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import io.helidon.common.GenericType;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;

import org.glassfish.jersey.internal.spi.ForcedAutoDiscoverable;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class JsonBindingProviderTest {
    private final JsonBindingProvider provider = JsonBindingProvider.create();

    @Test
    void readsAndWritesGeneratedJsonEntity() throws Exception {
        assertThat(provider.isWriteable(JsonBindingEntity.class,
                                       JsonBindingEntity.class,
                                       new java.lang.annotation.Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isReadable(JsonBindingEntity.class,
                                      JsonBindingEntity.class,
                                      new java.lang.annotation.Annotation[0],
                                      MediaType.APPLICATION_JSON_TYPE), is(true));

        JsonBindingEntity entity = new JsonBindingEntity("hello");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        provider.writeTo(entity,
                         JsonBindingEntity.class,
                         JsonBindingEntity.class,
                         new java.lang.annotation.Annotation[0],
                         MediaType.APPLICATION_JSON_TYPE,
                         new MultivaluedHashMap<>(),
                         output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json, containsString("\"message\":\"hello\""));

        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;
        JsonBindingEntity result = (JsonBindingEntity) provider.readFrom(entityType,
                                                                           JsonBindingEntity.class,
                                                                           new java.lang.annotation.Annotation[0],
                                                                           MediaType.APPLICATION_JSON_TYPE,
                                                                           new MultivaluedHashMap<>(),
                                                                           new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(result, is(entity));
    }

    @Test
    void autoDiscoverableIsRegistered() {
        boolean discovered = ServiceLoader.load(ForcedAutoDiscoverable.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(JsonBindingAutoDiscoverable.class::isInstance);

        assertThat(discovered, is(true));
    }

    @Test
    void precedesDefaultJsonProviders() {
        assertThat(JsonBindingProvider.class.getAnnotation(Priority.class).value(), is(Priorities.ENTITY_CODER));
    }

    @Test
    void supportsOnlyBindableGenericTypes() {
        Type generatedEntityList = new GenericType<List<JsonBindingEntity>>() { }.type();
        Type jsonbOnlyEntityList = new GenericType<List<JsonbOnlyEntity>>() { }.type();
        Type generatedEntityMap = new GenericType<Map<String, JsonBindingEntity>>() { }.type();
        Type unsupportedKeyMap = new GenericType<Map<JsonBindingEntity, JsonBindingEntity>>() { }.type();
        Type wildcardEntityList = new GenericType<List<?>>() { }.type();

        assertThat(provider.isReadable(List.class,
                                       generatedEntityList,
                                       new java.lang.annotation.Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isWriteable(List.class,
                                        generatedEntityList,
                                        new java.lang.annotation.Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isReadable(List.class,
                                       jsonbOnlyEntityList,
                                       new java.lang.annotation.Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(List.class,
                                        jsonbOnlyEntityList,
                                        new java.lang.annotation.Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(Map.class,
                                        generatedEntityMap,
                                        new java.lang.annotation.Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isWriteable(Map.class,
                                        unsupportedKeyMap,
                                        new java.lang.annotation.Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isReadable(List.class,
                                       wildcardEntityList,
                                       new java.lang.annotation.Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isReadable(Object.class,
                                       Object.class,
                                       new java.lang.annotation.Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(List.class,
                                        List.class,
                                        new java.lang.annotation.Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(Map.class,
                                        Map.class,
                                        new java.lang.annotation.Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
    }

    private record JsonbOnlyEntity(String message) {
    }
}
