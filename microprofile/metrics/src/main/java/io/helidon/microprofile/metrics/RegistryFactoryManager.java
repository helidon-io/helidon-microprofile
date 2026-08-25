/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.metrics.api.SystemTagsManager;
import io.helidon.metrics.spi.MeterRegistryLifeCycleListener;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

@Service.Singleton
final class RegistryFactoryManager implements MeterRegistryLifeCycleListener {
    private final ServiceRegistry serviceRegistry;
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final AtomicReference<MeterRegistry> meterRegistry = new AtomicReference<>();

    RegistryFactoryManager(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    void enable() {
        enabled.set(true);
        RegistryFactory.registerOwner(this);
        MeterRegistry currentMeterRegistry = meterRegistry.get();
        if (currentMeterRegistry != null) {
            registryFactory(currentMeterRegistry);
        }
    }

    RegistryFactory registryFactory() {
        return registryFactory(serviceRegistry.get(MeterRegistry.class));
    }

    @Override
    public void onCreate(MeterRegistry meterRegistry, MetricsConfig metricsConfig) {
        if (this.meterRegistry.compareAndSet(null, meterRegistry) && enabled.get()) {
            registryFactory(meterRegistry);
        }
    }

    @Service.PreDestroy
    void preDestroy() {
        RegistryFactory.serviceRegistryShutdown(this);
    }

    private RegistryFactory registryFactory(MeterRegistry meterRegistry) {
        MetricsFactory metricsFactory = serviceRegistry.get(MetricsFactory.class);
        if (meterRegistry.metricsFactory() != metricsFactory) {
            throw new IllegalStateException(RegistryFactory.class.getName()
                                                    + " cannot be initialized for an unrelated meter registry");
        }
        return RegistryFactory.activate(meterRegistry,
                                        metricsFactory,
                                        serviceRegistry.get(SystemTagsManager.class),
                                        this);
    }

}
