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
package io.helidon.microprofile.faulttolerance;

import java.util.ServiceLoader;

import io.helidon.common.Api;

import jakarta.annotation.Priority;
import org.glassfish.jersey.ext.cdi1x.internal.spi.InjectionManagerInjectedTarget;
import org.glassfish.jersey.ext.cdi1x.internal.spi.InjectionManagerStore;
import org.glassfish.jersey.ext.cdi1x.internal.spi.InjectionTargetListener;
import org.glassfish.jersey.internal.inject.InjectionManager;

import static jakarta.ws.rs.Priorities.USER;
import static java.util.Comparator.comparingInt;

/**
 * Jersey service provider that makes the captured injection manager available during asynchronous fault-tolerance calls.
 * All other lookups and initialization callbacks are delegated to Jersey's installed store.
 * This class implements Jersey's SPI, including its nullable lookup result.
 */
@Api.Internal
@Priority(1)
public final class JerseyInjectionManagerStore implements InjectionManagerStore, InjectionTargetListener {
    private static final ScopedValue<InjectionManager> CURRENT = ScopedValue.newInstance();

    private final InjectionManagerStore delegate;

    /**
     * Constructor for service loading.
     */
    public JerseyInjectionManagerStore() {
        // HelidonServiceLoader instantiates providers before applying exclusions, which would recurse into this constructor.
        // Use the JDK provider stream to exclude this class first and select by Jersey's @Priority instead of Helidon weights.
        this.delegate = ServiceLoader.load(InjectionManagerStore.class)
            .stream()
            .filter(provider -> provider.type() != this.getClass())
            .min(comparingInt(provider -> priority(provider.type())))
            .orElseThrow(() -> new IllegalStateException("No underlying Jersey injection manager store found"))
            .get();
    }

    static ScopedValue.Carrier with(InjectionManager injectionManager) {
        // A bound null represents a captured absence, distinct from an ordinary lookup with no override.
        return ScopedValue.where(CURRENT, injectionManager);
    }

    @Override
    public void registerInjectionManager(InjectionManager injectionManager) {
        this.delegate.registerInjectionManager(injectionManager);
    }

    @Override
    public InjectionManager getEffectiveInjectionManager() {
        return CURRENT.isBound() ? CURRENT.get() : this.delegate.getEffectiveInjectionManager();
    }

    @Override
    public void notify(InjectionManagerInjectedTarget target) {
        if (this.delegate instanceof InjectionTargetListener listener) {
            listener.notify(target);
        }
    }

    private static int priority(Class<?> type) {
        Priority priority = type.getAnnotation(Priority.class);
        return priority == null ? USER : priority.value();
    }
}
