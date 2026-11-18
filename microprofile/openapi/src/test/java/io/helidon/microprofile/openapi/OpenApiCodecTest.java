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

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.helidon.common.Errors;
import io.helidon.openapi.OpenApiFormat;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import static io.helidon.microprofile.openapi.TestUtil.config;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenApiCodecTest {
    @Test
    void modelReaderCharactersBecomeJsonStrings() {
        Schema schema = OASFactory.createSchema().schemaDialect("https://example.test/custom-schema")
                .set("character", 'a').set("nested", Map.of("character", '\u00a0'));
        OpenAPI model = OASFactory.createOpenAPI().openapi("3.1.0")
                .components(OASFactory.createComponents().addSchema("Custom", schema));
        for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
            OpenAPI copy = OpenApiCodec.parseDocument(OpenApiCodec.format(model, format));
            JsonObject custom = json(OpenApiCodec.format(copy, OpenApiFormat.JSON))
                    .getJsonObject("components").getJsonObject("schemas").getJsonObject("Custom");
            assertThat(custom.getString("character"), is("a"));
            assertThat(custom.getJsonObject("nested").getString("character"), is("\u00a0"));
        }
    }

    @Test
    void jsonEscapingUsesJsonRules() {
        String text = "Non-breaking\u00a0space, separators\u0085\u2028\u2029, controls\u0000\u0007\u000b\u001b, quote\" and slash\\";
        OpenAPI model = OASFactory.createOpenAPI().openapi("3.1.0")
                .info(OASFactory.createInfo().title(text).description(text).version("1"));
        JsonObject json = json(OpenApiCodec.format(model, OpenApiFormat.JSON));
        assertThat(json.getJsonObject("info").getString("title"), is(text));
        assertThat(json.getJsonObject("info").getString("description"), is(text));
        assertThat(OpenApiCodec.parseDocument(OpenApiCodec.format(model, OpenApiFormat.YAML)).getInfo().getTitle(), is(text));
    }

    @Test
    void explicitNullSurvivesMappingMergingCopyingAndBothFormats() {
        String input = """
                openapi: 3.1.0
                components:
                  schemas:
                    NullOnly:
                      const: null
                      default: null
                      example: null
                      examples: [null, {nested: null}]
                      x-null: null
                      custom-null: null
                """;
        OpenAPI parsed = OpenApiCodec.parseDocument(input);
        OpenAPI assembled = OpenApiEngine.create().load(config(Map.of("mp.openapi.scan.disable", "true")),
                getClass().getClassLoader(), List::of, input);
        for (OpenAPI model : List.of(parsed, assembled)) {
            for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
                OpenAPI copy = OpenApiCodec.parseDocument(OpenApiCodec.format(model, format));
                JsonObject schema = json(OpenApiCodec.format(copy, OpenApiFormat.JSON))
                        .getJsonObject("components").getJsonObject("schemas").getJsonObject("NullOnly");
                for (String property : List.of("const", "default", "example", "x-null", "custom-null")) {
                    assertThat(property + " must remain present", schema.containsKey(property), is(true));
                    assertThat(property + " must remain JSON null", schema.get(property), is(JsonValue.NULL));
                }
                assertThat(schema.getJsonArray("examples").get(0), is(JsonValue.NULL));
                assertThat(schema.getJsonArray("examples").getJsonObject(1).get("nested"), is(JsonValue.NULL));
            }
        }
    }

    @Test
    void legacyNullableRequiresAnExplicitLocalType() {
        OpenAPI model = OpenApiCodec.parseDocument("""
                openapi: 3.0.3
                components:
                  schemas:
                    Base: {type: object}
                    Composed: {nullable: true, allOf: [{$ref: '#/components/schemas/Base'}]}
                    Unconstrained: {nullable: true}
                    Typed: {type: string, nullable: true}
                """);
        var schemas = model.getComponents().getSchemas();
        assertThat(schemas.get("Composed").getType(), nullValue());
        assertThat(schemas.get("Composed").getAllOf().getFirst().getRef(), is("#/components/schemas/Base"));
        assertThat(schemas.get("Unconstrained").getType(), nullValue());
        assertThat(schemas.get("Typed").getType(), contains(Schema.SchemaType.STRING, Schema.SchemaType.NULL));
    }

    @Test
    void legacyRulesDoNotInterpret31CustomKeywords() {
        OpenAPI model = OpenApiCodec.parseDocument("""
                openapi: 3.1.1
                components:
                  schemas:
                    Example: {type: string, nullable: true}
                """);
        Schema schema = model.getComponents().getSchemas().get("Example");
        assertThat(schema.getType(), contains(Schema.SchemaType.STRING));
        assertThat(schema.get("nullable"), is(true));
        OpenAPI assembled = OpenApiEngine.create().load(config(Map.of("mp.openapi.scan.disable", "true")),
                getClass().getClassLoader(), List::of, OpenApiCodec.format(model, OpenApiFormat.JSON));
        assertThat(assembled.getOpenapi(), is("3.1.1"));
        assertThrows(Errors.ErrorMessagesException.class, () -> OpenApiCodec.parseDocument("""
                openapi: 3.1.0
                components:
                  schemas:
                    Example: {minimum: 0, exclusiveMinimum: true}
                """));
    }

    @Test
    void yamlResolvesJsonScalarsAndKeepsStringKeys() {
        OpenAPI model = OpenApiCodec.parseDocument("""
                openapi: 3.1.0
                components:
                  schemas:
                    Example:
                      type: object
                      properties:
                        on: {type: string, enum: [on, off, yes, no, TRUE, False]}
                        true: {type: string}
                        null: {type: string}
                        200: {type: string}
                      example: {on: off, enabled: true, number: 1.25, nothing: null, date: 2026-11-18}
                """);
        for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
            OpenAPI copy = OpenApiCodec.parseDocument(OpenApiCodec.format(model, format));
            Schema schema = copy.getComponents().getSchemas().get("Example");
            assertThat(schema.getProperties().keySet(), contains("on", "true", "null", "200"));
            assertThat(schema.getProperties().get("on").getEnumeration(), contains("on", "off", "yes", "no", "TRUE", "False"));
            Map<?, ?> example = (Map<?, ?>) schema.getExample();
            assertThat(example.get("on"), is("off"));
            assertThat(example.get("enabled"), is(true));
            assertThat(example.containsKey("nothing"), is(true));
            assertThat(example.get("date"), is("2026-11-18"));
        }
    }

    @Test
    void documentAndNestedDialectDefaultsAreInherited() {
        String input = """
                openapi: 3.1.0
                jsonSchemaDialect: https://example.test/custom-schema
                components:
                  schemas:
                    Inherited:
                      type: {custom: [true, null]}
                      minimum: alternative
                      custom-null: null
                    StandardOverride:
                      $schema: https://json-schema.org/draft/2020-12/schema
                      type: object
                      properties:
                        Child:
                          $schema: https://example.test/custom-schema
                          type: {custom: true}
                          properties: {Grandchild: {minimum: alternative}}
                """;
        OpenAPI parsed = OpenApiCodec.parseDocument(input);
        OpenAPI assembled = OpenApiEngine.create().load(config(Map.of("mp.openapi.scan.disable", "true")),
                getClass().getClassLoader(), List::of, input);
        JsonObject expected = json(OpenApiCodec.format(parsed, OpenApiFormat.JSON));
        for (OpenAPI model : List.of(parsed, assembled)) {
            for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
                OpenAPI copy = OpenApiCodec.parseDocument(OpenApiCodec.format(model, format));
                JsonObject actual = json(OpenApiCodec.format(copy, OpenApiFormat.JSON));
                // Assembly supplies required info/paths defaults; schema contents and dialect must be preserved exactly.
                assertThat(actual.get("components"), is(expected.get("components")));
                assertThat(actual.get("jsonSchemaDialect"), is(expected.get("jsonSchemaDialect")));
            }
        }
        assertThat(parsed.getComponents().getSchemas().get("StandardOverride").getType(), contains(Schema.SchemaType.OBJECT));
    }

    @Test
    void annotationParsingPreservesJsonValuesAndNumericLookingText() {
        assertThat(OpenApiCodec.parseAnnotationValue("15 milliseconds"), is("15 milliseconds"));
        assertThat(OpenApiCodec.parseAnnotationValue("\"quoted text\""), is("quoted text"));
        Map<?, ?> value = (Map<?, ?>) OpenApiCodec.parseAnnotationValue("{\"nothing\":null,\"values\":[null,true]}");
        assertThat(value.containsKey("nothing"), is(true));
        assertThat(value.get("nothing"), nullValue());
        assertThrows(IllegalArgumentException.class, () -> OpenApiCodec.parseAnnotationValue("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> OpenApiCodec.parseAnnotationValue("{invalid}"));
    }

    @Test
    void normalProcessingDoesNotDependOnLegacyTypeDescriptions() throws Exception {
        Path sources = Path.of("src/main/java/io/helidon/microprofile/openapi");
        for (String name : List.of("MpOpenApiManager", "SmallRyeOpenApiEngine", "OpenApiCodec",
                                  "OpenApiModelMapper", "OpenApiModelRegistry")) {
            String source = Files.readString(sources.resolve(name + ".java"));
            assertThat(name, source, not(containsString("ExpandedTypeDescription")));
            assertThat(name, source, not(containsString("OpenApiHelper")));
            if (!name.equals("OpenApiCodec")) {
                assertThat(name, source, not(containsString("import jakarta.json")));
                assertThat(name, source, not(containsString("import org.yaml.snakeyaml")));
            }
        }
    }

    private static JsonObject json(String text) {
        try (var reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        }
    }
}
