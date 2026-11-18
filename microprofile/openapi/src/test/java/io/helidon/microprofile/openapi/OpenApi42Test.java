/*
 * Copyright (c) 2020, 2026 Oracle and/or its affiliates.
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
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import io.helidon.openapi.OpenApiFormat;
import io.helidon.common.Errors;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.info.Contact;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenApi42Test {
    @Test
    void roundTripSchemaKeywords() {
        String document = """
                openapi: 3.1.0
                jsonSchemaDialect: https://json-schema.org/draft/2020-12/schema
                info:
                  title: Example
                  version: '1'
                  summary: A summary
                  license:
                    name: Apache
                    identifier: Apache-2.0
                webhooks:
                  notify:
                    post:
                      summary: Notification
                components:
                  pathItems:
                    reusable:
                      get:
                        summary: Reusable
                  schemas:
                    Allowed: true
                    Forbidden: false
                    Example:
                      $schema: https://json-schema.org/draft/2020-12/schema
                      $comment: A comment
                      type: [string, 'null']
                      const: {enabled: true, count: 123456789012345678901234567890}
                      enum: [1, true, null, {a: b}]
                      examples: [null, false, {value: [1, 2]}]
                      exclusiveMinimum: 0.125
                      if: {type: string}
                      then: {minLength: 1}
                      else: false
                      dependentSchemas: {a: true}
                      dependentRequired: {a: [b, c]}
                      prefixItems: [{type: number}, false]
                      patternProperties: {'^x-': {type: integer}}
                      unevaluatedProperties: false
                      additionalProperties: true
                      customKeyword: {nested: [1, null, true]}
                      x-extra: {nothing: null}
                """;
        OpenAPI model = parse(document);
        assertThat(model.getComponents().getSchemas().get("Allowed").getBooleanSchema(), is(true));
        Schema schema = model.getComponents().getSchemas().get("Example");
        assertThat(schema.getType(), contains(Schema.SchemaType.STRING, Schema.SchemaType.NULL));
        assertThat(schema.getExclusiveMinimum(), is(new BigDecimal("0.125")));
        assertThat(schema.getElseSchema().getBooleanSchema(), is(false));
        assertThat(schema.getDependentRequired().get("a"), contains("b", "c"));
        assertThat(model.getInfo().getSummary(), is("A summary"));
        for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
            String output = format(model, format);
            assertThat(output, not(containsString("!!")));
            Object actual = new Yaml().load(output);
            Object expected = new Yaml().load(document);
            assertThat(actual, is(expected));
            assertThat(parse(output).getJsonSchemaDialect(), is(model.getJsonSchemaDialect()));
        }
    }

    @Test
    void normalizeLegacySchema() {
        OpenAPI model = parse("""
                openapi: 3.0.3
                components:
                  schemas:
                    Example:
                      type: number
                      nullable: true
                      minimum: 1.25
                      maximum: 10
                      exclusiveMinimum: true
                      exclusiveMaximum: false
                """);
        Schema schema = model.getComponents().getSchemas().get("Example");
        assertThat(schema.getType(), contains(Schema.SchemaType.NUMBER, Schema.SchemaType.NULL));
        assertThat(schema.getMinimum(), nullValue());
        assertThat(schema.getExclusiveMinimum(), is(new BigDecimal("1.25")));
        assertThat(schema.getMaximum(), is(BigDecimal.TEN));
        assertThat(schema.getExclusiveMaximum(), nullValue());
        assertThat(format(model, OpenApiFormat.JSON), not(containsString("nullable")));
    }

    @Test
    void preserveNumericPrecision() {
        OpenAPI model = OASFactory.createOpenAPI().openapi("3.1.0").components(OASFactory.createComponents()
                .addSchema("Example", OASFactory.createSchema()
                        .multipleOf(new BigDecimal("0.000000000000000000123456789"))
                        .maximum(new BigDecimal("123456789012345678901234567890.125"))));
        for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
            Schema schema = parse(format(model, format)).getComponents().getSchemas().get("Example");
            assertThat(schema.getMultipleOf(), is(new BigDecimal("0.000000000000000000123456789")));
            assertThat(schema.getMaximum(), is(new BigDecimal("123456789012345678901234567890.125")));
        }
    }

    @Test
    void referencesRetainSiblingsAndMapEntries() {
        OpenAPI model = parse("""
                openapi: 3.1.0
                paths:
                  /test:
                    $ref: '#/components/pathItems/test'
                    summary: Path summary
                    get:
                      responses:
                        default: {description: Default response}
                        '200': {$ref: '#/components/responses/test', description: Sibling}
                """);
        for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
            OpenAPI copy = parse(format(model, format));
            assertThat(copy.getPaths().getPathItem("/test").getSummary(), is("Path summary"));
            assertThat(copy.getPaths().getPathItem("/test").getGET().getResponses().getDefaultValue().getDescription(),
                       is("Default response"));
            assertThat(copy.getPaths().getPathItem("/test").getGET().getResponses().getAPIResponse("200").getDescription(),
                       is("Sibling"));
        }
    }

    @Test
    void reportIndependentConstraintsWithoutRejectedValues() {
        var failure = assertThrows(Errors.ErrorMessagesException.class, () -> parse("""
                components:
                  schemas:
                    Example:
                      minimum: secret-minimum
                      maximum: secret-maximum
                      exclusiveMinimum: secret-exclusive
                      minLength: 1.25
                """));
        assertThat(failure.getMessage(), containsString("Example.minimum"));
        assertThat(failure.getMessage(), containsString("Example.maximum"));
        assertThat(failure.getMessage(), containsString("Example.exclusiveMinimum"));
        assertThat(failure.getMessage(), containsString("Example.minLength"));
        assertThat(failure.getMessage(), not(containsString("secret-")));
        assertThat(failure.getCause(), nullValue());
    }

    @Test
    void preserveAlternativeSchemaDialect() {
        String content = """
                components:
                  schemas:
                    Example:
                      $schema: https://example.test/custom-schema
                      type: {custom: [true, null]}
                      minimum: alternative
                      custom: [1, 2, 3]
                """;
        OpenAPI model = parse(content);
        for (OpenApiFormat format : List.of(OpenApiFormat.JSON, OpenApiFormat.YAML)) {
            Object expected = new Yaml().load(content);
            Object actual = new Yaml().load(format(model, format));
            assertThat(actual, is(expected));
        }
    }

    @Test
    void preservePublicHelperImplementationSelection() {
        Map<Class<?>, ExpandedTypeDescription> descriptions = new LinkedHashMap<>(OpenApiHelper.types());
        descriptions.put(Contact.class, ExpandedTypeDescription.create(Contact.class, CustomContact.class));
        Contact contact = OpenApiParser.parse(descriptions, Contact.class, new StringReader("name: Contact"));
        assertThat(contact, instanceOf(CustomContact.class));
        assertThat(contact.getName(), is("Contact"));
    }

    @Test
    void restrictSmallRyeImportsToAdapter() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!file.getFileName().toString().equals("SmallRyeOpenApiEngine.java")) {
                    assertThat(file.toString(), Files.readString(file), not(containsString("import io.smallrye.openapi")));
                }
            }
        }
    }

    private static OpenAPI parse(String content) {
        return OpenApiParser.parse(OpenApiHelper.types(), OpenAPI.class, new StringReader(content));
    }

    private static String format(OpenAPI model, OpenApiFormat format) {
        StringWriter writer = new StringWriter();
        OpenApiSerializer.serialize(OpenApiHelper.types(), model, format, writer);
        return writer.toString();
    }
    public static class CustomContact implements Contact {
        private String name;
        private String url;
        private String email;
        private Map<String, Object> extensions = new LinkedHashMap<>();

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public void setUrl(String url) {
            this.url = url;
        }

        @Override
        public String getEmail() {
            return email;
        }

        @Override
        public void setEmail(String email) {
            this.email = email;
        }

        @Override
        public Map<String, Object> getExtensions() {
            return extensions;
        }

        @Override
        public void setExtensions(Map<String, Object> extensions) {
            this.extensions = extensions;
        }

        @Override
        public Contact addExtension(String name, Object value) {
            extensions.put(name, value);
            return this;
        }

        @Override
        public void removeExtension(String name) {
            extensions.remove(name);
        }
    }

}
