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

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import io.helidon.openapi.OpenApiFormat;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Document and annotation-value I/O. All JSON-P and active SnakeYAML use is confined to this codec.
 */
final class OpenApiCodec {
    private OpenApiCodec() {
    }

    static OpenAPI parseDocument(String content) {
        return OpenApiModelMapper.read(OpenAPI.class, readTree(new StringReader(content)));
    }

    static Schema parseSchema(String content) {
        return OpenApiModelMapper.read(Schema.class, readTree(new StringReader(content)));
    }

    static Object readTree(Reader reader) {
        DumperOptions options = new DumperOptions();
        LoaderOptions loaderOptions = new LoaderOptions();
        try {
            return new Yaml(new JsonCompatibleConstructor(loaderOptions), new Representer(options), options,
                            loaderOptions, new JsonCompatibleResolver()).load(reader);
        } catch (YAMLException | NumberFormatException _) {
            throw new IllegalArgumentException("OpenAPI document must contain valid JSON or YAML with scalar string keys");
        }
    }

    static String format(OpenAPI model, OpenApiFormat format) {
        Object tree = OpenApiModelMapper.write(model);
        if (format == OpenApiFormat.JSON) {
            StringWriter writer = new StringWriter();
            try (JsonWriter json = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true)).createWriter(writer)) {
                json.writeObject(toJson(tree).asJsonObject());
            }
            return writer.toString();
        }
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setSplitLines(false);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setNonPrintableStyle(DumperOptions.NonPrintableStyle.ESCAPE);
        return new Yaml(options).dump(tree);
    }

    static Object parseAnnotationValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.valueOf(text);
        }
        if (text.equals("null")) {
            return OpenApiModelMapper.explicitNull();
        }
        char first = text.charAt(0);
        boolean numeric = first == '-' || Character.isDigit(first);
        if (first != '{' && first != '[' && first != '"' && !numeric) {
            return text;
        }
        try (JsonParser parser = Json.createParser(new StringReader(text))) {
            if (!parser.hasNext()) {
                throw new IllegalArgumentException("OpenAPI annotation value must contain valid JSON");
            }
            parser.next();
            Object result = fromJson(parser.getValue());
            if (parser.hasNext()) {
                throw new IllegalArgumentException("OpenAPI annotation value must contain a single JSON value");
            }
            return result;
        } catch (RuntimeException _) {
            if (numeric) {
                // Preserve numeric-looking annotation strings such as durations with a unit suffix.
                return text;
            }
            throw new IllegalArgumentException("OpenAPI annotation value must contain valid JSON");
        }
    }

    private static JsonValue toJson(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObjectBuilder builder = Json.createObjectBuilder();
            map.forEach((key, child) -> builder.add(key.toString(), toJson(child)));
            return builder.build();
        }
        if (value instanceof Iterable<?> values) {
            JsonArrayBuilder builder = Json.createArrayBuilder();
            values.forEach(child -> builder.add(toJson(child)));
            return builder.build();
        }
        if (value instanceof String text) {
            return Json.createValue(text);
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (value instanceof BigDecimal number) {
            return Json.createValue(number);
        }
        if (value instanceof BigInteger number) {
            return Json.createValue(number);
        }
        if (value instanceof Number number) {
            return Json.createValue(new BigDecimal(number.toString()));
        }
        throw new IllegalArgumentException(
                "OpenAPI JSON values must be strings, finite numbers, booleans, null, arrays, or objects");
    }

    private static Object fromJson(JsonValue value) {
        return switch (value.getValueType()) {
            case NULL -> null;
            case TRUE -> true;
            case FALSE -> false;
            case STRING -> ((JsonString) value).getString();
            case NUMBER -> ((JsonNumber) value).numberValue();
            case ARRAY -> {
                var result = new ArrayList<>();
                value.asJsonArray().forEach(child -> result.add(fromJson(child)));
                yield result;
            }
            case OBJECT -> {
                Map<String, Object> result = new LinkedHashMap<>();
                value.asJsonObject().forEach((key, child) -> result.put(key, fromJson(child)));
                yield result;
            }
        };
    }

    private static final class JsonCompatibleResolver extends Resolver {
        @Override
        public Tag resolve(NodeId kind, String value, boolean implicit) {
            if (kind == NodeId.scalar && implicit) {
                if (value.isEmpty() || value.equals("null")) {
                    return Tag.NULL;
                }
                if (value.equals("true") || value.equals("false")) {
                    return Tag.BOOL;
                }
                return numberTag(value);
            }
            return super.resolve(kind, value, implicit);
        }
        // Recognize the JSON number grammar in one forward scan, without regex backtracking.
        private static Tag numberTag(String value) {
            int length = value.length();
            int position = value.startsWith("-") ? 1 : 0;
            if (position == length) {
                return Tag.STR;
            }
            char first = value.charAt(position);
            if (first == '0') {
                position++;
            } else if (first >= '1' && first <= '9') {
                position = digits(value, position);
            } else {
                return Tag.STR;
            }
            Tag tag = Tag.INT;
            if (position < length && value.charAt(position) == '.') {
                int start = position + 1;
                position = digits(value, start);
                if (position == start) {
                    return Tag.STR;
                }
                tag = Tag.FLOAT;
            }
            if (position < length && (value.charAt(position) == 'e' || value.charAt(position) == 'E')) {
                position++;
                if (position < length && (value.charAt(position) == '+' || value.charAt(position) == '-')) {
                    position++;
                }
                int start = position;
                position = digits(value, start);
                if (position == start) {
                    return Tag.STR;
                }
                tag = Tag.FLOAT;
            }
            return position == length ? tag : Tag.STR;
        }

        private static int digits(String value, int start) {
            int position = start;
            while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                position++;
            }
            return position;
        }
    }

    private static final class JsonCompatibleConstructor extends SafeConstructor {
        JsonCompatibleConstructor(LoaderOptions options) {
            super(options);
            yamlConstructors.put(Tag.FLOAT, new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    return new BigDecimal(((ScalarNode) node).getValue());
                }
            });
        }

        @Override
        protected void constructMapping2ndStep(MappingNode node, Map<Object, Object> mapping) {
            for (var entry : node.getValue()) {
                if (!(entry.getKeyNode() instanceof ScalarNode key)) {
                    throw new YAMLException("OpenAPI object keys must be scalar strings");
                }
                key.setTag(Tag.STR);
            }
            super.constructMapping2ndStep(node, mapping);
        }
    }
}
