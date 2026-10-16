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

package io.helidon.microprofile.cdi;

import java.util.Map;

import io.helidon.common.Api;
import io.helidon.microprofile.config.core.MpConfig;

import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import static java.util.Objects.requireNonNull;

/**
 * An installer of CDI initializer properties from MicroProfile configuration.
 *
 * @see #addCdiProperties(SeContainerInitializer, Config)
 */
@Api.Stable
public final class HelidonCdiProperties {

    private HelidonCdiProperties() {
        super();
    }

    /**
     * Adds CDI initializer properties from the current MicroProfile configuration.
     *
     * @param <T> the {@link SeContainerInitializer} type
     * @param seContainerInitializer a non-{@code null} {@link SeContainerInitializer}
     * @return the supplied {@link SeContainerInitializer}
     * @throws NullPointerException if {@code seContainerInitializer} is {@code null}
     * @see #addCdiProperties(SeContainerInitializer, Config)
     */
    public static <T extends SeContainerInitializer> T addCdiProperties(T seContainerInitializer) {
        return addCdiProperties(seContainerInitializer, ConfigProvider.getConfig());
    }

    /**
     * Adds configuration entries under {@code cdi.} as initializer properties, removing the {@code cdi.} prefix.
     *
     * <p>Values are supplied as strings. Existing properties with the same names are replaced; other properties are
     * preserved. Subsequent initializer calls may override the added properties.</p>
     *
     * <p>Call this method before {@link SeContainerInitializer#initialize()} and after preparing the desired
     * configuration. For example, {@code cdi.org.jboss.weld.se.archive.isolation} becomes
     * {@code org.jboss.weld.se.archive.isolation}.</p>
     *
     * @param <T> the {@link SeContainerInitializer} type
     * @param seContainerInitializer a non-{@code null} {@link SeContainerInitializer}
     * @param config a non-{@code null} MicroProfile {@link Config}
     * @return the supplied {@link SeContainerInitializer}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static <T extends SeContainerInitializer> T addCdiProperties(T seContainerInitializer, Config config) {
        requireNonNull(seContainerInitializer, "seContainerInitializer");
        requireNonNull(config, "config");
        MpConfig.toHelidonConfig(config)
                .get("cdi")
                .detach()
                .asMap()
                .orElseGet(Map::of)
                .forEach(seContainerInitializer::addProperty);
        return seContainerInitializer;
    }
}
