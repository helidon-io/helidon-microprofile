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

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.helidon.common.Api;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import static java.util.Objects.requireNonNull;

/**
 * Initializes {@link SeContainer}s with cleanup of participating resources when initialization fails.
 *
 * @see #initialize(SeContainerInitializer)
 */
@Api.Internal
public final class SeContainerInitializers {
    private static final ScopedValue<Deque<Runnable>> CLEANUP_ACTIONS = ScopedValue.newInstance();

    private SeContainerInitializers() {
        super();
    }

    /**
     * {@linkplain SeContainerInitializer#initialize() Initializes} a {@linkplain SeContainer container}, running
     * {@linkplain #onInitializationFailure(Runnable) previously registered cleanup actions} if an error occurs.
     *
     * <p>If no error occurs, any {@linkplain #onInitializationFailure(Runnable) registered cleanup actions} are simply
     * discarded without being {@linkplain Runnable#run() run}.</p>
     *
     * <p>Cleanup actions run in reverse registration order.</p>
     *
     * @param initializer a non-{@code null} {@link SeContainerInitializer}
     * @return a non-{@code null} {@link SeContainer}
     * @throws NullPointerException if {@code initializer} is {@code null}
     * @throws RuntimeException if an error occurs during initialization; cleanup actions will have been run
     * @see #onInitializationFailure(Runnable)
     * @see SeContainerInitializer#initialize()
     */
    public static SeContainer initialize(SeContainerInitializer initializer) {
        requireNonNull(initializer, "initializer");
        Deque<Runnable> cleanupActions = new ConcurrentLinkedDeque<>();
        try {
            // Bind only for this invocation; nested initialization gets its own actions and restores this binding.
            return ScopedValue.where(CLEANUP_ACTIONS, cleanupActions).call(initializer::initialize);
        } catch (RuntimeException | Error failure) {
            for (Runnable cleanupAction : cleanupActions.reversed()) {
                try {
                    cleanupAction.run();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            throw failure;
        } finally {
            cleanupActions.clear();
        }
    }

    /**
     * Registers a <dfn>cleanup action</dfn> to be {@linkplain Runnable#run() run} in case an invocation of the {@link
     * #initialize(SeContainerInitializer)} method fails.
     *
     * <p>This method is designed to be invoked by, e.g., portable extensions as they run, so that if a failure occurs
     * the extension's resources are properly cleaned up. CDI does not provide a native facility for such cleanups
     * (destruction and shutdown events are not broadcast when portable extensions fail).</p>
     *
     * <p>The action must release only resources owned by its caller and must tolerate resources already being released,
     * i.e. it must be idempotent.</p>
     *
     * <p>If no {@link #initialize(SeContainerInitializer)} invocation is active in the current scope, the action is
     * discarded without being run.</p>
     *
     * @param cleanupAction a non-{@code null} {@link Runnable}
     * @throws NullPointerException if {@code cleanupAction} is {@code null}
     * @see #initialize(SeContainerInitializer)
     */
    public static void onInitializationFailure(Runnable cleanupAction) {
        requireNonNull(cleanupAction, "cleanupAction");
        if (CLEANUP_ACTIONS.isBound()) {
            CLEANUP_ACTIONS.get().addLast(cleanupAction);
        }
    }
}
