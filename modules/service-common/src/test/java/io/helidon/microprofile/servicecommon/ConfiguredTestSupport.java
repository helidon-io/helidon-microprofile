/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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
package io.helidon.microprofile.servicecommon;

import io.helidon.config.Config;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.HttpRouting;

/**
 * Test SE service which does not really expose its own endpoint but does use config to set an "importance" value.
 */
public class ConfiguredTestSupport implements HttpFeature {

    static final String ENDPOINT_PATH = "/testendpoint";
    private static final HttpService EMPTY_SERVICE = rules -> {
    };

    private final int importance;
    private final String context;
    private final boolean enabled;
    private final String routing;

    /**
     * Initialization.
     *
     * @param builder builder for the service support instance.
     */
    private ConfiguredTestSupport(Builder builder) {
        importance = builder.importance;
        enabled = builder.enabled;
        routing = builder.routing;
        String configuredContext = builder.webContext;
        context = configuredContext.startsWith("/") ? configuredContext : "/" + configuredContext;
    }

    static Builder builder() {
        return new Builder();
    }

    @Override
    public void setup(HttpRouting.Builder routing) {
        setup(routing, routing);
    }

    @Override
    public String socket() {
        return routing == null ? WebServer.DEFAULT_SOCKET_NAME : routing;
    }

    void setup(HttpRouting.Builder defaultRouting, HttpRouting.Builder featureRouting) {
        if (enabled) {
            featureRouting.register(context, EMPTY_SERVICE);
        }
    }

    int importance() {
        return importance;
    }

    static class Builder implements io.helidon.common.Builder<Builder, ConfiguredTestSupport> {

        private int importance;
        private String webContext = ENDPOINT_PATH;
        private String routing;
        private boolean enabled = true;

        @Override
        public ConfiguredTestSupport build() {
            return new ConfiguredTestSupport(this);
        }

        public Builder config(Config config) {
            config.get("web-context").asString().ifPresent(this::webContext);
            config.get("routing").asString().ifPresent(this::routing);
            config.get("enabled").asBoolean().ifPresent(this::enabled);
            config.get("importance").asInt().ifPresent(this::importance);
            return this;
        }

        public Builder webContext(String path) {
            webContext = path;
            return this;
        }

        public Builder routing(String routing) {
            this.routing = routing;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder importance(int value) {
            importance = value;
            return this;
        }
    }
}
