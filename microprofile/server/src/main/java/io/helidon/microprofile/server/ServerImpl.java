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

package io.helidon.microprofile.server;

import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;
import static io.helidon.microprofile.cdi.SeContainerInitializers.initialize;
import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;

/**
 * Server to handle lifecycle of MicroProfile implementation.
 */
public class ServerImpl implements Server {
    private static final System.Logger STARTUP_LOGGER = System.getLogger("io.helidon.microprofile.startup.server");

    private final Lock containerAccess;
    private final SeContainerInitializer containerInitializer;
    private final Config config;
    private final ClassLoader classLoader;
    private final String host;

    private SeContainer container;
    private volatile int port;

    ServerImpl(Builder builder) {
        this.containerAccess = new ReentrantLock();
        this.config = builder.config();
        this.port = -1;

        InetAddress listenAddress;
        try {
            listenAddress = InetAddress.getByName(builder.host());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Failed to create address for host: " + builder.host(), e);
        }
        this.host = listenAddress.getHostName();

        int builderPort = builder.port();
        String basePath = builder.basePath();
        boolean retainDiscoveredApplications = builder.retainDiscoveredApplications();
        List<JaxRsApplication> applications = List.copyOf(builder.applications());
        List<Class<?>> resourceClasses = List.copyOf(builder.resourceClasses());

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        this.classLoader = contextClassLoader == null ? Server.class.getClassLoader() : contextClassLoader;

        this.containerInitializer = addHelidonBeanDefiningAnnotations(SeContainerInitializer.newInstance(), this.classLoader)
                .setClassLoader(ServerImpl.this.classLoader)
                .addExtensions(new Extension() {
                    private void configureBasePath(@Observes @Priority(PLATFORM_BEFORE + 1) AfterDeploymentValidation event,
                                                   BeanManager beanManager) {
                        // Apply the builder value after BuildTimeEnd and before RuntimeStart customizations.
                        if (basePath != null) {
                            beanManager.getExtension(ServerCdiExtension.class).basePath(basePath);
                        }
                    }

                    private void configure(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
                        ServerCdiExtension serverCdiExtension = beanManager.getExtension(ServerCdiExtension.class);
                        serverCdiExtension.serverBuilder()
                                .port(builderPort)
                                .address(listenAddress);
                        serverCdiExtension.listenHost(ServerImpl.this.host);

                        JaxRsCdiExtension jaxRsCdiExtension = beanManager.getExtension(JaxRsCdiExtension.class);
                        if ((!applications.isEmpty() || !resourceClasses.isEmpty()) && !retainDiscoveredApplications) {
                            jaxRsCdiExtension.removeApplications();
                        }
                        if (!resourceClasses.isEmpty()) {
                            jaxRsCdiExtension.removeResourceClasses();
                            jaxRsCdiExtension.addResourceClasses(resourceClasses);
                        }
                        jaxRsCdiExtension.addApplications(applications);
                        if (!resourceClasses.isEmpty()) {
                            jaxRsCdiExtension.addSyntheticApplication(resourceClasses);
                        }
                    }
                });
    }

    @Override
    public Server start() {
        STARTUP_LOGGER.log(Level.TRACE, ServerImpl.class.getName(), "start ENTRY");

        this.containerAccess.lock();
        try {
            if (this.container != null) {
                if (!this.container.isRunning()) {
                    throw new IllegalStateException("Server has been stopped; build a new server instead");
                }
                return this;
            }

            ConfigProviderResolver.instance().registerConfig(this.config, this.classLoader);
            SeContainer startedContainer = initialize(addCdiProperties(this.containerInitializer, this.config));
            try {
                this.port = startedContainer.getBeanManager().getExtension(ServerCdiExtension.class).port();
                this.container = startedContainer;
            } catch (RuntimeException | Error e) {
                try {
                    startedContainer.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }

            STARTUP_LOGGER.log(Level.TRACE, "Started up");
        } finally {
            this.containerAccess.unlock();
        }
        // Honor this launcher's exit request after CDI initialization completes.
        if ("!".equals(System.getProperty("exit.on.started"))) {
            STARTUP_LOGGER.log(Level.INFO, "Exiting, -Dexit.on.started set.");
            System.exit(0);
        }
        return this;
    }

    @Override
    public Server stop() {
        this.containerAccess.lock();
        try {
            if (this.container == null) {
                throw new IllegalStateException("Server has not been started");
            }
            this.container.close();
            return this;
        } finally {
            this.containerAccess.unlock();
        }
    }

    @Override
    public String host() {
        return this.host;
    }

    @Override
    public int port() {
        return this.port;
    }

}
