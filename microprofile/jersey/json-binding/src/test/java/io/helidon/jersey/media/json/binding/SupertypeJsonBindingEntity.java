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

import java.io.Serial;
import java.io.Serializable;

import io.helidon.json.binding.Json;

@Json.Entity
class SupertypeJsonBindingEntity extends JsonBindingProviderTest.AbstractJsonBindingEntity
        implements JsonBindingProviderTest.JsonBindingMarker, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String message;
    @Json.Ignore
    private String secret;

    SupertypeJsonBindingEntity() {
    }

    SupertypeJsonBindingEntity(String message, String secret) {
        this.message = message;
        this.secret = secret;
    }

    String message() {
        return message;
    }

    void message(String message) {
        this.message = message;
    }

    String secret() {
        return secret;
    }

    void secret(String secret) {
        this.secret = secret;
    }
}
