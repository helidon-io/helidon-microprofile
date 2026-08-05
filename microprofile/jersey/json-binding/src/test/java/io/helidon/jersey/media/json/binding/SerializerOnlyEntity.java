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

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.GenericType;
import io.helidon.json.JsonGenerator;
import io.helidon.json.JsonParser;
import io.helidon.json.binding.JsonDeserializer;
import io.helidon.json.binding.JsonBindingFactory;
import io.helidon.json.binding.JsonSerializer;
import io.helidon.service.registry.Service;

interface SerializerOnlyView {
    String value();
}

interface NestedSerializerOnlyView extends SerializerOnlyView {
}

interface LeftSerializerOnlyView extends SerializerOnlyView {
}

interface RightSerializerOnlyView extends SerializerOnlyView {
}

interface DiamondSerializerOnlyView extends LeftSerializerOnlyView, RightSerializerOnlyView {
}

record SerializerOnlyEntity(String value, String secret) implements SerializerOnlyView {
}

record NestedSerializerOnlyEntity(String value, String secret) implements NestedSerializerOnlyView {
}

record DiamondSerializerOnlyEntity(String value, String secret) implements DiamondSerializerOnlyView {
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

interface SafeKeyView {
    String safe();
}

interface SharedKeyView {
}

interface LeftKeyView1 extends SharedKeyView {
}

interface RightKeyView1 extends SharedKeyView {
}

interface DiamondKeyView1 extends LeftKeyView1, RightKeyView1 {
}

interface LeftKeyView2 extends DiamondKeyView1 {
}

interface RightKeyView2 extends DiamondKeyView1 {
}

interface DiamondKeyView2 extends LeftKeyView2, RightKeyView2 {
}

interface LeftKeyView3 extends DiamondKeyView2 {
}

interface RightKeyView3 extends DiamondKeyView2 {
}

interface DiamondKeyView3 extends LeftKeyView3, RightKeyView3 {
}

interface LeftKeyView4 extends DiamondKeyView3 {
}

interface RightKeyView4 extends DiamondKeyView3 {
}

interface DiamondKeyView4 extends LeftKeyView4, RightKeyView4 {
}

record SafeKey(String safe, String secret) implements SafeKeyView {
}

record ConflictingKey(String value, String safe) implements SerializerOnlyView, SafeKeyView {
}

record DiamondKey(String safe) implements DiamondKeyView4, SafeKeyView {
}

enum FactoryKey implements SafeKeyView {
    KEY;

    @Override
    public String safe() {
        return name();
    }
}

record FactoryOverloadKey(String value) {
}

@Service.Singleton
final class FactoryOverloadKeyBindingFactory implements JsonBindingFactory<FactoryOverloadKey> {
    @Override
    public JsonDeserializer<FactoryOverloadKey> createDeserializer(Class<? extends FactoryOverloadKey> type) {
        return new FactoryOverloadKeyConverter(false);
    }

    @Override
    public JsonDeserializer<FactoryOverloadKey> createDeserializer(GenericType<? extends FactoryOverloadKey> type) {
        return new FactoryOverloadKeyConverter(false);
    }

    @Override
    public JsonSerializer<FactoryOverloadKey> createSerializer(Class<? extends FactoryOverloadKey> type) {
        return new FactoryOverloadKeyConverter(false);
    }

    @Override
    public JsonSerializer<FactoryOverloadKey> createSerializer(GenericType<? extends FactoryOverloadKey> type) {
        return new FactoryOverloadKeyConverter(true);
    }

    @Override
    public Set<Class<?>> supportedTypes() {
        return Set.of(FactoryOverloadKey.class);
    }
}

final class FactoryOverloadKeyConverter
        implements JsonDeserializer<FactoryOverloadKey>, JsonSerializer<FactoryOverloadKey> {
    private static final GenericType<FactoryOverloadKey> TYPE = GenericType.create(FactoryOverloadKey.class);
    private final boolean mapKeySerializer;

    FactoryOverloadKeyConverter(boolean mapKeySerializer) {
        this.mapKeySerializer = mapKeySerializer;
    }

    @Override
    public void serialize(JsonGenerator generator, FactoryOverloadKey instance, boolean writeNulls) {
        generator.write(instance.value());
    }

    @Override
    public FactoryOverloadKey deserialize(JsonParser parser) {
        return new FactoryOverloadKey(parser.readString());
    }

    @Override
    public boolean isMapKeySerializer() {
        return mapKeySerializer;
    }

    @Override
    public String serializeAsMapKey(FactoryOverloadKey instance) {
        return instance.value();
    }

    @Override
    public GenericType<FactoryOverloadKey> type() {
        return TYPE;
    }
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

@Service.Singleton
final class SafeKeySerializer implements JsonSerializer<SafeKeyView> {
    private static final GenericType<SafeKeyView> TYPE = GenericType.create(SafeKeyView.class);
    private static final AtomicInteger TYPE_ACCESSES = new AtomicInteger();

    static void resetTypeAccesses() {
        TYPE_ACCESSES.set(0);
    }

    static int typeAccesses() {
        return TYPE_ACCESSES.get();
    }

    @Override
    public void serialize(JsonGenerator generator, SafeKeyView instance, boolean writeNulls) {
        generator.write(instance.safe());
    }

    @Override
    public boolean isMapKeySerializer() {
        return true;
    }

    @Override
    public String serializeAsMapKey(SafeKeyView instance) {
        return instance.safe();
    }

    @Override
    public GenericType<SafeKeyView> type() {
        TYPE_ACCESSES.incrementAndGet();
        return TYPE;
    }
}

@Service.Singleton
final class SafeKeyDeserializer implements JsonDeserializer<SafeKey> {
    private static final GenericType<SafeKey> TYPE = GenericType.create(SafeKey.class);

    @Override
    public SafeKey deserialize(JsonParser parser) {
        return new SafeKey(parser.readString(), null);
    }

    @Override
    public GenericType<SafeKey> type() {
        return TYPE;
    }
}

@Service.Singleton
final class ConflictingKeyDeserializer implements JsonDeserializer<ConflictingKey> {
    private static final GenericType<ConflictingKey> TYPE = GenericType.create(ConflictingKey.class);

    @Override
    public ConflictingKey deserialize(JsonParser parser) {
        return new ConflictingKey(parser.readString(), null);
    }

    @Override
    public GenericType<ConflictingKey> type() {
        return TYPE;
    }
}

@Service.Singleton
final class DiamondKeyDeserializer implements JsonDeserializer<DiamondKey> {
    private static final GenericType<DiamondKey> TYPE = GenericType.create(DiamondKey.class);

    @Override
    public DiamondKey deserialize(JsonParser parser) {
        return new DiamondKey(parser.readString());
    }

    @Override
    public GenericType<DiamondKey> type() {
        return TYPE;
    }
}
