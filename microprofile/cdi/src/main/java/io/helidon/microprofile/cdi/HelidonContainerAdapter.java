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

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;

import static io.helidon.common.context.Contexts.runInContext;
import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;

@Deprecated(since = "28.0.0")
final class HelidonContainerAdapter implements HelidonContainer {
    // Serialize the current-container check and initialization across adapters obtained before CDI starts.
    private static final ReentrantLock LIFECYCLE_LOCK = new ReentrantLock();

    private static boolean initializing;

    private final Context context;

    private Optional<SeContainer> container;

    private HelidonContainerAdapter() {
        super();
        this.context = Contexts.context().orElseGet(Contexts::globalContext);
        this.container = currentContainer();
    }

    static HelidonContainer create() {
        return new HelidonContainerAdapter();
    }

    @Override
    public SeContainer start() {
        LIFECYCLE_LOCK.lock();
        try {
            if (this.container.isEmpty()) {
                SeContainer selected = runInContext(this.context,
                                                    () -> currentContainer().orElseGet(HelidonContainerAdapter::initialize));
                this.container = Optional.of(selected);
            }
            SeContainer container = this.container.orElseThrow();
            if (!container.isRunning()) {
                throw new IllegalStateException("The CDI container associated with this adapter has been closed");
            }
            return container;
        } finally {
            LIFECYCLE_LOCK.unlock();
        }
    }

    @Override
    public Context context() {
        return this.context;
    }

    @Override
    public void shutdown() {
        LIFECYCLE_LOCK.lock();
        try {
            this.container.ifPresent(container -> runInContext(this.context, container::close));
        } finally {
            LIFECYCLE_LOCK.unlock();
        }
    }

    private static Optional<SeContainer> currentContainer() {
        CDI<Object> current;
        try {
            current = CDI.current();
        } catch (IllegalStateException _) {
            return Optional.empty();
        }
        if (current instanceof SeContainer container) {
            return Optional.of(container);
        }
        throw new IllegalStateException("The current CDI instance is not an SeContainer");
    }

    private static SeContainer initialize() {
        // A startup callback must not recursively initialize another container through a new adapter.
        if (initializing) {
            throw new IllegalStateException("CDI initialization is already in progress through a Helidon container adapter");
        }
        initializing = true;
        try {
            return addCdiProperties(addHelidonBeanDefiningAnnotations(SeContainerInitializer.newInstance())).initialize();
        } finally {
            initializing = false;
        }
    }
}
