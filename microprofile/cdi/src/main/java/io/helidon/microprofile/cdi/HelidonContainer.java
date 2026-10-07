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

import io.helidon.common.context.Context;

import jakarta.enterprise.inject.se.SeContainer;

/**
 * Helidon CDI container with separate initialization and startup operations.
 *
 * @see io.helidon.microprofile.cdi.Main
 */
public interface HelidonContainer {
    /**
     * Returns the registered container without creating or initializing one.
     *
     * @return the registered container
     * @throws IllegalStateException if no container is registered
     * @see jakarta.enterprise.inject.se.SeContainerInitializer
     */
    static HelidonContainer instance() {
        return ContainerInstanceHolder.get();
    }

    /**
     * Start the container. This will finish the lifecycle of CDI implementation.
     * If already started, this method is a noop.
     *
     * @return the started container
     * @throws IllegalStateException if this container has not been initialized or has been closed
     */
    SeContainer start();

    /**
     * The root context of MP.
     *
     * @return context
     */
    Context context();

    /**
     * Shutdown the container (and CDI).
     *
     * @throws IllegalStateException if this container has already been closed
     */
    void shutdown();
}
