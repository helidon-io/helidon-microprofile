/*
 * Copyright (c) 2018, 2026 Oracle and/or its affiliates.
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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.metrics.api.SystemTagsManager;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.Services;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Timer;

/**
 * MicroProfile Metrics registry factory backed by a Helidon {@link io.helidon.metrics.api.MeterRegistry}.
 * <p>
 * A managed instance is created and recorded for the meter registry owned by the currently configured service registry.
 * Otherwise, static access throws an {@link java.lang.IllegalStateException}.
 * <p>
 * The {@link #create(io.helidon.metrics.api.MeterRegistry)} method creates an isolated instance for the supplied meter
 * registry without recording it internally.
 */
public class RegistryFactory {

    static final Collection<Class<? extends Metric>> METRIC_TYPES = Set.of(Counter.class,
                                                                           Gauge.class,
                                                                           Histogram.class,
                                                                           Timer.class);
    private static final Lock LIFECYCLE_ACCESS = new ReentrantLock();
    private static final AtomicReference<RegistryFactory> REGISTRY_FACTORY = new AtomicReference<>();
    private static final AtomicReference<RegistryFactoryManager> REGISTRY_FACTORY_OWNER = new AtomicReference<>();
    private final MeterRegistry meterRegistry;
    private final MetricsFactory metricsFactory;
    private final SystemTagsManager systemTagsManager;
    private final RegistryFactoryManager owner;
    private final Map<String, Registry> registries = new HashMap<>();
    private final Lock metricsSettingsAccess = new ReentrantLock(true);

    private RegistryFactory(MeterRegistry meterRegistry,
                            MetricsFactory metricsFactory,
                            SystemTagsManager systemTagsManager,
                            RegistryFactoryManager owner) {
        this.meterRegistry = meterRegistry;
        this.metricsFactory = metricsFactory;
        this.systemTagsManager = systemTagsManager;
        this.owner = owner;
        meterRegistry
                .onMeterAdded(this::registerMetricForExistingMeter)
                .onMeterRemoved(this::removeMetricForMeter);
    }

    /**
     * Get a singleton instance of the registry factory.
     *
     * @return registry factory singleton
     * @throws IllegalStateException if no service registry is configured
     */
    public static RegistryFactory getInstance() {
        RegistryFactory result = REGISTRY_FACTORY.get();
        if (result != null) {
            return result;
        }
        if (!activationAllowed()) {
            throw new IllegalStateException(RegistryFactory.class.getName()
                                                    + " is available only while a service registry is configured");
        }
        RegistryFactoryManager owner = REGISTRY_FACTORY_OWNER.get();
        if (owner == null) {
            owner = Services.get(RegistryFactoryManager.class);
        }
        return owner.registryFactory();
    }

    static RegistryFactory create(MeterRegistry meterRegistry) {
        MetricsFactory metricsFactory = meterRegistry.metricsFactory();
        SystemTagsManager systemTagsManager = SystemTagsManager.create(metricsFactory.metricsConfig(), metricsFactory);
        return new RegistryFactory(meterRegistry, metricsFactory, systemTagsManager, null);
    }

    static RegistryFactory activate(MeterRegistry meterRegistry,
                                    MetricsFactory metricsFactory,
                                    SystemTagsManager systemTagsManager,
                                    RegistryFactoryManager owner) {
        LIFECYCLE_ACCESS.lock();
        try {
            if (!activationAllowed()) {
                throw new IllegalStateException(RegistryFactory.class.getName()
                                                        + " cannot be initialized without a configured service registry");
            }
            RegistryFactory result = REGISTRY_FACTORY.get();
            if (result != null && result.meterRegistry == meterRegistry && result.owner == owner) {
                return result;
            }
            result = new RegistryFactory(meterRegistry, metricsFactory, systemTagsManager, owner);
            REGISTRY_FACTORY_OWNER.set(owner);
            REGISTRY_FACTORY.set(result);
            return result;
        } finally {
            LIFECYCLE_ACCESS.unlock();
        }
    }

    /**
     * Intended for use by test initializers to do a brute force clearout of each registry and
     * the factory's collection of registries.
     */
    static void closeAll() {
        LIFECYCLE_ACCESS.lock();
        try {
            RegistryFactory rf = REGISTRY_FACTORY.getAndSet(null);
            if (rf != null) {
                rf.close();
            }
        } finally {
            LIFECYCLE_ACCESS.unlock();
        }
    }

    static void registerOwner(RegistryFactoryManager owner) {
        REGISTRY_FACTORY_OWNER.set(owner);
    }

    static void serviceRegistryShutdown(RegistryFactoryManager owner) {
        RegistryFactory current = REGISTRY_FACTORY.get();
        if (current != null && current.owner == owner) {
            REGISTRY_FACTORY.compareAndSet(current, null);
        }
        REGISTRY_FACTORY_OWNER.compareAndSet(owner, null);
    }

    /**
     * Get a registry based on its scope.
     *
     * @param scope scope of registry
     * @return Registry for the scope requested
     */
    public MetricRegistry getRegistry(String scope) {
        return registry(scope);
    }

    /**
     * Report the scopes of all existing registries.
     *
     * @return set of scope names
     */
    public Set<String> scopes() {
        return Collections.unmodifiableSet(registries.keySet());
    }

    Registry registry(String scope) {
        return accessMetricsSettings(() -> registries.computeIfAbsent(scope, s ->
                Registry.create(s, meterRegistry, metricsFactory, systemTagsManager)));
    }

    void start() {
        PeriodicExecutor.start();
    }

    void close() {
        /*
            Primarily for successive tests (e.g., in the TCK) which might share the same VM, delete each metric individually
            (which will trickle down into the delegate meter registry) and also closeAll out the collection of registries.
         */
        List.copyOf(registries.values()).forEach(Registry::clear);
        registries.clear();
        PeriodicExecutor.stop();
    }

    private static boolean activationAllowed() {
        return GlobalServiceRegistry.configured();
    }

    private <T> T accessMetricsSettings(Callable<T> callable) {
        metricsSettingsAccess.lock();
        try {
            return callable.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            metricsSettingsAccess.unlock();
        }
    }

    private void registerMetricForExistingMeter(Meter delegate) {
        registry(MpScope.scope(delegate)).onMeterAdded(delegate);
    }

    private void removeMetricForMeter(Meter meter) {
        registry(MpScope.scope(meter)).onMeterRemoved(meter);
    }

}
