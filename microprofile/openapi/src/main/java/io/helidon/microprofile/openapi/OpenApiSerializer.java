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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;

import io.helidon.openapi.OpenApiFormat;

import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Compatibility entry point for the legacy type-description-based serialization API.
 */
final class OpenApiSerializer {
    private OpenApiSerializer() {
    }

    static void serialize(Map<Class<?>, ExpandedTypeDescription> types, OpenAPI model, OpenApiFormat format, Writer writer) {
        try {
            writer.write(OpenApiCodec.format(model, format));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write OpenAPI document", e);
        }
    }
}
