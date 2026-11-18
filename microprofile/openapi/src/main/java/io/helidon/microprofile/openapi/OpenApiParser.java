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

import java.io.Reader;
import java.util.Map;

import org.eclipse.microprofile.openapi.models.Constructible;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Compatibility entry points for parsing with caller-selected model implementations.
 * The normal processing path uses {@link OpenApiCodec} directly.
 */
final class OpenApiParser {
    private OpenApiParser() {
    }

    static <T> T parse(Map<Class<?>, ExpandedTypeDescription> types, Class<T> type, Reader reader) {
        return OpenApiModelMapper.read(type, OpenApiCodec.readTree(reader), modelType -> {
            var standard = OpenApiModelRegistry.create(modelType);
            ExpandedTypeDescription description = types.get(modelType);
            if (description == null || description.impl().equals(standard.getClass())) {
                return standard;
            }
            try {
                return description.impl().asSubclass(Constructible.class)
                        .getConstructor().newInstance();
            } catch (ReflectiveOperationException _) {
                throw new IllegalStateException("OpenAPI model implementation must expose a public no-argument constructor");
            }
        });
    }

    static <T> T parse(Map<Class<?>, ExpandedTypeDescription> types, Class<T> type, Reader reader, Representer representer) {
        return parse(types, type, reader);
    }
}
