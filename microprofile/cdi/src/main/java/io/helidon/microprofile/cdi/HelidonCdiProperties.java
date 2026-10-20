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
import static org.jboss.weld.environment.deployment.discovery.jandex.Jandex.DISABLE_JANDEX_DISCOVERY_STRATEGY;
import static org.jboss.weld.environment.se.Weld.ALLOW_OPTIMIZED_CLEANUP;
import static org.jboss.weld.environment.se.Weld.ARCHIVE_ISOLATION_SYSTEM_PROPERTY;
import static org.jboss.weld.environment.se.Weld.EMPTY_BEANS_XML_DISCOVERY_MODE_ALL;
import static org.jboss.weld.environment.se.Weld.JAVAX_ENTERPRISE_INJECT_SCAN_IMPLICIT;
import static org.jboss.weld.environment.se.Weld.SCAN_CLASSPATH_ENTRIES_SYSTEM_PROPERTY;
import static org.jboss.weld.environment.se.Weld.SHUTDOWN_HOOK_SYSTEM_PROPERTY;

/**
 * An installer of CDI initializer properties from MicroProfile configuration.
 *
 * @see #addCdiProperties(SeContainerInitializer, Config)
 */
@Api.Stable
public final class HelidonCdiProperties {

    private HelidonCdiProperties() {
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
     * <p>Values are supplied as strings, except for the following Weld SE options, whose values are converted using
     * {@link Boolean#valueOf(String)}:</p>
     * <ul>
     * <li>{@value org.jboss.weld.environment.se.Weld#ALLOW_OPTIMIZED_CLEANUP}</li>
     * <li>{@value org.jboss.weld.environment.se.Weld#ARCHIVE_ISOLATION_SYSTEM_PROPERTY}</li>
     * <li>{@value org.jboss.weld.environment.se.Weld#EMPTY_BEANS_XML_DISCOVERY_MODE_ALL}</li>
     * <li>{@value org.jboss.weld.environment.se.Weld#JAVAX_ENTERPRISE_INJECT_SCAN_IMPLICIT}</li>
     * <li>{@value org.jboss.weld.environment.se.Weld#SCAN_CLASSPATH_ENTRIES_SYSTEM_PROPERTY}</li>
     * <li>{@value org.jboss.weld.environment.se.Weld#SHUTDOWN_HOOK_SYSTEM_PROPERTY}</li>
     * <li>{@value org.jboss.weld.environment.deployment.discovery.jandex.Jandex#DISABLE_JANDEX_DISCOVERY_STRATEGY}</li>
     * </ul>
     *
     * <p>Existing properties with the same names are replaced; other properties are preserved. Subsequent initializer
     * calls may override the added properties.</p>
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
                .forEach((name, value) -> seContainerInitializer.addProperty(name, propertyValue(name, value)));
        return seContainerInitializer;
    }

    private static Object propertyValue(String name, String value) {
        // Weld SE checks these initializer options against Boolean.TRUE; its core configuration parser accepts strings.
        return switch (name) {
        case ALLOW_OPTIMIZED_CLEANUP,
             ARCHIVE_ISOLATION_SYSTEM_PROPERTY,
             EMPTY_BEANS_XML_DISCOVERY_MODE_ALL,
             JAVAX_ENTERPRISE_INJECT_SCAN_IMPLICIT,
             SCAN_CLASSPATH_ENTRIES_SYSTEM_PROPERTY,
             SHUTDOWN_HOOK_SYSTEM_PROPERTY,
             DISABLE_JANDEX_DISCOVERY_STRATEGY -> Boolean.valueOf(value);
        default -> value;
        };
    }
}
