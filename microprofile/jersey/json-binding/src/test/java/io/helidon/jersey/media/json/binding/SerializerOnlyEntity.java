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
import io.helidon.json.JsonGenerator;
import io.helidon.json.binding.JsonSerializer;
import io.helidon.service.registry.Service;

record SerializerOnlyEntity(String value) {
}

@Service.Singleton
final class SerializerOnlyEntitySerializer implements JsonSerializer<SerializerOnlyEntity> {
    private static final GenericType<SerializerOnlyEntity> TYPE = GenericType.create(SerializerOnlyEntity.class);

    @Override
    public void serialize(JsonGenerator generator, SerializerOnlyEntity instance, boolean writeNulls) {
        generator.write(instance.value());
    }

    @Override
    public GenericType<SerializerOnlyEntity> type() {
        return TYPE;
    }
}
