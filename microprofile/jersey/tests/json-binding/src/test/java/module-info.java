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

@SuppressWarnings("requires-automatic")
open module io.helidon.jersey.tests.media.json.binding {
    requires io.helidon.common;
    requires io.helidon.common.buffers;
    requires io.helidon.common.types;
    requires io.helidon.config;
    requires io.helidon.jersey.media.json.binding;
    requires io.helidon.jersey.webserver;
    requires io.helidon.json;
    requires io.helidon.json.binding;
    requires io.helidon.service.registry;
    requires io.helidon.webserver;
    requires jakarta.inject;
    requires jakarta.json.bind;
    requires jakarta.validation;
    requires jakarta.ws.rs;
    requires jersey.client;
    requires jersey.hk2;
    requires jersey.media.json.binding;
    requires jersey.server;
    requires org.junit.jupiter.api;
}
