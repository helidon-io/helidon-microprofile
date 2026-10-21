/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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
package io.helidon.microprofile.testing;

import java.lang.System.Logger.Level;
import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import io.helidon.common.testing.virtualthreads.PinningRecorder;
import io.helidon.microprofile.cdi.CdiExtension;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;
import static io.helidon.microprofile.cdi.SeContainerInitializers.initialize;
import static io.helidon.microprofile.testing.ReflectionHelper.requirePublic;

/**
 * CDI container testing facade.
 */
public class HelidonTestContainer {

    /**
     * Indicate that the container previously failed to initialize.
     */
    public static final class InitializationFailed extends RuntimeException {
        private InitializationFailed(RuntimeException error) {
            super("Container initialization previously failed", error);
        }
    }

    private static final System.Logger LOGGER = System.getLogger(HelidonTestContainer.class.getName());
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private final HelidonTestInfo<?> testInfo;
    private final HelidonTestScope testScope;
    private final BiFunction<HelidonTestInfo<?>, HelidonTestScope, HelidonTestExtension> extensionFactory;
    private final AtomicBoolean closed;
    private final ReentrantLock lock;
    private final int id;

    private SeContainer container;
    private PinningRecorder pinningRecorder;
    private RuntimeException error;

    /**
     * Create a new instance.
     *
     * @param testInfo         test info
     * @param testScope        test scope
     * @param extensionFactory extension factory
     */
    public HelidonTestContainer(HelidonTestInfo<?> testInfo,
                                HelidonTestScope testScope,
                                BiFunction<HelidonTestInfo<?>, HelidonTestScope, HelidonTestExtension> extensionFactory) {
        super();
        this.testInfo = testInfo;
        this.testScope = testScope;
        this.extensionFactory = extensionFactory;
        this.closed = new AtomicBoolean(false);
        this.lock = new ReentrantLock();
        this.id = NEXT_ID.getAndIncrement();
    }

    /**
     * Stop the container.
     */
    public void close() {
        if (this.container != null && this.closed.compareAndSet(false, true)) {
            LOGGER.log(Level.DEBUG, "closing container id={0}", id);
            try {
                this.container.close();
            } finally {
                if (this.pinningRecorder != null) {
                    this.pinningRecorder.close();
                }
            }
        }
    }

    /**
     * Indicate if the container is closed.
     *
     * @return {@code true} if closed, {@code false} otherwise
     */
    public boolean closed() {
        return this.closed.get();
    }

    /**
     * Indicate if the container initializion failed.
     *
     * @return {@code true} if failed, {@code false} otherwise
     */
    public boolean initFailed() {
        return this.error != null;
    }

    /**
     * Resolve an unqualified bean of the given type.
     *
     * @param type type
     * @param qualifiers qualifiers
     * @param <T>  type
     * @return resolved instance
     * @throws InitializationFailed if the container previusly failed to
     *                              start
     */
    @SuppressWarnings("resource")
    public <T> T resolveInstance(Class<T> type, Annotation... qualifiers) throws InitializationFailed {
        if (SeContainer.class.isAssignableFrom(type)) {
            return type.cast(container());
        }
        return this.container().select(type, qualifiers).get();
    }

    /**
     * Test if the given type is supported for injection.
     *
     * @param type type
     * @param qualifiers qualifier annotations present on the injection point
     * @return {@code true} if supported, {@code false} otherwise
     * @throws InitializationFailed if the container previously failed to
     *                              start
     */
    @SuppressWarnings("resource")
    public boolean isSupported(Class<?> type, Annotation... qualifiers) throws InitializationFailed {
        return !this.container().select(type, qualifiers).isUnsatisfied();
    }

    private SeContainer container() {
        if (this.error == null && this.container == null) {
            this.lock.lock();
            try {
                if (this.error == null && this.container == null) {
                    this.start();
                }
            } catch (RuntimeException ex) {
                this.error = ex;
                throw ex;
            } finally {
                this.lock.unlock();
            }
        }
        if (this.error != null) {
            throw new InitializationFailed(this.error);
        }
        return this.container;
    }

    @SuppressWarnings("unchecked")
    private void start() {
        LOGGER.log(Level.DEBUG, "starting container\n{0}", this);
        HelidonTestExtension testExtension = null;
        try {
            if (this.testInfo.pinningDetection()) {
                this.pinningRecorder = PinningRecorder.create();
                this.pinningRecorder.record(Duration.ofMillis(this.testInfo.pinningThreshold()));
            }
            testExtension = this.extensionFactory.apply(this.testInfo, this.testScope);
            // Initializer properties need the test configuration before discovery starts.
            testExtension.resolveTestConfig();
            SeContainerInitializer initializer = addHelidonBeanDefiningAnnotations(SeContainerInitializer.newInstance());
            if (this.testInfo.disableDiscovery()) {
                initializer.disableDiscovery().addExtensions(CdiExtension.class);
            }
            for (AddExtension extension : this.testInfo.addExtensions()) {
                initializer.addExtensions(requirePublic(extension.value()));
            }
            initializer.addExtensions(testExtension);
            this.container = initialize(addCdiProperties(initializer));
        } catch (RuntimeException | Error failure) {
            // Test configuration and the recorder are acquired before initialize binds its cleanup actions.
            if (testExtension != null) {
                try {
                    testExtension.cleanup();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (this.pinningRecorder != null) {
                try {
                    this.pinningRecorder.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            throw failure;
        }
    }

    @Override
    public String toString() {
        return new PrettyPrinter()
                .object(printer -> printer
                        .value("id", id)
                        .object("testInfo", PrettyPrinters.testInfo(testInfo)))
                .toString();
    }
}
