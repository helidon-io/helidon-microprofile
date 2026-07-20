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

import io.helidon.common.Api;

import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.FeatureContext;
import org.glassfish.jersey.internal.spi.ForcedAutoDiscoverable;

/**
 * Registers the Helidon JSON Binding entity provider with Jersey.
 */
public class JsonBindingAutoDiscoverable implements ForcedAutoDiscoverable {
    /**
     * Required public constructor for {@link java.util.ServiceLoader}.
     */
    @Api.Internal
    public JsonBindingAutoDiscoverable() {
    }

    @Override
    public void configure(FeatureContext context) {
        if (!context.getConfiguration().isRegistered(JsonBindingProvider.class)) {
            context.register(JsonBindingProvider.create(), Priorities.ENTITY_CODER);
        }
    }
}
