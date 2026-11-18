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

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import io.smallrye.openapi.api.OpenApiConfig;
import io.smallrye.openapi.api.OpenApiConfigImpl;
import io.smallrye.openapi.api.OpenApiDocument;
import io.smallrye.openapi.api.util.MergeUtil;
import io.smallrye.openapi.model.BaseModel;
import io.smallrye.openapi.model.Extensions;
import io.smallrye.openapi.runtime.OpenApiProcessor;
import io.smallrye.openapi.runtime.scanner.AnnotationScannerExtension;
import io.smallrye.openapi.runtime.scanner.FilteredIndexView;
import io.smallrye.openapi.runtime.scanner.OpenApiAnnotationScanner;
import io.smallrye.openapi.runtime.scanner.spi.AnnotationScannerContext;
import io.smallrye.openapi.runtime.scanner.spi.AnnotationScannerFactory;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.IndexView;

/**
 * SmallRye-specific scanning, configuration, merging, and model ownership.
 * The lower-level APIs retain Helidon's JSON-P/SnakeYAML conversion and per-application scanning
 * without creating SmallRye's default JSON provider.
 */
@SuppressWarnings("deprecation")
final class SmallRyeOpenApiEngine implements OpenApiEngine {
    @Override
    public OpenAPI load(Config config, ClassLoader classLoader, Supplier<List<IndexView>> indexes, String content) {
        OpenApiConfig openApiConfig = OpenApiConfig.fromConfig(config);
        OpenApiDocument document = OpenApiDocument.newInstance();
        document.config(openApiConfig);
        OpenAPI readerModel = OpenApiProcessor.modelFromReader(openApiConfig, classLoader);
        if (readerModel != null) {
            OpenAPI copy = BaseModel.deepCopy(readerModel, OpenAPI.class);
            normalizeVersion(copy);
            document.modelFromReader(copy);
        }
        if (!content.isBlank()) {
            OpenAPI staticModel = OpenApiCodec.parseDocument(content);
            if (staticModel != null) {
                normalizeVersion(staticModel);
                document.modelFromStaticFile(staticModel);
            }
        }
        if (!openApiConfig.scanDisable()) {
            OpenAPI annotations = OASFactory.createOpenAPI();
            for (IndexView index : indexes.get()) {
                FilteredIndexView filtered = index instanceof FilteredIndexView view
                        ? view : new FilteredIndexView(index, openApiConfig);
                AnnotationScannerContext context = new AnnotationScannerContext(filtered, classLoader,
                        List.of(new JsonpAnnotationScannerExtension()), false, openApiConfig, null, OASFactory.createOpenAPI());
                OpenAPI scanned = new OpenApiAnnotationScanner(context, new AnnotationScannerFactory(classLoader)).scan();
                annotations = MergeUtil.merge(annotations, scanned);
                if (scanned.getOpenapi() != null) {
                    annotations.setOpenapi(scanned.getOpenapi());
                }
            }
            document.modelFromAnnotations(annotations);
        }
        document.filter(OpenApiProcessor.getFilter(openApiConfig, classLoader));
        document.initialize();
        return BaseModel.deepCopy(document.get(), OpenAPI.class);
    }

    @Override
    public IndexView filter(IndexView index, Config config) {
        return new FilteredIndexView(index, OpenApiConfig.fromConfig(config));
    }

    @Override
    public IndexView exclude(IndexView index, Config config, Set<String> classes) {
        return new FilteredIndexView(index, new OpenApiConfigImpl(config) {
            @Override
            public Set<String> scanExcludeClasses() {
                return classes;
            }
        });
    }

    @Override
    public void schemaName(Schema schema, String name) {
        Extensions.setName(schema, name);
    }

    private static void normalizeVersion(OpenAPI model) {
        if (model.getOpenapi() != null && model.getOpenapi().startsWith("3.0.")) {
            model.setOpenapi("3.1.0");
        }
    }

    private final class JsonpAnnotationScannerExtension implements AnnotationScannerExtension {
        @Override
        public Object parseExtension(String key, String value) {
            try {
                return OpenApiCodec.parseAnnotationValue(value);
            } catch (IllegalArgumentException _) {
                System.getLogger(SmallRyeOpenApiEngine.class.getName())
                        .log(System.Logger.Level.ERROR, "OpenAPI extension {0} must contain valid JSON", key);
                return null;
            }
        }

        @Override
        public Object parseValue(String value) {
            return OpenApiCodec.parseAnnotationValue(value);
        }

        @Override
        public Schema parseSchema(String jsonSchema) {
            Schema schema = OpenApiCodec.parseSchema(jsonSchema);
            Object name = schema.get("name");
            if (name instanceof String schemaName) {
                schemaName(schema, schemaName);
                schema.set("name", null);
            }
            return schema;
        }
    }
}
