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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.Main;
import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;
import io.helidon.config.Config;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryException;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;
import io.helidon.spi.HelidonShutdownHandler;

import org.eclipse.microprofile.config.ConfigProvider;

final class MpServiceRegistry {
    private static final System.Logger LOGGER = System.getLogger(MpServiceRegistry.class.getName());
    private static final String GLOBAL_CONTEXT_CLASSIFIER = "helidon-registry-static-context";
    private static final String GLOBAL_REGISTRY_CLASSIFIER = "helidon-registry";
    private static final String REGISTRY_SHUTDOWN_MESSAGE = "Helidon MP service registry is shut down.";
    private static final ReentrantLock LOCK = new ReentrantLock();

    private static RegistryShutdownHandler shutdownHandler;

    private MpServiceRegistry() {
    }

    static boolean start() {
        if (contextualRegistry().isPresent()) {
            return false;
        }

        LOCK.lock();
        try {
            if (shutdownHandler != null) {
                if (!shutdownHandler.shutdown.get()
                        && GlobalServiceRegistry.configured()
                        && GlobalServiceRegistry.registry() == shutdownHandler.registry) {
                    return false;
                }
                throw new ServiceRegistryException("Helidon MP service registry is already started.");
            }
            Config config = config();
            ServiceRegistryManager manager = null;
            try {
                if (GlobalServiceRegistry.configured()) {
                    throw new ServiceRegistryException("Application-global service registry was initialized before Helidon MP "
                                                               + "startup.");
                }
                manager = ServiceRegistryManager.create();
                ServiceRegistry registry = manager.registry();
                configureRegistry(registry, config);
                ServiceRegistry selectedRegistry = GlobalServiceRegistry.registry(() -> registry);
                if (selectedRegistry != registry) {
                    throw new ServiceRegistryException("Application-global service registry was initialized while Helidon MP "
                                                               + "was starting.");
                }

                RegistryShutdownHandler newShutdownHandler = new RegistryShutdownHandler(manager, registry);
                shutdownHandler = newShutdownHandler;
                Main.addShutdownHandler(newShutdownHandler);
                return true;
            } catch (RuntimeException | Error e) {
                shutdownHandler = null;
                if (manager != null) {
                    try {
                        manager.shutdown();
                    } catch (RuntimeException | Error cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                throw e;
            }
        } finally {
            LOCK.unlock();
        }
    }

    static ServiceRegistry registry() {
        Optional<ServiceRegistry> contextualRegistry = contextualRegistry();
        if (contextualRegistry.isPresent()) {
            return contextualRegistry.get();
        }

        LOCK.lock();
        try {
            RegistryShutdownHandler current = shutdownHandler;
            if (current == null || current.shutdown.get()) {
                throw new ServiceRegistryException(REGISTRY_SHUTDOWN_MESSAGE);
            }
            return current.registry;
        } finally {
            LOCK.unlock();
        }
    }

    static void shutdown() {
        LOCK.lock();
        try {
            RegistryShutdownHandler current = shutdownHandler;
            if (current != null) {
                Main.removeShutdownHandler(current);
                current.shutdown();
                shutdownHandler = null;
            }
        } finally {
            LOCK.unlock();
        }
    }

    private static Config config() {
        org.eclipse.microprofile.config.Config config = ConfigProvider.getConfig();
        if (config instanceof Config helidonConfig) {
            return helidonConfig;
        }
        return config.unwrap(Config.class);
    }

    private static void configureRegistry(ServiceRegistry registry, Config config) {
        Context setupContext = Context.create();
        setupContext.register(GLOBAL_CONTEXT_CLASSIFIER, setupContext);
        setupContext.register(GLOBAL_REGISTRY_CLASSIFIER, registry);
        Contexts.runInContext(setupContext, () -> Services.set(Config.class, config));
    }

    private static Optional<ServiceRegistry> contextualRegistry() {
        return Contexts.context()
                .flatMap(context -> context.get(GLOBAL_CONTEXT_CLASSIFIER, Context.class))
                .filter(context -> context != Contexts.globalContext())
                .flatMap(context -> context.get(GLOBAL_REGISTRY_CLASSIFIER, ServiceRegistry.class));
    }

    @Weight(Weighted.DEFAULT_WEIGHT - 10)
    private static final class RegistryShutdownHandler implements HelidonShutdownHandler {
        private final ServiceRegistryManager manager;
        private final ServiceRegistry registry;
        private final AtomicBoolean shutdown = new AtomicBoolean();

        private RegistryShutdownHandler(ServiceRegistryManager manager, ServiceRegistry registry) {
            this.manager = manager;
            this.registry = registry;
        }

        @Override
        public void shutdown() {
            if (shutdown.compareAndSet(false, true)) {
                try {
                    manager.shutdown();
                } catch (Exception e) {
                    LOGGER.log(System.Logger.Level.ERROR, "Failed to shutdown Helidon MP Service Registry", e);
                }
            }
        }

        @Override
        public String toString() {
            return "Helidon MP service registry shutdown handler";
        }
    }
}
