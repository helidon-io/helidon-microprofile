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
package io.helidon.microprofile.metrics;

import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class RegistryFactoryIsolationTest {

    @Test
    void suppliedMeterRegistryDoesNotInitializeGlobalServiceRegistry() {
        assertThat(GlobalServiceRegistry.configured(), is(false));

        ServiceRegistryManager manager = ServiceRegistryManager.create();
        MeterRegistry meterRegistry = null;
        RegistryFactory registryFactory = null;
        try {
            MetricsFactory metricsFactory = manager.registry().get(MetricsFactory.class);
            meterRegistry = metricsFactory.createMeterRegistry(metricsFactory.metricsConfig());
            registryFactory = RegistryFactory.create(meterRegistry);

            assertThat(GlobalServiceRegistry.configured(), is(false));
        } finally {
            if (registryFactory != null) {
                registryFactory.close();
            }
            if (meterRegistry != null) {
                meterRegistry.close();
            }
            GlobalServiceRegistry.registry(manager.registry());
            manager.shutdown();
        }
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }
}
