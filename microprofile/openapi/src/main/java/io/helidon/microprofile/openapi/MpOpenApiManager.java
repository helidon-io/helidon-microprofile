/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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

import io.helidon.microprofile.server.JaxRsApplication;
import io.helidon.microprofile.server.JaxRsCdiExtension;
import io.helidon.openapi.OpenApiFormat;
import io.helidon.openapi.OpenApiManager;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.jboss.jandex.IndexView;

/**
 * A {@link OpenApiManager} for MicroProfile.
 */
final class MpOpenApiManager implements OpenApiManager<OpenAPI> {

    private static final String CONFIG_EXT_PREFIX = "mp.openapi.extensions.helidon.";

    /**
     * Full config key for the {@code JAXRS_SEMANTICS} option.
     */
    static final String USE_JAXRS_SEMANTICS_KEY = CONFIG_EXT_PREFIX + "use-jaxrs-semantics";

    private final Config config;
    private final MpOpenApiManagerConfig managerConfig;
    private final OpenApiEngine engine = OpenApiEngine.create();

    MpOpenApiManager(Config config) {
        this.config = config;
        this.managerConfig = MpOpenApiManagerConfig.builder()
                .update(builder -> config.getOptionalValue(USE_JAXRS_SEMANTICS_KEY, Boolean.class)
                        .ifPresent(builder::useJaxRsSemantics))
                .build();
    }

    @Override
    public String name() {
        return "manager";
    }

    @Override
    public String type() {
        return "mp";
    }

    @Override
    public OpenAPI load(String content) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader == null) {
            contextClassLoader = MpOpenApiManager.class.getClassLoader();
        }
        return engine.load(config, contextClassLoader, this::buildFilteredIndexViews, content);
    }

    @Override
    public String format(OpenAPI model, OpenApiFormat format) {
        return OpenApiCodec.format(model, format);
    }

    private List<IndexView> buildFilteredIndexViews() {
        BeanManager beanManager = CDI.current().getBeanManager();
        List<JaxRsApplication> jaxRsApps = beanManager.getExtension(JaxRsCdiExtension.class).applicationsToRun();
        Set<Class<?>> annotatedTypes = beanManager.getExtension(OpenApiCdiExtension.class).annotatedTypes();
        return new FilteredIndexViewsBuilder(config,
                                             jaxRsApps,
                                             annotatedTypes,
                                             managerConfig.indexPaths(),
                                             managerConfig.useJaxRsSemantics()).buildViews();
    }
}
