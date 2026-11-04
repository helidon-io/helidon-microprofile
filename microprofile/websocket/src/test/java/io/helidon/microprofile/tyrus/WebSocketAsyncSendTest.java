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

package io.helidon.microprofile.tyrus;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.client.WebTarget;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.container.jdk.client.JdkClientContainer;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

@HelidonTest
@AddBean(WebSocketAsyncSendTest.AsyncEndpoint.class)
class WebSocketAsyncSendTest {
    @Inject
    private WebTarget target;

    @Inject
    private AsyncEndpoint endpoint;

    @Test
    void asyncSendsCompleteSuccessfully() throws Exception {
        CompletableFuture<String> echo = new CompletableFuture<>();
        CompletableFuture<SendResult> clientResult = new CompletableFuture<>();
        ClientManager client = ClientManager.createClient(JdkClientContainer.class.getName());
        Endpoint clientEndpoint = new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                session.addMessageHandler(String.class, echo::complete);
            }
        };
        URI uri = URI.create("ws://localhost:" + target.getUri().getPort() + "/async");

        try (Session session = client.connectToServer(clientEndpoint, uri)) {
            session.getAsyncRemote().sendText("hello", clientResult::complete);

            SendResult sent = clientResult.get(10, SECONDS);
            assertThat(sent.isOK(), is(true));
            // WebSocket 2.2 permits null when the session is not known.
            assertThat(sent.getSession(), anyOf(nullValue(), sameInstance(session)));
            assertThat(echo.get(10, SECONDS), is("hello"));

            SendResult echoed = endpoint.sendResult().get(10, SECONDS);
            assertThat(echoed.isOK(), is(true));
            assertThat(echoed.getSession(), anyOf(nullValue(), sameInstance(endpoint.session().get(10, SECONDS))));
        } finally {
            client.shutdown();
        }
    }

    @ApplicationScoped
    @ServerEndpoint("/async")
    public static class AsyncEndpoint {
        private final CompletableFuture<Session> session = new CompletableFuture<>();
        private final CompletableFuture<SendResult> sendResult = new CompletableFuture<>();

        @OnMessage
        public void echo(Session session, String message) {
            this.session.complete(session);
            session.getAsyncRemote().sendText(message, sendResult::complete);
        }

        public CompletableFuture<Session> session() {
            return session;
        }

        public CompletableFuture<SendResult> sendResult() {
            return sendResult;
        }
    }
}
