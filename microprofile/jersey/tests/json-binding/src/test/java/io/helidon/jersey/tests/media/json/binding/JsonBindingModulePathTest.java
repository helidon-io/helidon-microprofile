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

package io.helidon.jersey.tests.media.json.binding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.UUID;

import io.helidon.config.Config;
import io.helidon.jersey.webserver.JaxRsService;
import io.helidon.json.binding.Json;
import io.helidon.webserver.WebServer;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonBindingModulePathTest {

    @Test
    void initializesProviderFromNamedModule() {
        assertTrue(JsonBindingModulePathTest.class.getModule().isNamed());

        try (Client client = ClientBuilder.newClient();
                Response response = client.target("http://localhost")
                        .register((ClientRequestFilter) request -> request.abortWith(Response.noContent().build()))
                        .request()
                        .get()) {
            assertEquals(204, response.getStatus());
        }
    }

    @Test
    void readsAndWritesGeneratedEntityThroughServer() {
        ResourceConfig resourceConfig = new ResourceConfig(JsonBindingResource.class);
        WebServer webServer = WebServer.builder()
                .host("127.0.0.1")
                .routing(routing -> routing.register("/jersey", JaxRsService.create(Config.empty(), resourceConfig)))
                .build()
                .start();
        try {
            try (Client client = ClientBuilder.newClient();
                    Response response = client.target("http://127.0.0.1:" + webServer.port())
                            .path("jersey/entity")
                            .request(MediaType.APPLICATION_JSON_TYPE)
                            .post(Entity.entity("{\"json_message\":\"hello\"}", MediaType.APPLICATION_JSON_TYPE))) {
                assertEquals(200, response.getStatus());
                assertEquals("{\"json_message\":\"hello\"}", response.readEntity(String.class));
            }
        } finally {
            webServer.stop();
        }
    }

    @Test
    void writesGeneratedEntityThroughParameterizedDeclaredType() {
        ResourceConfig resourceConfig = new ResourceConfig(JsonBindingResource.class);
        WebServer webServer = WebServer.builder()
                .host("127.0.0.1")
                .routing(routing -> routing.register("/jersey", JaxRsService.create(Config.empty(), resourceConfig)))
                .build()
                .start();
        try {
            try (Client client = ClientBuilder.newClient();
                    Response response = client.target("http://127.0.0.1:" + webServer.port())
                            .path("jersey/entity/view")
                            .request(MediaType.APPLICATION_JSON_TYPE)
                            .get()) {
                assertEquals(200, response.getStatus());
                assertEquals("{\"json_message\":\"hello\"}", response.readEntity(String.class));
            }
        } finally {
            webServer.stop();
        }
    }

    @Test
    void mapsMalformedResponseToProcessingException() {
        ProcessingException exception = assertThrows(
                ProcessingException.class,
                () -> readUuid("{", MediaType.APPLICATION_JSON_TYPE));

        assertEquals(ProcessingException.class, exception.getClass());
        assertEquals("Invalid JSON response body", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void mapsUnsupportedResponseCharsetToProcessingException() {
        ProcessingException exception = assertThrows(
                ProcessingException.class,
                () -> readUuid("\"00000000-0000-0000-0000-000000000000\"",
                               MediaType.valueOf("text/json;charset=no-such-charset")));

        assertEquals(ProcessingException.class, exception.getClass());
        assertEquals("Unsupported JSON response charset: no-such-charset", exception.getMessage());
        assertInstanceOf(UnsupportedCharsetException.class, exception.getCause());
    }

    @Test
    void mapsNonUtf8ResponseStreamFailureToProcessingException() {
        byte[] partialJson = "\"00000000-0000-0000-".getBytes(StandardCharsets.UTF_8);
        IOException expected = new IOException("Response stream failed");
        InputStream inputStream = new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index == partialJson.length) {
                    throw expected;
                }
                return partialJson[index++];
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new UncheckedIOException(expected);
            }
        };

        ProcessingException exception = assertThrows(
                ProcessingException.class, () -> {
                    MediaType mediaType = MediaType.valueOf("text/json;charset=ISO-8859-1");
                    try (Client client = ClientBuilder.newClient();
                            Response response = client.target("http://localhost")
                                    .register((ClientRequestFilter) request -> request.abortWith(
                                            Response.ok("{}")
                                                    .type(mediaType)
                                                    .build()))
                                    .register((ClientResponseFilter) (request, responseContext) ->
                                            responseContext.setEntityStream(inputStream))
                                    .request()
                                    .get()) {
                        assertEquals(200, response.getStatus());
                        response.readEntity(UUID.class);
                    }
                });

        assertEquals(ProcessingException.class, exception.getClass());
        assertSame(expected, exception.getCause());
    }

    private static UUID readUuid(String json, MediaType mediaType) {
        try (Client client = ClientBuilder.newClient();
                Response response = client.target("http://localhost")
                        .register((ClientRequestFilter) request -> request.abortWith(
                                Response.ok(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                                        .type(mediaType)
                                        .build()))
                        .request()
                        .get()) {
            assertEquals(200, response.getStatus());
            return response.readEntity(UUID.class);
        }
    }

    @Path("/entity")
    public static class JsonBindingResource {
        @GET
        @Path("view")
        @Produces(MediaType.APPLICATION_JSON)
        public JsonBindingView<String> view() {
            return new JsonBindingEntity("hello", "do-not-serialize");
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public JsonBindingEntity echo(JsonBindingEntity entity) {
            return new JsonBindingEntity(entity.message(), "do-not-serialize");
        }
    }

    public interface JsonBindingView<T> {
        T message();
    }

    @Json.Entity
    public record JsonBindingEntity(@Json.Property("json_message") String message,
                                    @Json.Ignore String secret) implements JsonBindingView<String> {
    }
}
