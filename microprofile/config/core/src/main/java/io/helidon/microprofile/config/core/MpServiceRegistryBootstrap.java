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
package io.helidon.microprofile.config.core;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryException;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;

/**
 * Coordinates the temporary service registry used by MP Config with the application registry owned by Helidon MP.
 */
@Api.Internal
public final class MpServiceRegistryBootstrap {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile State state = State.AVAILABLE;
    private static volatile Config registeredConfig;
    private static ServiceRegistryManager bootstrapManager;
    private static ServiceRegistryManager applicationManager;

    private MpServiceRegistryBootstrap() {
    }

    /**
     * Makes the current MP Config available from the application-global service registry.
     *
     * @param config config to register
     */
    public static void configure(Config config) {
        Objects.requireNonNull(config);

        State currentState = state;
        if (config == registeredConfig && (currentState == State.BOOTSTRAP || currentState == State.RUNNING)) {
            return;
        }

        LOCK.lock();
        try {
            if (state == State.STARTING || state == State.STOPPING) {
                return;
            }

            if (!GlobalServiceRegistry.configured()) {
                if (state == State.RUNNING) {
                    return;
                }
                initializeBootstrapRegistry();
            }

            try {
                Services.set(Config.class, config);
                registeredConfig = config;
            } catch (Exception _) {
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Replaces the MP Config bootstrap registry, if present, with a new application registry.
     *
     * @return manager of the application registry
     */
    public static ServiceRegistryManager start() {
        ServiceRegistryManager bootstrapToShutdown;
        LOCK.lock();
        try {
            if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
                throw new ServiceRegistryException("Helidon MP service registry is already started.");
            }

            state = State.STARTING;
            try {
                bootstrapToShutdown = bootstrapManagerForShutdown();
            } catch (RuntimeException | Error e) {
                restoreStartState();
                throw e;
            }
        } finally {
            LOCK.unlock();
        }

        try {
            if (bootstrapToShutdown != null) {
                bootstrapToShutdown.shutdown();
            }
        } catch (RuntimeException | Error e) {
            LOCK.lock();
            try {
                restoreStartState();
            } finally {
                LOCK.unlock();
            }
            throw e;
        }

        LOCK.lock();
        try {
            try {
                if (bootstrapToShutdown != null) {
                    bootstrapManager = null;
                    registeredConfig = null;
                }
                if (GlobalServiceRegistry.configured()) {
                    throw new ServiceRegistryException("Application-global service registry was initialized before Helidon MP "
                                                               + "startup.");
                }
                ServiceRegistryManager manager = ServiceRegistryManager.create();
                ServiceRegistry registry = manager.registry();
                ServiceRegistry selectedRegistry = GlobalServiceRegistry.registry(() -> registry);
                if (selectedRegistry != registry) {
                    manager.shutdown();
                    throw new ServiceRegistryException("Application-global service registry was initialized while Helidon MP "
                                                               + "was starting.");
                }

                applicationManager = manager;
                state = State.RUNNING;
                return manager;
            } catch (RuntimeException | Error e) {
                restoreStartState();
                throw e;
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Shuts down the application registry while preventing MP Config from reinstalling it.
     *
     * @param manager manager returned from {@link #start()}
     */
    public static void shutdown(ServiceRegistryManager manager) {
        LOCK.lock();
        try {
            if (manager != applicationManager || state != State.RUNNING) {
                return;
            }

            state = State.STOPPING;
            registeredConfig = null;
        } finally {
            LOCK.unlock();
        }

        try {
            manager.shutdown();
        } finally {
            LOCK.lock();
            try {
                applicationManager = null;
                state = State.AVAILABLE;
            } finally {
                LOCK.unlock();
            }
        }
    }

    private static void initializeBootstrapRegistry() {
        ServiceRegistryManager manager = bootstrapManager;
        if (manager == null) {
            manager = ServiceRegistryManager.create();
        }
        ServiceRegistry registry = manager.registry();
        ServiceRegistry selectedRegistry = GlobalServiceRegistry.registry(() -> registry);
        if (selectedRegistry == registry) {
            bootstrapManager = manager;
            state = State.BOOTSTRAP;
        } else {
            if (manager == bootstrapManager) {
                bootstrapManager = null;
            }
            manager.shutdown();
            state = State.AVAILABLE;
        }
    }

    private static ServiceRegistryManager bootstrapManagerForShutdown() {
        ServiceRegistryManager manager = bootstrapManager;
        if (manager == null) {
            return null;
        }

        ServiceRegistry registry = manager.registry();
        if (!GlobalServiceRegistry.configured() || GlobalServiceRegistry.registry() != registry) {
            throw new ServiceRegistryException("Application-global service registry does not match the registry initialized "
                                                       + "by Helidon MP Config.");
        }

        return manager;
    }

    private static void restoreStartState() {
        state = bootstrapManager == null ? State.AVAILABLE : State.BOOTSTRAP;
    }

    private enum State {
        AVAILABLE,
        BOOTSTRAP,
        STARTING,
        RUNNING,
        STOPPING
    }
}
