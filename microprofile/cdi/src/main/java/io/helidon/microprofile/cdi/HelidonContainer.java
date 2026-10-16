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

import io.helidon.common.Api;
import io.helidon.common.context.Context;

import jakarta.enterprise.inject.se.SeContainer;

/**
 * Helidon CDI container with separate initialization and startup operations.
 *
 * @see io.helidon.microprofile.cdi.Main
 * @deprecated Retain the {@link jakarta.enterprise.inject.se.SeContainer} returned by initialization and close it directly.
 */
@Api.Stable
@Deprecated(since = "28.0.0")
public interface HelidonContainer {
    /**
     * Returns a non-{@code null} {@link HelidonContainer}.
     *
     * <p>This method is deprecated. Existing and potential callers should use standard CDI SE features instead.</p>
     *
     * <p>This method returns a {@link HelidonContainer} that is implemented in terms of the {@link
     * jakarta.enterprise.inject.spi.CDI#current()} method. If no current CDI container is available, the returned
     * {@link HelidonContainer} {@linkplain jakarta.enterprise.inject.se.SeContainerInitializer#initialize() creates one}
     * when {@link #start()} is called.</p>
     *
     * <p>Merely calling this method does <em>not</em> initialize CDI.</p>
     *
     * <p>The {@link HelidonContainer} that is returned uses the {@linkplain
     * io.helidon.common.context.Contexts#context() current Helidon context}, or the {@linkplain
     * io.helidon.common.context.Contexts#globalContext() global context} if there is no current context, and returns it
     * from its {@link #context()} method.</p>
     *
     * @return a non-{@code null} {@link HelidonContainer}
     * @throws IllegalStateException if the current CDI instance is not an {@linkplain SeContainer SeContainer}
     * @deprecated Use facilities like {@link jakarta.enterprise.inject.se.SeContainerInitializer#initialize()} instead.
     * @see jakarta.enterprise.inject.se.SeContainerInitializer#initialize()
     */
    @Deprecated(since = "28.0.0")
    static HelidonContainer instance() {
        return HelidonContainerAdapter.create();
    }

    /**
     * Initializes and starts an {@link SeContainer}, or returns an existing {@link SeContainer}.
     *
     * <p>Before initializing a new container, this {@link HelidonContainer} checks again for a current CDI SE
     * container. Initialization runs in the current Helidon context, as described above, with Helidon's additional
     * bean-defining annotations and CDI properties. Once associated with an {@link SeContainer}, this {@link
     * HelidonContainer} remains notionally bound to it, including after it is closed.</p>
     *
     * @return a non-{@code null} {@link SeContainer}
     * @throws IllegalStateException if the associated container has been closed, the current CDI instance is not an
     *                               {@link SeContainer SeContainer}, or initialization is called recursively
     * @deprecated Use facilities like {@link jakarta.enterprise.inject.se.SeContainerInitializer#initialize()} instead.
     */
    @Deprecated(since = "28.0.0")
    SeContainer start();

    /**
     * Returns the Helidon {@link Context} captured when this {@link HelidonContainer} was {@linkplain #instance()
     * acquired}, falling back to the {@linkplain io.helidon.common.context.Contexts#globalContext() global context} if
     * necessary.
     *
     * <p>Note that the returned {@link Context} is not necessarily the one in which an already-running CDI {@link
     * SeContainer} was initialized.
     *
     * @return a non-{@code null} {@link Context}
     * @deprecated Use facilities like {@link jakarta.enterprise.inject.se.SeContainerInitializer#initialize()} instead.
     */
    @Deprecated(since = "28.0.0")
    Context context();

    /**
     * Shut down the associated {@link SeContainer}.
     *
     * <p>If this {@link HelidonContainer} has not yet {@linkplain #start() been started}, this method does nothing.</p>
     *
     * @throws IllegalStateException if this container has already been closed
     * @deprecated Use facilities like {@link jakarta.enterprise.inject.se.SeContainerInitializer#initialize()} instead.
     */
    @Deprecated(since = "28.0.0")
    void shutdown();
}
