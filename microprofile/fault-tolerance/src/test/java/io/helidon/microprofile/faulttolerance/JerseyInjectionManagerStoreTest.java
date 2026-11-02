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

import java.lang.reflect.Proxy;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.jersey.ext.cdi1x.internal.spi.InjectionManagerInjectedTarget;
import org.glassfish.jersey.ext.cdi1x.internal.spi.InjectionManagerStore;
import org.glassfish.jersey.inject.hk2.Hk2InjectionManagerFactory;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JerseyInjectionManagerStoreTest {
    private InjectionManager original;
    private InjectionManager captured;
    private JerseyInjectionManagerStore store;

    @BeforeEach
    void setUp() {
        original = new Hk2InjectionManagerFactory().create(null);
        captured = new Hk2InjectionManagerFactory().create(null);
        store = (JerseyInjectionManagerStore) ServiceLoader.load(InjectionManagerStore.class).stream()
                .filter(provider -> provider.type() == JerseyInjectionManagerStore.class)
                .findFirst().orElseThrow().get();
        store.registerInjectionManager(original);
    }

    @AfterEach
    void tearDown() {
        original.shutdown();
        captured.shutdown();
    }

    @Test
    void restoresNestedBindingsAfterFailure() {
        assertThat(store.getEffectiveInjectionManager(), sameInstance(original));
        JerseyInjectionManagerStore.with(captured).run(() -> {
            assertThat(store.getEffectiveInjectionManager(), sameInstance(captured));
            assertThrows(IllegalStateException.class, () -> JerseyInjectionManagerStore.with(original).run(() -> {
                assertThat(store.getEffectiveInjectionManager(), sameInstance(original));
                throw new IllegalStateException("nested failure");
            }));
            assertThat(store.getEffectiveInjectionManager(), sameInstance(captured));
        });
        assertThat(store.getEffectiveInjectionManager(), sameInstance(original));
    }

    @Test
    void capturedAbsenceDoesNotFallBackToAnotherManager() {
        JerseyInjectionManagerStore.with(null).run(() -> assertThat(store.getEffectiveInjectionManager(), nullValue()));
        assertThat(store.getEffectiveInjectionManager(), sameInstance(original));
    }

    @Test
    void workerBindingDoesNotLeakAfterError() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThrows(AssertionError.class, () -> JerseyInjectionManagerStore.with(captured).run(() -> {
                assertThat(store.getEffectiveInjectionManager(), sameInstance(captured));
                throw new AssertionError("worker failure");
            }))).get(5, SECONDS);
            assertThat(executor.submit(store::getEffectiveInjectionManager).get(5, SECONDS), sameInstance(original));
        }
    }

    @Test
    void forwardsInjectionTargetNotifications() {
        AtomicReference<InjectionManager> notified = new AtomicReference<>();
        var target = (InjectionManagerInjectedTarget) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {InjectionManagerInjectedTarget.class}, (_, method, arguments) -> {
                    assertThat(method.getName(), is("setInjectionManager"));
                    notified.set((InjectionManager) arguments[0]);
                    return null;
                });
        store.notify(target);
        store.registerInjectionManager(captured);
        assertThat(notified.get(), sameInstance(captured));
    }
}
