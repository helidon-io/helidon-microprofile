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

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.ConfigProvider;

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;

/**
 * Delivers legacy Helidon lifecycle events during ordinary CDI initialization.
 * The events are fired in order during {@link AfterDeploymentValidation}, at priority
 * {@link jakarta.interceptor.Interceptor.Priority#PLATFORM_BEFORE}, before ordinary-priority observers.
 * Applications that disable extension discovery must explicitly register this extension if they use features
 * that observe these events. The Helidon testing framework supplies it automatically for its test containers.
 */
public final class LifecycleExtension implements Extension {
    /**
     * Creates the compatibility extension for CDI discovery or explicit registration.
     */
    public LifecycleExtension() {
        super();
    }

    private void onValidation(@Observes @Priority(PLATFORM_BEFORE) AfterDeploymentValidation event, BeanManager beanManager) {
        Event<Object> events = beanManager.getEvent();
        events.select(BuildTimeStart.Literal.INSTANCE).fire("STATIC_INSTANCE");
        events.select(BuildTimeEnd.Literal.INSTANCE).fire("STATIC_INSTANCE");
        events.select(RuntimeStart.Literal.INSTANCE).fire(ConfigProvider.getConfig());
    }
}
