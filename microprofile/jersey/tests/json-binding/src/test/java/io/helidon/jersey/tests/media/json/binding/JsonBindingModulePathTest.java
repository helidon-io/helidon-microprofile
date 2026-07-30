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

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBindingModulePathTest {

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
}
