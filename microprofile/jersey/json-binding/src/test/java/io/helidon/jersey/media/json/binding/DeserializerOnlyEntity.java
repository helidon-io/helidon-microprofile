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

import io.helidon.common.GenericType;
import io.helidon.json.JsonParser;
import io.helidon.json.binding.JsonDeserializer;
import io.helidon.service.registry.Service;

record DeserializerOnlyEntity(String value) {
}

@Service.Singleton
final class DeserializerOnlyEntityDeserializer implements JsonDeserializer<DeserializerOnlyEntity> {
    private static final GenericType<DeserializerOnlyEntity> TYPE = GenericType.create(DeserializerOnlyEntity.class);

    @Override
    public DeserializerOnlyEntity deserialize(JsonParser parser) {
        return new DeserializerOnlyEntity(parser.readString());
    }

    @Override
    public GenericType<DeserializerOnlyEntity> type() {
        return TYPE;
    }
}
