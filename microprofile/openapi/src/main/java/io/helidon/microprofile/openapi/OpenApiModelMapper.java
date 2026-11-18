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
package io.helidon.microprofile.openapi;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.helidon.common.Errors;
import io.helidon.microprofile.openapi.OpenApiModelRegistry.ModelProperty;

import org.eclipse.microprofile.openapi.models.Constructible;
import org.eclipse.microprofile.openapi.models.Extensible;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;

import static io.helidon.microprofile.openapi.OpenApiModelRegistry.invoke;

/**
 * Maps standard MP models to plain data trees with explicit source-version and schema-dialect context.
 */
final class OpenApiModelMapper {
    private static final String OAS_DIALECT = "https://spec.openapis.org/oas/3.1/dialect/base";
    private static final String JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    private OpenApiModelMapper() {
    }

    static <T> T read(Class<T> type, Object tree) {
        return read(type, tree, OpenApiModelRegistry::create);
    }

    static <T> T read(Class<T> type, Object tree, Function<Class<?>, Constructible> factory) {
        Errors.Collector errors = Errors.collector();
        ReadContext context = new ReadContext("3.1.0", OAS_DIALECT);
        if (type == OpenAPI.class && tree instanceof Map<?, ?> map) {
            context = new ReadContext(map.get("openapi") instanceof String version ? version : "3.1.0",
                                      map.get("jsonSchemaDialect") instanceof String dialect ? dialect : OAS_DIALECT);
        }
        Object result = readValue(type, tree, context, factory, errors, type.getSimpleName());
        errors.collect().checkValid();
        return type.cast(result);
    }

    static Object write(Object model) {
        String dialect = model instanceof OpenAPI api && api.getJsonSchemaDialect() != null
                ? api.getJsonSchemaDialect() : OAS_DIALECT;
        return writeValue(model, dialect);
    }

    static Object explicitNull() {
        return JsonNull.INSTANCE;
    }

    private static Object writeValue(Object value, String dialect) {
        if (value == null || value == JsonNull.INSTANCE) {
            return null;
        }
        if (value instanceof Schema schema) {
            if (schema.getBooleanSchema() != null) {
                return schema.getBooleanSchema();
            }
            dialect = schema.getSchemaDialect() == null ? dialect : schema.getSchemaDialect();
            if (!standardDialect(dialect)) {
                return writeValue(schema.getAll(), dialect);
            }
        }
        if (value instanceof Constructible) {
            Class<?> type = OpenApiModelRegistry.types().stream().filter(t -> t.isInstance(value)).findFirst().orElseThrow();
            Map<String, Object> result = new LinkedHashMap<>();
            String unwrapped = OpenApiModelRegistry.unwrapped(type);
            for (var entry : OpenApiModelRegistry.properties(type).entrySet()) {
                String name = entry.getKey();
                Object property = invoke(entry.getValue().getter(), value);
                if (property == null) {
                    continue;
                }
                if (name.equals(unwrapped)) {
                    append(result, (Map<?, ?>) property, dialect);
                } else if (!name.equals("extensions") && (unwrapped == null || name.equals("$ref"))) {
                    Object converted = writeValue(property, dialect);
                    if (type == Schema.class && name.equals("type") && converted instanceof List<?> list && list.size() == 1) {
                        converted = list.getFirst();
                    }
                    result.put(name, converted);
                }
            }
            if (value instanceof Schema schema) {
                for (var entry : schema.getAll().entrySet()) {
                    if (!result.containsKey(entry.getKey())) {
                        result.put(entry.getKey(), writeValue(entry.getValue(), dialect));
                    }
                }
            }
            if (value instanceof Extensible<?> extensible && extensible.getExtensions() != null) {
                append(result, extensible.getExtensions(), dialect);
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            append(result, map, dialect);
            return result;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object item : values) {
                result.add(writeValue(item, dialect));
            }
            return result;
        }
        if (value instanceof BigDecimal number && number.stripTrailingZeros().scale() <= 0) {
            return number.toBigIntegerExact();
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.toString();
        }
        return value;
    }

    private static void append(Map<String, Object> target, Map<?, ?> source, String dialect) {
        source.forEach((key, value) -> target.put(key.toString(), writeValue(value, dialect)));
    }

    private static Object readValue(Type type,
                                    Object value,
                                    ReadContext context,
                                    Function<Class<?>, Constructible> factory,
                                    Errors.Collector errors,
                                    String field) {
        try {
            return convert(type, value, context, factory, errors, field);
        } catch (IllegalArgumentException | ClassCastException | ArithmeticException _) {
            errors.fatal("OpenAPI field " + field + constraint(type));
            return null;
        }
    }

    private static String constraint(Type type) {
        if (type == BigDecimal.class || type == BigInteger.class) {
            return " must be a finite number";
        }
        if (type == Integer.class) {
            return " must be an integer within the 32-bit signed range";
        }
        if (type == Boolean.class) {
            return " must be a boolean";
        }
        if (type instanceof Class<?> clazz && clazz.isEnum()) {
            return " must use a supported " + clazz.getSimpleName() + " name";
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            return " must be an array with elements of the declared model type";
        }
        return " must be an object with properties of the declared model types";
    }

    private static Object convert(Type type,
                                  Object value,
                                  ReadContext context,
                                  Function<Class<?>, Constructible> factory,
                                  Errors.Collector errors,
                                  String field) {
        if (value == null) {
            return null;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (parameterized.getRawType() == Map.class) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("OpenAPI map property must contain an object");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, child) -> result.put(key.toString(),
                        readValue(arguments[1], child, context, factory, errors, field + "." + key)));
                return result;
            }
            if (parameterized.getRawType() == List.class) {
                List<Object> result = new ArrayList<>();
                Iterable<?> children = value instanceof List<?> list ? list : List.of(value);
                int index = 0;
                for (Object child : children) {
                    result.add(readValue(arguments[0], child, context, factory, errors, field + "[" + index++ + "]"));
                }
                return result;
            }
        }
        if (!(type instanceof Class<?> clazz) || clazz == Object.class) {
            return value;
        }
        if (OpenApiModelRegistry.types().contains(clazz)) {
            Constructible model = factory.apply(clazz);
            if (model instanceof Schema schema && value instanceof Boolean booleanSchema) {
                schema.setBooleanSchema(booleanSchema);
                return model;
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("OpenAPI model property must contain an object");
            }
            ReadContext modelContext = context;
            if (model instanceof Schema schema) {
                modelContext = context.schema(map);
                if (!standardDialect(modelContext.schemaDialect())) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    map.forEach((key, child) -> values.put(key.toString(), child == null ? JsonNull.INSTANCE : child));
                    schema.setAll(values);
                    return model;
                }
            }
            readProperties(model, clazz, map, modelContext, factory, errors, field);
            if (model instanceof Schema schema && modelContext.legacy()) {
                normalizeLegacySchema(schema, map);
            }
            return model;
        }
        if (clazz.isEnum()) {
            return Arrays.stream(clazz.getEnumConstants())
                    .filter(e -> e.toString().equals(value.toString())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("OpenAPI enum property contains an unsupported name"));
        }
        if (clazz == BigDecimal.class) {
            return new BigDecimal(value.toString());
        }
        if (clazz == BigInteger.class) {
            return new BigInteger(value.toString());
        }
        if (clazz == Integer.class && value instanceof Number number) {
            return new BigDecimal(number.toString()).intValueExact();
        }
        if (clazz == Boolean.class && value instanceof String text
                && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.valueOf(text);
        }
        if (clazz == String.class && !(value instanceof String)) {
            return value.toString();
        }
        return clazz.cast(value);
    }

    private static void readProperties(Constructible model,
                                       Class<?> type,
                                       Map<?, ?> map,
                                       ReadContext context,
                                       Function<Class<?>, Constructible> factory,
                                       Errors.Collector errors,
                                       String field) {
        Map<String, ModelProperty> properties = OpenApiModelRegistry.properties(type);
        Map<String, Object> children = new LinkedHashMap<>();
        String unwrapped = OpenApiModelRegistry.unwrapped(type);
        for (var entry : map.entrySet()) {
            String key = entry.getKey().toString();
            Object child = entry.getValue();
            ModelProperty property = properties.get(key);
            if (model instanceof Schema && context.legacy()
                    && (key.equals("nullable") || ((key.equals("exclusiveMinimum") || key.equals("exclusiveMaximum"))
                    && child instanceof Boolean))) {
                continue;
            }
            if (unwrapped != null && !key.equals("$ref") && !key.startsWith("x-")) {
                children.put(key, child);
            } else if (property != null && !key.equals(unwrapped) && !key.equals("extensions")) {
                Object converted = child == null && property.type() == Object.class ? JsonNull.INSTANCE
                        : readValue(property.type(), child, context, factory, errors, field + "." + key);
                if (child == null || converted != null) {
                    invoke(property.setter(), model, converted);
                }
            } else if (key.startsWith("x-") && model instanceof Extensible<?> extensible) {
                extensible.addExtension(key, child == null ? JsonNull.INSTANCE : child);
            } else if (model instanceof Schema schema) {
                schema.set(key, child == null ? JsonNull.INSTANCE : child);
            } else if (unwrapped != null) {
                children.put(key, child);
            }
        }
        if (unwrapped != null) {
            ModelProperty property = properties.get(unwrapped);
            invoke(property.setter(), model, readValue(property.type(), children, context, factory, errors, field));
        }
    }

    private static void normalizeLegacySchema(Schema schema, Map<?, ?> map) {
        // In OpenAPI 3.0 nullable only modifies a type explicitly declared on this schema.
        if (Boolean.TRUE.equals(map.get("nullable")) && map.get("type") instanceof String && schema.getType() != null) {
            List<Schema.SchemaType> types = new ArrayList<>(schema.getType());
            if (!types.contains(Schema.SchemaType.NULL)) {
                types.add(Schema.SchemaType.NULL);
            }
            schema.setType(types);
        }
        if (Boolean.TRUE.equals(map.get("exclusiveMinimum")) && schema.getMinimum() != null) {
            schema.setExclusiveMinimum(schema.getMinimum());
            schema.setMinimum(null);
        }
        if (Boolean.TRUE.equals(map.get("exclusiveMaximum")) && schema.getMaximum() != null) {
            schema.setExclusiveMaximum(schema.getMaximum());
            schema.setMaximum(null);
        }
    }

    private static boolean standardDialect(String dialect) {
        return dialect.equals(OAS_DIALECT) || dialect.equals(JSON_SCHEMA_DIALECT);
    }

    private record ReadContext(String sourceVersion, String schemaDialect) {
        boolean legacy() {
            return sourceVersion.equals("3.0") || sourceVersion.startsWith("3.0.");
        }

        ReadContext schema(Map<?, ?> properties) {
            return properties.get("$schema") instanceof String dialect ? new ReadContext(sourceVersion, dialect) : this;
        }
    }

    // MP model setters use Java null to remove a property. This marker retains an explicit JSON null
    // across model merging and copying; it is converted back to null only at the plain-tree boundary.
    private enum JsonNull {
        INSTANCE;

        @Override
        public String toString() {
            return "null";
        }
    }
}
