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
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.GenericType;

import jakarta.annotation.Priority;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.NoContentException;

import org.glassfish.jersey.internal.spi.ForcedAutoDiscoverable;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonBindingProviderTest {
    private final JsonBindingProvider provider = JsonBindingProvider.create(RuntimeType.SERVER);
    private final JsonBindingProvider clientProvider = JsonBindingProvider.create(RuntimeType.CLIENT);

    @Test
    void readsAndWritesGeneratedJsonEntity() throws Exception {
        assertThat(provider.isWriteable(JsonBindingEntity.class,
                                       JsonBindingEntity.class,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isReadable(JsonBindingEntity.class,
                                      JsonBindingEntity.class,
                                      new Annotation[0],
                                      MediaType.APPLICATION_JSON_TYPE), is(true));

        JsonBindingEntity entity = new JsonBindingEntity("hello", null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        provider.writeTo(entity,
                         JsonBindingEntity.class,
                         JsonBindingEntity.class,
                         new Annotation[0],
                         MediaType.APPLICATION_JSON_TYPE,
                         new MultivaluedHashMap<>(),
                         output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json, containsString("\"message\":\"hello\""));

        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;
        JsonBindingEntity result = (JsonBindingEntity) provider.readFrom(entityType,
                                                                           JsonBindingEntity.class,
                                                                           new Annotation[0],
                                                                           MediaType.APPLICATION_JSON_TYPE,
                                                                           new MultivaluedHashMap<>(),
                                                                           new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(result, is(entity));
    }

    @Test
    void writesRuntimeTypeWhenDeclaredAsObject() throws Exception {
        assertThat(provider.isWriteable(JsonBindingEntity.class,
                                       Object.class,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(true));

        JsonBindingEntity entity = new JsonBindingEntity("hello", "do-not-serialize");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        provider.writeTo(entity,
                         JsonBindingEntity.class,
                         Object.class,
                         new Annotation[0],
                         MediaType.APPLICATION_JSON_TYPE,
                         new MultivaluedHashMap<>(),
                         output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json, containsString("\"message\":\"hello\""));
        assertThat(json, not(containsString("do-not-serialize")));
    }

    @Test
    void writesConcreteCollectionType() throws Exception {
        Type type = new GenericType<LinkedHashMap<String, JsonBindingEntity>>() { }.type();
        assertThat(provider.isWriteable(LinkedHashMap.class,
                                       type,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(true));

        LinkedHashMap<String, JsonBindingEntity> entity = new LinkedHashMap<>();
        entity.put("key", new JsonBindingEntity("hello", "do-not-serialize"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        provider.writeTo(entity,
                         LinkedHashMap.class,
                         type,
                         new Annotation[0],
                         MediaType.APPLICATION_JSON_TYPE,
                         new MultivaluedHashMap<>(),
                         output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json, containsString("\"key\":{\"message\":\"hello\"}"));
        assertThat(json, not(containsString("do-not-serialize")));
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
        Type generatedEntityGenericArray = new GenericType<List<JsonBindingEntity>[]>() { }.type();
        Type jsonbOnlyEntityList = new GenericType<List<JsonbOnlyEntity>>() { }.type();
        Type generatedEntityMap = new GenericType<Map<String, JsonBindingEntity>>() { }.type();
        Type unsupportedKeyMap = new GenericType<Map<JsonBindingEntity, JsonBindingEntity>>() { }.type();
        Type wildcardEntityList = new GenericType<List<?>>() { }.type();

        assertThat(provider.isReadable(List.class,
                                       generatedEntityList,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isWriteable(List.class,
                                        generatedEntityList,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isReadable(List[].class,
                                       generatedEntityGenericArray,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(List[].class,
                                        generatedEntityGenericArray,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isReadable(List.class,
                                       jsonbOnlyEntityList,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(List.class,
                                        jsonbOnlyEntityList,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(Map.class,
                                        generatedEntityMap,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(true));
        assertThat(provider.isWriteable(Map.class,
                                        unsupportedKeyMap,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isReadable(List.class,
                                       wildcardEntityList,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isReadable(Object.class,
                                       Object.class,
                                       new Annotation[0],
                                       MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(List.class,
                                        List.class,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
        assertThat(provider.isWriteable(Map.class,
                                        Map.class,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE), is(false));
    }

    @Test
    void supportsJsonMediaTypesCaseInsensitively() {
        for (MediaType mediaType : List.of(MediaType.valueOf("Application/JSON"),
                                           MediaType.valueOf("application/vnd.example+JSON"))) {
            assertThat(provider.isReadable(JsonBindingEntity.class,
                                           JsonBindingEntity.class,
                                           new Annotation[0],
                                           mediaType), is(true));
            assertThat(provider.isWriteable(JsonBindingEntity.class,
                                            JsonBindingEntity.class,
                                            new Annotation[0],
                                            mediaType), is(true));
        }
    }

    @Test
    void rejectsEmptyInput() {
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        assertThrows(NoContentException.class,
                     () -> provider.readFrom(entityType,
                                             JsonBindingEntity.class,
                                             new Annotation[0],
                                             MediaType.APPLICATION_JSON_TYPE,
                                             new MultivaluedHashMap<>(),
                                             new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void honorsDeclaredCharset() throws Exception {
        MediaType mediaType = MediaType.valueOf("text/json;charset=ISO-8859-1");
        JsonBindingEntity entity = new JsonBindingEntity("héllo", null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        provider.writeTo(entity,
                         JsonBindingEntity.class,
                         JsonBindingEntity.class,
                         new Annotation[0],
                         mediaType,
                         new MultivaluedHashMap<>(),
                         output);

        String json = output.toString(StandardCharsets.ISO_8859_1);
        assertThat(json, containsString("\"message\":\"héllo\""));

        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;
        JsonBindingEntity result = (JsonBindingEntity) provider.readFrom(entityType,
                                                                          JsonBindingEntity.class,
                                                                          new Annotation[0],
                                                                          mediaType,
                                                                          new MultivaluedHashMap<>(),
                                                                          new ByteArrayInputStream(
                                                                                  json.getBytes(
                                                                                          StandardCharsets.ISO_8859_1)));
        assertThat(result, is(entity));
    }

    @Test
    void keepsApplicationJsonUtf8() throws Exception {
        JsonBindingEntity entity = new JsonBindingEntity("héllo", null);
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        for (MediaType mediaType : List.of(MediaType.valueOf("application/json;charset=ISO-8859-1"),
                                           MediaType.valueOf("application/problem+json;charset=ISO-8859-1"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            provider.writeTo(entity,
                             JsonBindingEntity.class,
                             JsonBindingEntity.class,
                             new Annotation[0],
                             mediaType,
                             new MultivaluedHashMap<>(),
                             output);

            String json = output.toString(StandardCharsets.UTF_8);
            assertThat(json, containsString("\"message\":\"héllo\""));
            JsonBindingEntity result = (JsonBindingEntity) provider.readFrom(entityType,
                                                                              JsonBindingEntity.class,
                                                                              new Annotation[0],
                                                                              mediaType,
                                                                              new MultivaluedHashMap<>(),
                                                                              new ByteArrayInputStream(
                                                                                      output.toByteArray()));
            assertThat(result, is(entity));
        }
    }

    @Test
    void rejectsMalformedDeclaredCharsetInput() {
        MediaType mediaType = MediaType.valueOf("text/json;charset=US-ASCII");
        byte[] malformedJson = "{\"message\":\"héllo\"}".getBytes(StandardCharsets.ISO_8859_1);
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> provider.readFrom(entityType,
                                        JsonBindingEntity.class,
                                        new Annotation[0],
                                        mediaType,
                                        new MultivaluedHashMap<>(),
                                        new ByteArrayInputStream(malformedJson)));
        assertThat(exception.getCause(), instanceOf(CharacterCodingException.class));
    }

    @Test
    void mapsMalformedClientResponseEncodingToProcessingException() {
        MediaType mediaType = MediaType.valueOf("text/json;charset=US-ASCII");
        byte[] malformedJson = "{\"message\":\"héllo\"}".getBytes(StandardCharsets.ISO_8859_1);
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        ProcessingException exception = assertThrows(
                ProcessingException.class,
                () -> clientProvider.readFrom(entityType,
                                              JsonBindingEntity.class,
                                              new Annotation[0],
                                              mediaType,
                                              new MultivaluedHashMap<>(),
                                              new ByteArrayInputStream(malformedJson)));
        assertEquals(ProcessingException.class, exception.getClass());
        assertThat(exception.getMessage(), is("Invalid JSON response encoding for charset US-ASCII"));
        assertThat(exception.getCause(), instanceOf(CharacterCodingException.class));
    }

    @Test
    void rejectsMalformedJsonWithoutExposingRequestContent() {
        byte[] malformedUtf8 = "{\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        malformedUtf8[12] = (byte) 0xFF;
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        for (byte[] malformedJson : List.of("{\"message\":}".getBytes(StandardCharsets.UTF_8), malformedUtf8)) {
            BadRequestException exception = assertThrows(
                    BadRequestException.class,
                    () -> provider.readFrom(entityType,
                                            JsonBindingEntity.class,
                                            new Annotation[0],
                                            MediaType.APPLICATION_JSON_TYPE,
                                            new MultivaluedHashMap<>(),
                                            new ByteArrayInputStream(malformedJson)));
            assertThat(exception.getResponse().getStatus(), is(400));
            assertThat(exception.getMessage(), is("Invalid JSON request body"));
            assertThat(exception.getCause(), nullValue());
        }
    }

    @Test
    void rejectsInvalidUuidWithoutExposingRequestContent() {
        @SuppressWarnings("unchecked")
        Class<Object> uuidType = (Class<Object>) (Class<?>) UUID.class;

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> provider.readFrom(uuidType,
                                        UUID.class,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE,
                                        new MultivaluedHashMap<>(),
                                        new ByteArrayInputStream("\"not-a-uuid\"".getBytes(StandardCharsets.UTF_8))));
        assertThat(exception.getResponse().getStatus(), is(400));
        assertThat(exception.getMessage(), is("Invalid JSON request body"));
        assertThat(exception.getCause(), nullValue());
    }

    @Test
    void mapsMalformedClientResponseToProcessingException() {
        @SuppressWarnings("unchecked")
        Class<Object> uuidType = (Class<Object>) (Class<?>) UUID.class;

        ProcessingException exception = assertThrows(
                ProcessingException.class,
                () -> clientProvider.readFrom(uuidType,
                                              UUID.class,
                                              new Annotation[0],
                                              MediaType.APPLICATION_JSON_TYPE,
                                              new MultivaluedHashMap<>(),
                                              new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8))));
        assertEquals(ProcessingException.class, exception.getClass());
        assertThat(exception.getMessage(), is("Invalid JSON response body"));
        assertThat(exception.getCause(), nullValue());
    }

    @Test
    void propagatesRequestStreamFailures() {
        byte[] partialJson = "{\"message\":\"\\u".getBytes(StandardCharsets.UTF_8);
        IOException expected = new IOException("Request stream failed");
        InputStream inputStream = new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index == partialJson.length) {
                    throw expected;
                }
                return partialJson[index++];
            }
        };
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        IOException actual = assertThrows(
                IOException.class,
                () -> provider.readFrom(entityType,
                                        JsonBindingEntity.class,
                                        new Annotation[0],
                                        MediaType.APPLICATION_JSON_TYPE,
                                        new MultivaluedHashMap<>(),
                                        inputStream));
        assertThat(actual, sameInstance(expected));
    }

    @Test
    void rejectsUnmappableDeclaredCharsetOutput() {
        MediaType mediaType = MediaType.valueOf("text/json;charset=US-ASCII");
        JsonBindingEntity entity = new JsonBindingEntity("héllo", null);

        assertThrows(CharacterCodingException.class,
                     () -> provider.writeTo(entity,
                                            JsonBindingEntity.class,
                                            JsonBindingEntity.class,
                                            new Annotation[0],
                                            mediaType,
                                            new MultivaluedHashMap<>(),
                                            new ByteArrayOutputStream()));
    }

    @Test
    void rejectsUnsupportedRequestCharset() {
        MediaType mediaType = MediaType.valueOf("text/json;charset=no-such-charset");
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        assertThrows(NotSupportedException.class,
                     () -> provider.readFrom(entityType,
                                             JsonBindingEntity.class,
                                             new Annotation[0],
                                             mediaType,
                                             new MultivaluedHashMap<>(),
                                             new ByteArrayInputStream(
                                                     "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void mapsUnsupportedClientResponseCharsetToProcessingException() {
        MediaType mediaType = MediaType.valueOf("text/json;charset=no-such-charset");
        @SuppressWarnings("unchecked")
        Class<Object> entityType = (Class<Object>) (Class<?>) JsonBindingEntity.class;

        ProcessingException exception = assertThrows(
                ProcessingException.class,
                () -> clientProvider.readFrom(entityType,
                                              JsonBindingEntity.class,
                                              new Annotation[0],
                                              mediaType,
                                              new MultivaluedHashMap<>(),
                                              new ByteArrayInputStream(
                                                      "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8))));
        assertEquals(ProcessingException.class, exception.getClass());
        assertThat(exception.getMessage(), is("Unsupported JSON response charset: no-such-charset"));
        assertThat(exception.getCause(), instanceOf(UnsupportedCharsetException.class));
    }

    @Test
    void fallsBackToUtf8ForUnsupportedResponseCharset() throws Exception {
        MediaType mediaType = MediaType.valueOf("text/json;charset=no-such-charset");
        JsonBindingEntity entity = new JsonBindingEntity("héllo", null);
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        provider.writeTo(entity,
                         JsonBindingEntity.class,
                         JsonBindingEntity.class,
                         new Annotation[0],
                         mediaType,
                         headers,
                         output);

        assertThat(output.toString(StandardCharsets.UTF_8), containsString("\"message\":\"héllo\""));
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE),
                   is(mediaType.withCharset(StandardCharsets.UTF_8.name())));
    }

    @Test
    void fallsBackToUtf8ForDecodeOnlyResponseCharset() throws Exception {
        MediaType mediaType = MediaType.valueOf("text/json;charset=x-JISAutoDetect");
        JsonBindingEntity entity = new JsonBindingEntity("héllo", null);
        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThat(Charset.forName("x-JISAutoDetect").canEncode(), is(false));
        provider.writeTo(entity,
                         JsonBindingEntity.class,
                         JsonBindingEntity.class,
                         new Annotation[0],
                         mediaType,
                         headers,
                         output);

        assertThat(output.toString(StandardCharsets.UTF_8), containsString("\"message\":\"héllo\""));
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE),
                   is(mediaType.withCharset(StandardCharsets.UTF_8.name())));
    }

    @Test
    void supportsConcurrentReaderAndWriterFirstUse() throws Exception {
        Type nestedListType = new GenericType<List<List<String>>>() { }.type();
        @SuppressWarnings("unchecked")
        Class<Object> listType = (Class<Object>) (Class<?>) List.class;

        for (int i = 0; i < 20; i++) {
            JsonBindingProvider freshProvider = JsonBindingProvider.create(RuntimeType.SERVER);
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = Thread.ofPlatform().daemon().unstarted(() -> {
                try {
                    start.await();
                    freshProvider.readFrom(listType,
                                           nestedListType,
                                           new Annotation[0],
                                           MediaType.APPLICATION_JSON_TYPE,
                                           new MultivaluedHashMap<>(),
                                           new ByteArrayInputStream("[[\"value\"]]".getBytes(StandardCharsets.UTF_8)));
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            Thread writer = Thread.ofPlatform().daemon().unstarted(() -> {
                try {
                    start.await();
                    freshProvider.writeTo(List.of(List.of("value")),
                                          List.class,
                                          nestedListType,
                                          new Annotation[0],
                                          MediaType.APPLICATION_JSON_TYPE,
                                          new MultivaluedHashMap<>(),
                                          new ByteArrayOutputStream());
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });

            reader.start();
            writer.start();

            assertThat("concurrent read did not complete", reader.join(Duration.ofSeconds(2)), is(true));
            assertThat("concurrent write did not complete", writer.join(Duration.ofSeconds(2)), is(true));
            assertThat("concurrent binding failed", failure.get(), is(nullValue()));
        }
    }

    private record JsonbOnlyEntity(String message) {
    }
}
