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
 * An {@link Extension} that ensures {@linkplain LogConfig#configureRuntime() logging is configured}, {@linkplain
 * SerializationConfig#configureRuntime() serialization is configured}, and Helidon features are reported. It also
 * ensures certain "lifecycle events" fired by previous versions of Helidon MicroProfile continue to be fired for
 * backwards compatibility (though they are deprecated; prefer standard CDI lifecycle events instead for new development).
 *
 * <p>Applications and tests that {@linkplain jakarta.enterprise.inject.se.SeContainerInitializer#disableDiscovery()
 * disable extension discovery} must explicitly {@linkplain
 * jakarta.enterprise.inject.se.SeContainerInitializer#addExtensions(Extension...) register this extension} if they need
 * this setup or features that observe these events. The Helidon testing framework supplies it automatically for its
 * test containers.</p>
 *
 * @see LogConfig#configureRuntime()
 * @see SerializationConfig#configureRuntime()
 */
@Api.Stable
public final class CdiExtension implements Extension {
    /**
     * Creates a new {@link CdiExtension}.
     */
    public CdiExtension() {
    }

    private void onDiscovery(@Observes @Priority(PLATFORM_BEFORE - 1) BeforeBeanDiscovery event) {
        // The priority is so this runs before service registry acquisition (see ServiceRegistryExtension), which can
        // initialize Helidon services that might need logging and serialization set up properly.
        LogConfig.configureRuntime();
        SerializationConfig.configureRuntime();
        HelidonFeatures.flavor(HelidonFlavor.MP);
    }

    @SuppressWarnings("deprecation")
    private void fireBuildTimeStartAndEnd(@Observes @Priority(PLATFORM_BEFORE) AfterDeploymentValidation event,
                                          BeanManager beanManager) {
        Event<Object> events = beanManager.getEvent();
        // ("STATIC_INSTANCE" is the event fired by prior versions of Helidon MicroProfile and is used here for
        // backwards compatibility.)
        events.select(BuildTimeStart.Literal.INSTANCE).fire("STATIC_INSTANCE");
        events.select(BuildTimeEnd.Literal.INSTANCE).fire("STATIC_INSTANCE");
    }

    @SuppressWarnings("deprecation")
    private void fireRuntimeStart(@Observes @Priority(PLATFORM_BEFORE + 2) AfterDeploymentValidation event,
                                  BeanManager beanManager) {
        // Note that io.helidon.microprofile.server.ServerCdiExtension has an AfterDeploymentValidation observer that
        // runs "in between" the #fireBuildTimeStartAndEnd(AfterDeploymentValidation, BeanManager) observer above and
        // this observer. This preserves an ordering that was present in prior versions of Helidon MicroProfile and that
        // end users might be relying on.
        beanManager.getEvent().select(RuntimeStart.Literal.INSTANCE).fire(ConfigProvider.getConfig());
    }

    private void onStartup(@Observes @Priority(PLATFORM_AFTER) Startup event) {
        HelidonFeatures.print(HelidonFlavor.MP,
                              Version.VERSION,
                              ConfigProvider.getConfig().getOptionalValue("features.print-details", Boolean.class).orElse(false));
    }
}
