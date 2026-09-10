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

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.microprofile.server.JaxRsCdiExtension;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.service.registry.DependencyContext;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.InterceptionMetadata;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceDescriptor;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryFactoryLifecycleTest {

    @Test
    void rejectsCachedFactoryAfterGlobalRegistryIsUnsetDuringShutdown() {
        ServiceRegistryManager manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                .addServiceDescriptor(new ShutdownProbeDescriptor())
                .build());
        try {
            GlobalServiceRegistry.registry(manager.registry());
            RegistryFactory factory = manager.registry().get(RegistryFactoryManager.class).registryFactory();
            assertThat(RegistryFactory.getInstance(), sameInstance(factory));
            ShutdownProbe probe = manager.registry().get(ShutdownProbe.class);

            manager.shutdown();

            assertThat("The probe ran before MP registry destruction", probe.called, is(true));
            assertThat("Global registry was already unset during the callback", probe.globalConfigured, is(false));
            assertThat("Cached factory access during shutdown must fail", probe.failure, instanceOf(IllegalStateException.class));
            assertThat(probe.failure.getMessage(), containsString("only while a service registry is configured"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void staticAccessAfterShutdownDoesNotRestoreGlobalServiceRegistry() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Config originalConfig = resolver.getConfig(classLoader);
        Config config = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of("server.port", "0",
                                                           "mp.initializer.allow", "true")))
                .build();
        resolver.registerConfig(config, classLoader);

        try {
            IllegalStateException beforeStartup = assertThrows(IllegalStateException.class, RegistryFactory::getInstance);
            assertThat(beforeStartup.getMessage(), containsString("only while a service registry is configured"));
            assertThat("pre-start access must not initialize the global service registry",
                       GlobalServiceRegistry.configured(),
                       is(false));

            try (SeContainer _ = startContainer()) {
                assertThat(GlobalServiceRegistry.configured(), is(true));
                assertThat(RegistryFactory.getInstance(), notNullValue());
            }

            assertThat(GlobalServiceRegistry.configured(), is(false));
            IllegalStateException e = assertThrows(IllegalStateException.class, RegistryFactory::getInstance);
            assertThat(e.getMessage(), containsString("only while a service registry is configured"));
            assertThat("static access must not restore the global service registry",
                       GlobalServiceRegistry.configured(),
                       is(false));

            ServiceRegistryManager unrelatedManager = ServiceRegistryManager.create();
            ServiceRegistryManager currentManager = ServiceRegistryManager.create();
            try {
                GlobalServiceRegistry.registry(currentManager.registry());
                RegistryFactoryManager currentRegistryFactoryManager =
                        currentManager.registry().get(RegistryFactoryManager.class);
                RegistryFactory initialRegistryFactory = currentRegistryFactoryManager.registryFactory();
                assertThat(RegistryFactory.getInstance(), sameInstance(initialRegistryFactory));

                RegistryFactory.closeAll();
                RegistryFactory reactivatedRegistryFactory = RegistryFactory.getInstance();
                assertThat("the current registry owner can reactivate the registry factory",
                           reactivatedRegistryFactory,
                           notNullValue());

                unrelatedManager.registry().get(RegistryFactoryManager.class);
                unrelatedManager.shutdown();
                assertThat("shutting down an unrelated registry must preserve the current registry factory",
                           RegistryFactory.getInstance(),
                           sameInstance(reactivatedRegistryFactory));

                currentManager.shutdown();
                assertThrows(IllegalStateException.class, RegistryFactory::getInstance);
                assertThat("registry shutdown must not restore the global service registry",
                           GlobalServiceRegistry.configured(),
                           is(false));
            } finally {
                RegistryFactory.closeAll();
                currentManager.shutdown();
                unrelatedManager.shutdown();
            }

            try (SeContainer _ = startContainer()) {
                assertThat("a later MP startup should reactivate the registry factory",
                           RegistryFactory.getInstance(),
                           notNullValue());
            }
            assertThat(GlobalServiceRegistry.configured(), is(false));
        } finally {
            resolver.registerConfig(originalConfig, classLoader);
        }
    }

    private static SeContainer startContainer() {
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(MetricsCdiExtension.class, ServerCdiExtension.class, JaxRsCdiExtension.class)
                .initialize();
    }

    private static class ShutdownProbe {
        private boolean called;
        private boolean globalConfigured;
        private RuntimeException failure;
    }

    private static class ShutdownProbeDescriptor implements ServiceDescriptor<ShutdownProbe> {
        @Override
        public TypeName serviceType() {
            return TypeName.create(ShutdownProbe.class);
        }

        @Override
        public TypeName descriptorType() {
            return TypeName.create(ShutdownProbeDescriptor.class);
        }

        @Override
        public Set<ResolvedType> contracts() {
            return Set.of(ResolvedType.create(ShutdownProbe.class));
        }

        @Override
        public Optional<Double> runLevel() {
            return Optional.of(Service.RunLevel.NORMAL + 100);
        }

        @Override
        public Object instantiate(DependencyContext ctx, InterceptionMetadata interceptionMetadata) {
            return new ShutdownProbe();
        }

        @Override
        public void preDestroy(ShutdownProbe probe) {
            probe.called = true;
            probe.globalConfigured = GlobalServiceRegistry.configured();
            try {
                RegistryFactory.getInstance();
            } catch (RuntimeException e) {
                probe.failure = e;
            }
        }
    }
}
