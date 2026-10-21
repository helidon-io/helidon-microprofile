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

package io.helidon.microprofile.arquillian;

import java.util.function.Consumer;

import io.helidon.microprofile.server.Server;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;

/**
 * A portable extension that can supply a {@link WebServer} to a caller who is starting a {@link Server} in case the
 * {@link WebServer} needs cleanup in the case of a failure.
 *
 * @see ServerRunner#start(org.eclipse.microprofile.config.Config, int, java.util.Optional)
 * @see ServerRunner#stop()
 */
public class ServerCleanupExtension implements Extension {
    private static final ScopedValue<Consumer<WebServer>> SERVER_STARTED = ScopedValue.newInstance();

    static Server start(Server server, Consumer<WebServer> started) {
        // Connect the discovered extension to this runner only for the duration of startup.
        return ScopedValue.where(SERVER_STARTED, started).call(server::start);
    }

    private void afterDeploymentValidation(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
        if (SERVER_STARTED.isBound()) {
            Consumer<WebServer> afterStartConsumer = SERVER_STARTED.get();
            beanManager.getExtension(ServerCdiExtension.class).serverRoutingBuilder().addFeature(new HttpFeature() {
                @Override
                public void setup(HttpRouting.Builder routing) {
                }

                @Override
                public void afterStart(WebServer webServer) {
                    afterStartConsumer.accept(webServer);
                }
            });
        }
    }
}
