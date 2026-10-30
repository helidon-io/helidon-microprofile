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

package io.helidon.tests.integration.restclient;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@HelidonTest
@AddBean(InboundHeadersTest.HeaderResource.class)
@AddConfig(key = "server.port", value = "0")
class InboundHeadersTest {

    private static final String INBOUND_HEADER = "X-Inbound-Only";
    private static final String OUTGOING_HEADER = "X-Outgoing-Header";

    @Test
    void inboundHeadersRequireExplicitPropagation(WebTarget target) {
        // Each request builds a new client; the regression depended on provider iteration order.
        for (int i = 0; i < 100; i++) {
            String headers = target.path("headers/forward")
                    .request()
                    .header(INBOUND_HEADER, "inbound")
                    .get(String.class);
            assertThat(headers, is("absent:outgoing"));
        }
    }

    @Test
    void explicitFactoryCanPropagateInboundHeaders(WebTarget target) {
        String headers = target.path("headers/forward")
                .queryParam("propagate", true)
                .request()
                .header(INBOUND_HEADER, "inbound")
                .get(String.class);
        assertThat(headers, is("inbound:outgoing"));
    }

    @Path("/headers/echo")
    @ClientHeaderParam(name = OUTGOING_HEADER, value = "outgoing")
    public interface HeaderClient extends AutoCloseable {

        @GET
        String headers();

        @Override
        void close();
    }

    @Path("/headers/echo")
    @RegisterClientHeaders(ForwardInboundHeader.class)
    @ClientHeaderParam(name = OUTGOING_HEADER, value = "outgoing")
    public interface PropagatingClient extends HeaderClient {
    }

    public static class ForwardInboundHeader implements ClientHeadersFactory {

        @Override
        public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
                                                    MultivaluedMap<String, String> outgoingHeaders) {
            MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
            headers.putSingle(INBOUND_HEADER, incomingHeaders.getFirst(INBOUND_HEADER));
            return headers;
        }
    }

    @Path("/headers")
    public static class HeaderResource {

        @Context
        private UriInfo uriInfo;

        @GET
        @Path("/forward")
        public String forward(@QueryParam("propagate") boolean propagate) {
            RestClientBuilder builder = RestClientBuilder.newBuilder()
                    .baseUri(uriInfo.getBaseUri());
            try (HeaderClient client = propagate ? builder.build(PropagatingClient.class) : builder.build(HeaderClient.class)) {
                return client.headers();
            }
        }

        @GET
        @Path("/echo")
        public String echo(@HeaderParam(INBOUND_HEADER) @DefaultValue("absent") String inbound,
                           @HeaderParam(OUTGOING_HEADER) String outgoing) {
            return inbound + ":" + outgoing;
        }
    }
}
