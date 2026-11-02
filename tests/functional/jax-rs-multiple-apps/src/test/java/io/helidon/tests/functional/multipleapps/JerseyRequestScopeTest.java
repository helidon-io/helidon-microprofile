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
package io.helidon.tests.functional.multipleapps;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@HelidonTest
@AddConfig(key = "server.port", value = "0")
@AddBean(JerseyRequestScopeTest.FirstApplication.class)
@AddBean(JerseyRequestScopeTest.SecondApplication.class)
@AddBean(JerseyRequestScopeTest.Resource.class)
@AddBean(JerseyRequestScopeTest.RequestState.class)
@AddBean(JerseyRequestScopeTest.RequestData.class)
@AddBean(JerseyRequestScopeTest.Worker.class)
class JerseyRequestScopeTest {
    @Inject
    private WebTarget target;

    @Test
    void preservesCdiStateAndResolvesJerseyInjectionOnWorkersForEachApplication() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) {
                String application = i % 2 == 0 ? "first" : "second";
                String requestId = "request-" + i;
                calls.add(executor.submit(() -> {
                    try (Response response = target.path(application).path("context").request()
                            .header("request-id", requestId).get()) {
                        assertThat(response.getStatus(), is(200));
                        assertThat(response.readEntity(String.class), is(requestId + ":/" + application + "/:" + requestId));
                    }
                }));
            }
            for (Future<?> call : calls) {
                call.get(20, SECONDS);
            }
        }
    }

    @ApplicationPath("first")
    @ApplicationScoped
    public static class FirstApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Resource.class);
        }
    }

    @ApplicationPath("second")
    @ApplicationScoped
    public static class SecondApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Resource.class);
        }
    }

    @Path("context")
    @RequestScoped
    public static class Resource {
        @Inject
        private RequestState state;
        @Inject
        private Worker worker;

        @GET
        public String get(@HeaderParam("request-id") String requestId) throws Exception {
            state.id(requestId);
            return worker.read().toCompletableFuture().get(10, SECONDS);
        }
    }

    @RequestScoped
    public static class RequestState {
        private String id;

        public void id(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    @ApplicationScoped
    public static class Worker {
        @Inject
        private RequestState state;
        @Inject
        private Instance<RequestData> requestData;

        @Asynchronous
        public CompletionStage<String> read() {
            // Force Jersey injection into a new CDI bean on the worker, after the originating request was captured.
            RequestData data = requestData.get();
            try {
                return CompletableFuture.completedFuture(state.id() + ":" + data.uriInfo.getBaseUri().getPath()
                                                                 + ":" + data.headers.getHeaderString("request-id"));
            } finally {
                requestData.destroy(data);
            }
        }
    }

    @Dependent
    public static class RequestData {
        @Context
        private UriInfo uriInfo;
        @Context
        private HttpHeaders headers;
    }
}
