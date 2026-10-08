/*
 * Copyright (c) 2019, 2026 Oracle and/or its affiliates.
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

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import io.helidon.common.Api;
import io.helidon.logging.common.LogConfig;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;

/**
 * This is the "master" main class of Helidon MP.
 * You can boot the Helidon MP instance using this class if you do not need to modify
 * anything using builders.
 * <h2>Startup</h2>
 * <h3>Configuration</h3>
 * <p>
 * If this method is used, the following approach is taken for configuration:
 * <ul>
 *     <li>If there is a {@code meta-config} file in one of the supported formats (a config parser on the classpath,
 *      e.g. {@code yaml}, it will be used to set up configuration</li>
 *      <li>If there are any MicroProfile config sources, these will be added</li>
 *      <li>If there are any {@code META-INF/microprofile-config.properties} files on the classpath, these will be added</li>
 * </ul>
 * <h3>Logging</h3>
 * Helidon uses Java Util Logging. You can configure logging using:
 * <ul>
 *     <li>A system property {@code java.util.logging.config.class}</li>
 *     <li>A system property {@code java.util.logging.config.file}</li>
 *     <li>Placing file {@code logging.properties} on the current path</li>
 *     <li>Placing file {@code logging.properties} on the classpath</li>
 * </ul>
 */
@Api.Stable
public final class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static final Lock CONTAINER_ACCESS = new ReentrantLock();
    private static SeContainer inUse;

    private Main() {
    }

    /**
     * Start CDI.
     * This will also start all features on the classpath, such as JAX-RS.
     * This method should only be invoked when custom configuration is not used (in cases you rely on
     * config sources from classpath, or on meta-configuration).
     * If the container started by this class is still running, this method does nothing.
     *
     * @param args command line arguments, currently ignored
     */
    public static void main(String[] args) {
        boolean started = false;
        CONTAINER_ACCESS.lock();
        try {
            if (inUse == null || !inUse.isRunning()) {
                LogConfig.initClass();
                inUse = addCdiProperties(addHelidonBeanDefiningAnnotations(SeContainerInitializer.newInstance())).initialize();
                started = true;
            }
        } finally {
            CONTAINER_ACCESS.unlock();
        }
        // Honor this launcher's exit request after CDI initialization completes.
        if (started && "!".equals(System.getProperty("exit.on.started"))) {
            LOGGER.info("Exiting, -Dexit.on.started set.");
            System.exit(0);
        }
    }

    /**
     * Shut down the CDI container started by this class and release its reference.
     * If this class has no running container, this method does nothing.
     * Containers started independently must be closed by their callers.
     */
    public static void shutdown() {
        CONTAINER_ACCESS.lock();
        try {
            SeContainer container = inUse;
            inUse = null;
            if (container != null && container.isRunning()) {
                LOGGER.finest("Shutting down container from cdi.Main");
                container.close();
            }
        } finally {
            CONTAINER_ACCESS.unlock();
        }
    }
}
