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
import io.helidon.json.JsonParser;
import io.helidon.json.binding.JsonDeserializer;
import io.helidon.json.binding.JsonSerializer;
import io.helidon.service.registry.Service;

interface SerializerOnlyView {
    String value();
}

interface NestedSerializerOnlyView extends SerializerOnlyView {
}

record SerializerOnlyEntity(String value, String secret) implements SerializerOnlyView {
}

record NestedSerializerOnlyEntity(String value, String secret) implements NestedSerializerOnlyView {
}

class SerializerOnlyBase implements SerializerOnlyView {
    private final String value;

    SerializerOnlyBase(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}

final class InheritedSerializerOnlyEntity extends SerializerOnlyBase {
    InheritedSerializerOnlyEntity(String value) {
        super(value);
    }
}

interface ParameterizedSerializerOnlyView<T> {
    T value();
}

record ParameterizedSerializerOnlyEntity(String value) implements ParameterizedSerializerOnlyView<String> {
}

@Service.Singleton
final class SerializerOnlyEntitySerializer implements JsonSerializer<SerializerOnlyView> {
    private static final GenericType<SerializerOnlyView> TYPE = GenericType.create(SerializerOnlyView.class);

    @Override
    public void serialize(JsonGenerator generator, SerializerOnlyView instance, boolean writeNulls) {
        generator.write(instance.value());
    }

    @Override
    public GenericType<SerializerOnlyView> type() {
        return TYPE;
    }
}

@Service.Singleton
final class SerializerOnlyEntityDeserializer implements JsonDeserializer<SerializerOnlyView> {
    private static final GenericType<SerializerOnlyView> TYPE = GenericType.create(SerializerOnlyView.class);

    @Override
    public SerializerOnlyView deserialize(JsonParser parser) {
        return new SerializerOnlyEntity(parser.readString(), null);
    }

    @Override
    public GenericType<SerializerOnlyView> type() {
        return TYPE;
    }
}

@Service.Singleton
final class ParameterizedSerializerOnlyEntitySerializer
        implements JsonSerializer<ParameterizedSerializerOnlyView<String>> {
    private static final GenericType<ParameterizedSerializerOnlyView<String>> TYPE = new GenericType<>() { };

    @Override
    public void serialize(JsonGenerator generator,
                          ParameterizedSerializerOnlyView<String> instance,
                          boolean writeNulls) {
        generator.write(instance.value());
    }

    @Override
    public GenericType<ParameterizedSerializerOnlyView<String>> type() {
        return TYPE;
    }
}
