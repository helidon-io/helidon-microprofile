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

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExplicitCdiProviderTest {
    @Test
    void testBootstrapPreservesApplicationProvider() {
        HelidonCdiProvider delegate = new HelidonCdiProvider();
        AtomicBoolean providerCalled = new AtomicBoolean();
        CDI.setCDIProvider(() -> {
            providerCalled.set(true);
            return delegate.getCDI();
        });
        for (int i = 0; i < 2; i++) {
            try (SeContainer container = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addBeanClasses(TestBean2.class)
                    .initialize()) {
                providerCalled.set(false);
                assertThat(CDI.current(), sameInstance(container));
                assertThat(providerCalled.get(), is(true));
                assertThat(CDI.current().select(TestBean2.class).get().message(), is("Hello"));
            }
            providerCalled.set(false);
            assertThrows(IllegalStateException.class, CDI::current);
            assertThat(providerCalled.get(), is(true));
        }
    }
}
