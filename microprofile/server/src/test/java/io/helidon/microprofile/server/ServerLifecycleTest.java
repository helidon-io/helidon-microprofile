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

package io.helidon.microprofile.server;

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.config.WeldConfiguration;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.jboss.weld.config.ConfigurationKey.CONCURRENT_DEPLOYMENT;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerLifecycleTest {
    @Test
    void testBuildDefersCdiInitialization() {
        Server server = Server.builder().config(config("deferred")).port(0).build();

        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThat(server.port(), is(-1));

        server.start();
        try {
            assertThat(server.port(), greaterThan(0));
            assertThat(GlobalServiceRegistry.configured(), is(true));
            assertThat(server.start(), sameInstance(server));
        } finally {
            server.stop();
        }

        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    void testCapturedBuilderSettings() {
        Server.Builder builder = Server.builder()
                .config(config("captured"))
                .host("127.0.0.1")
                .port(0)
                .basePath("/server-lifecycle/captured")
                .addResourceClass(CapturedResource.class);
        Server server = builder.build();

        builder.host("invalid.invalid")
                .port(-2)
                .basePath("/changed")
                .addResourceClass(LaterResource.class);

        // Preparing another server registers a different MP Config before the first server starts.
        Server.builder().config(config("other")).port(0).build();

        server.start();
        try (Client client = ClientBuilder.newClient()) {
            var target = client.target("http://127.0.0.1:" + server.port());
            assertThat(target.path("server-lifecycle/captured").request().get(String.class), is("captured"));
            assertThat(target.request().get(String.class), is("captured"));
            try (Response response = target.path("server-lifecycle/later").request().get()) {
                assertThat(response.getStatus(), is(404));
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void testCdiPropertiesFromBuilderConfig() {
        Server server = Server.builder()
                .config(Config.just(ConfigSources.create(Map.of(
                        "server.port", "0",
                        "cdi.org.jboss.weld.bootstrap.concurrentDeployment", "false"))))
                .build();

        // Another builder changes the registered config before this server starts.
        Server.builder().config(config("other")).build();

        server.start();
        try {
            WeldConfiguration configuration =
                BeanManagerProxy.unwrap(CDI.current().getBeanManager())
                .getServices()
                .get(WeldConfiguration.class);
            assertThat(configuration.getBooleanProperty(CONCURRENT_DEPLOYMENT), is(false));
        } finally {
            server.stop();
        }
    }

    private static Config config(String message) {
        return Config.just(ConfigSources.create(Map.of("server.port", "0", "server-lifecycle.message", message)));
    }

    @ApplicationScoped
    @Path("/server-lifecycle/captured")
    public static class CapturedResource {
        @GET
        public String message() {
            return ConfigProvider.getConfig().getOptionalValue("server-lifecycle.message", String.class).orElse("default");
        }
    }

    @ApplicationScoped
    @Path("/server-lifecycle/later")
    public static class LaterResource {
        @GET
        public String message() {
            return "later";
        }
    }
}
