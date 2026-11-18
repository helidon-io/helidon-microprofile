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

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.IndexView;

/**
 * Helidon's boundary to annotation scanning and model assembly.
 */
interface OpenApiEngine {
    static OpenApiEngine create() {
        return new SmallRyeOpenApiEngine();
    }

    OpenAPI load(Config config, ClassLoader classLoader, Supplier<List<IndexView>> indexes, String content);

    void schemaName(Schema schema, String name);

    IndexView filter(IndexView index, Config config);

    IndexView exclude(IndexView index, Config config, Set<String> classes);
}
