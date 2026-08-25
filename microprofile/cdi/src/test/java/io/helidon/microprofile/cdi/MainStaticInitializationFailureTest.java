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

import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryException;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainStaticInitializationFailureTest {

    @Test
    void reportsStickyStartupFailureWithoutPoisoningMainClass() {
        RegistryLifecycleService.PRE_DESTROY.set(0);
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        ServiceRegistry registry = manager.registry();
        RegistryLifecycleService service = registry.get(RegistryLifecycleService.class);
        GlobalServiceRegistry.registry(registry);
        boolean managerShutdown = false;

        try {
            ServiceRegistryException firstFailure =
                    assertThrows(ServiceRegistryException.class, () -> Main.main(new String[0]));
            assertThat(firstFailure.getMessage(), containsString("initialized before Helidon MP"));
            assertThat(GlobalServiceRegistry.registry(), sameInstance(registry));
            assertThat(registry.get(RegistryLifecycleService.class), sameInstance(service));
            assertThat("pre-existing registry remains open", RegistryLifecycleService.PRE_DESTROY.get(), is(0));

            manager.shutdown();
            managerShutdown = true;
            assertThat(GlobalServiceRegistry.configured(), is(false));
            assertThat("pre-existing registry is closed by its owner", RegistryLifecycleService.PRE_DESTROY.get(), is(1));

            ServiceRegistryException secondFailure =
                    assertThrows(ServiceRegistryException.class, () -> Main.main(new String[0]));
            assertThat(secondFailure, sameInstance(firstFailure));
        } finally {
            Main.shutdown();
            if (!managerShutdown) {
                manager.shutdown();
            }
        }
    }

    @Service.Singleton
    static class RegistryLifecycleService {
        private static final AtomicInteger PRE_DESTROY = new AtomicInteger();

        @Service.PreDestroy
        void preDestroy() {
            PRE_DESTROY.incrementAndGet();
        }
    }
}
