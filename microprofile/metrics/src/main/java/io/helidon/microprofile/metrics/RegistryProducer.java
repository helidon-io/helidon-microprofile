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

import java.lang.annotation.Annotation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

import static org.eclipse.microprofile.metrics.MetricRegistry.APPLICATION_SCOPE;
import static org.eclipse.microprofile.metrics.MetricRegistry.BASE_SCOPE;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.APPLICATION;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.BASE;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.VENDOR;
import static org.eclipse.microprofile.metrics.MetricRegistry.VENDOR_SCOPE;

/**
 * Producer of each type of registry.
 *
 * We cannot use a lazy value for the registry factory, because the factory can be updated with new metrics settings after
 * the first use (to closeAll the app registry) using runtime (not build-time) config.
 */
@ApplicationScoped
final class RegistryProducer {

    private RegistryProducer() {
    }

    @Produces
    @Default
    @RegistryScope
    private static MetricRegistry getScopedRegistry(RegistryFactory registryFactory, InjectionPoint injectionPoint) {
        if (injectionPoint != null) {
            for (Annotation qualifier : injectionPoint.getQualifiers()) {
                if (qualifier instanceof RegistryScope scope) {
                    return registryFactory.getRegistry(scope.scope());
                }
            }
        }
        return registryFactory.getRegistry(APPLICATION_SCOPE);
    }

    // Remove if MP Metrics ever removes @RegistryType.
    @Produces
    @RegistryType(type = APPLICATION)
    private static MetricRegistry getApplicationRegistry(RegistryFactory registryFactory) {
        return registryFactory.getRegistry(APPLICATION_SCOPE);
    }

    // Remove if MP Metrics ever removes @RegistryType.
    @Produces
    @RegistryType(type = BASE)
    private static MetricRegistry getBaseRegistry(RegistryFactory registryFactory) {
        return registryFactory.getRegistry(BASE_SCOPE);
    }

    // Remove if MP Metrics ever removes @RegistryType.
    @Produces
    @RegistryType(type = VENDOR)
    private static MetricRegistry getVendorRegistry(RegistryFactory registryFactory) {
        return registryFactory.getRegistry(VENDOR_SCOPE);
    }

    @Produces
    private static RegistryFactory getRegistryFactory() {
        return RegistryFactory.getInstance();
    }

}
