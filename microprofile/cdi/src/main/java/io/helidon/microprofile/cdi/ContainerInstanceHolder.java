/*
 * Copyright (c) 2019, 2026 Oracle and/or its affiliates.
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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores the container registered by {@link io.helidon.microprofile.cdi.HelidonContainerInitializer}.
 */
final class ContainerInstanceHolder {
    private static final AtomicReference<HelidonContainer> CONTAINER = new AtomicReference<>();

    private ContainerInstanceHolder() {
        super();
    }

    static void set(HelidonContainer container) {
        CONTAINER.set(container);
    }

    static HelidonContainer get() {
        HelidonContainer container = CONTAINER.get();
        if (container == null) {
            throw new IllegalStateException("No Helidon CDI container is registered; initialize one with SeContainerInitializer");
        }
        return container;
    }

    static void reset() {
        CONTAINER.set(null);
        HelidonCdiProvider.unset();
    }
}
