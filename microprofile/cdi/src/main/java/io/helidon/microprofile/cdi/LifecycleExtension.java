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

import io.helidon.common.Api;
import io.helidon.common.SerializationConfig;
import io.helidon.common.Version;
import io.helidon.common.features.HelidonFeatures;
import io.helidon.common.features.api.HelidonFlavor;
import io.helidon.logging.common.LogConfig;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.ConfigProvider;

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;

/**
 * Initializes Helidon MP support and delivers legacy Helidon lifecycle events during ordinary CDI initialization.
 * Logging, deserialization filtering, and the MP feature-reporting flavor are configured before service registry acquisition.
 * Features are reported late in {@link Startup}, using the {@code features.print-details} configuration property.
 * The legacy events are fired in order during {@link AfterDeploymentValidation}, at priority
 * {@link jakarta.interceptor.Interceptor.Priority#PLATFORM_BEFORE}, before ordinary-priority observers.
 * Applications that disable extension discovery must explicitly register this extension if they need this setup or
 * features that observe the legacy events. The Helidon testing framework supplies it automatically for its test containers.
 */
@Api.Stable
public final class LifecycleExtension implements Extension {
    /**
     * Creates the compatibility extension for CDI discovery or explicit registration.
     */
    public LifecycleExtension() {
        super();
    }

    private void onDiscovery(@Observes @Priority(PLATFORM_BEFORE - 1) BeforeBeanDiscovery event) {
        // Run before service registry acquisition, which can initialize Helidon services.
        LogConfig.configureRuntime();
        SerializationConfig.configureRuntime();
        HelidonFeatures.flavor(HelidonFlavor.MP);
    }

    private void onValidation(@Observes @Priority(PLATFORM_BEFORE) AfterDeploymentValidation event, BeanManager beanManager) {
        Event<Object> events = beanManager.getEvent();
        events.select(BuildTimeStart.Literal.INSTANCE).fire("STATIC_INSTANCE");
        events.select(BuildTimeEnd.Literal.INSTANCE).fire("STATIC_INSTANCE");
        events.select(RuntimeStart.Literal.INSTANCE).fire(ConfigProvider.getConfig());
    }

    private void onStartup(@Observes @Priority(PLATFORM_AFTER) Startup event) {
        HelidonFeatures.print(HelidonFlavor.MP,
                              Version.VERSION,
                              ConfigProvider.getConfig().getOptionalValue("features.print-details", Boolean.class).orElse(false));
    }
}
