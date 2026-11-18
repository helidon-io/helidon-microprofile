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
package io.helidon.microprofile.telemetry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import io.helidon.config.Config;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Owns the SDK and instrumentation for one CDI application. Borrowed SDKs are never shut down.
 * <p>
 * Integration operations retain a {@link Lease} while using the SDK. Shutdown stops admitting new
 * operations and keeps the SDK available to admitted work until all outstanding leases are released,
 * then closes integration-owned resources and an owned SDK.
 */
@ApplicationScoped
class TelemetrySdk {
    private static final String DISABLED = "otel.sdk.disabled";
    private final Config helidonConfig;
    private final org.eclipse.microprofile.config.Config config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();
    private State state = State.INITIALIZING;
    private volatile OpenTelemetry telemetry = OpenTelemetry.noop();
    private OpenTelemetrySdk owned;
    private RuntimeTelemetry runtimeMetrics;
    private TelemetryLogHandler logHandler;
    private CompletableResultCode shutdown;
    private int active;
    private volatile boolean runtimeEnabled;
    private volatile boolean enabled;
    private volatile boolean propagationEnabled;
    private List<String> propagationHeaders = List.of();
    private TextMapPropagator propagator = TextMapPropagator.noop();

    @Inject
    TelemetrySdk(Config helidonConfig, org.eclipse.microprofile.config.Config config) {
        this.helidonConfig = helidonConfig;
        this.config = config;
    }

    OpenTelemetry telemetry() {
        return telemetry;
    }

    boolean runtimeEnabled() {
        return runtimeEnabled;
    }

    boolean instrumentationEnabled() {
        return enabled;
    }

    boolean propagationEnabled() {
        return propagationEnabled;
    }

    TextMapPropagator propagator() {
        return propagator;
    }

    boolean propagationRequired(Map<String, ?> headers) {
        if (!propagationEnabled) {
            return false;
        }
        for (String header : propagationHeaders) {
            if (headers.containsKey(header)) {
                return true;
            }
        }
        // Honor custom extractors that use no declared carrier fields, while skipping the native no-op.
        return propagationHeaders.isEmpty() && propagator != TextMapPropagator.noop();
    }

    Lease acquire() {
        if (!enabled) {
            return null;
        }
        lock.lock();
        try {
            if (state != State.RUNNING || !enabled) {
                return null;
            }
            active++;
            return new Lease(this);
        } finally {
            lock.unlock();
        }
    }

    @PostConstruct
    void initialize() {
        lock.lock();
        try {
            if (state != State.INITIALIZING) {
                throw new IllegalStateException("Telemetry SDK initialization requires the initializing state");
            }
            Map<String, String> properties = properties(config);
            String runtimeSetting = System.getProperty(DISABLED, System.getenv("OTEL_SDK_DISABLED"));
            runtimeEnabled = "false".equalsIgnoreCase(runtimeSetting);
            boolean agentPresent = HelidonOpenTelemetry.AgentDetector.isAgentPresent(helidonConfig);
            // A runtime SDK uses runtime configuration; application settings must not reconfigure it.
            if (runtimeEnabled) {
                properties.clear();
                properties.put(DISABLED, "false");
            }
            if (HelidonOpenTelemetry.AgentDetector.useExistingGlobalOpenTelemetry(helidonConfig)) {
                telemetry = GlobalOpenTelemetry.get();
                enabled = !agentPresent && !Boolean.parseBoolean(properties.get(DISABLED));
            } else {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                String applicationName = runtimeEnabled ? null : config.getOptionalValue("mp.application.name", String.class)
                        .orElse(null);
                owned = AutoConfiguredOpenTelemetrySdk.builder()
                        .addPropertiesCustomizer(_ -> properties)
                        .addResourceCustomizer((resource, effective) -> {
                            // This callback sees the final properties, including ServiceLoader customizers.
                            enabled = !effective.getBoolean(DISABLED, true);
                            return resource;
                        })
                        .setComponentLoader(components(loader == null ? TelemetrySdk.class.getClassLoader() : loader,
                                                       applicationName))
                        .disableShutdownHook()
                        .build()
                        .getOpenTelemetrySdk();
                telemetry = owned;
            }
            // Integration-owned instrumentation is separate from SDK ownership. An agent supplies its own.
            if (enabled) {
                if (runtimeEnabled) {
                    runtimeMetrics = RuntimeTelemetry.create(telemetry);
                }
                logHandler = new TelemetryLogHandler(this);
                Logger.getLogger("").addHandler(logHandler);
            }
            propagator = telemetry.getPropagators().getTextMapPropagator();
            propagationHeaders = List.copyOf(propagator.fields());
            state = State.RUNNING;
            propagationEnabled = !agentPresent;
        } catch (RuntimeException | Error failure) {
            state = State.CLEANUP_FAILED;
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void close() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            state = State.STOPPING;
            enabled = false;
            propagationEnabled = false;
            if (logHandler != null) {
                Logger.getLogger("").removeHandler(logHandler);
            }
            while (active != 0) {
                try {
                    drained.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Telemetry shutdown interrupted while draining active operations", e);
                }
            }
            if (runtimeMetrics != null) {
                runtimeMetrics.close();
                runtimeMetrics = null;
            }
            if (owned != null) {
                if (shutdown == null) {
                    shutdown = owned.shutdown();
                }
                shutdown.join(10, TimeUnit.SECONDS);
                if (!shutdown.isSuccess()) {
                    throw new IllegalStateException("Telemetry SDK shutdown did not complete successfully");
                }
                owned = null;
            }
            state = State.CLOSED;
        } catch (RuntimeException | Error e) {
            state = State.CLEANUP_FAILED;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private static ComponentLoader components(ClassLoader loader, String applicationName) {
        var delegate = ComponentLoader.forClassLoader(loader);
        if (applicationName == null) {
            return delegate;
        }
        Resource fallback = Resource.builder().put(ServiceAttributes.SERVICE_NAME, applicationName).build();
        var provider = new ResourceProvider() {
            @Override
            public Resource createResource(ConfigProperties config) {
                return fallback;
            }

            @Override
            public int order() {
                // A default only: configured names and application ResourceProviders retain precedence.
                return Integer.MIN_VALUE;
            }
        };
        return new ComponentLoader() {
            @Override
            public <T> Iterable<T> load(Class<T> spiClass) {
                if (spiClass != ResourceProvider.class) {
                    return delegate.load(spiClass);
                }
                var providers = new ArrayList<T>();
                providers.add(spiClass.cast(provider));
                delegate.load(spiClass).forEach(providers::add);
                return providers;
            }
        };
    }

    private static Map<String, String> properties(org.eclipse.microprofile.config.Config config) {
        Map<String, String> result = new HashMap<>();
        for (String name : config.getPropertyNames()) {
            String canonical = name.startsWith("OTEL_")
                    ? name.toLowerCase(Locale.ROOT).replace('_', '.') : name;
            if (canonical.startsWith("otel.")) {
                config.getOptionalValue(canonical, String.class)
                        .or(() -> config.getOptionalValue(name, String.class))
                        .ifPresent(value -> result.put(canonical, value));
            }
        }
        // MP differs from the OpenTelemetry default: SDK support must be explicitly enabled.
        result.putIfAbsent(DISABLED, "true");
        return result;
    }

    private void release() {
        lock.lock();
        try {
            if (--active == 0) {
                drained.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private enum State {
        INITIALIZING, RUNNING, STOPPING, CLOSED, CLEANUP_FAILED
    }

    /**
     * Reserves an active integration operation so SDK shutdown waits for its completion.
     * A successful {@link TelemetrySdk#acquire()} increments the owner's active-operation count while
     * instrumentation is enabled and the SDK is running. The holder must retain the lease until all work
     * using the SDK has finished, including asynchronous callbacks or response writing. Returning from
     * a method or canceling a result future does not establish that the underlying work has stopped.
     * <p>
     * {@link TelemetrySdk#close()} stops admitting new operations and drains outstanding leases before
     * releasing integration-owned resources and shutting down an owned SDK. A borrowed SDK remains
     * externally owned and is never shut down by this component.
     * <p>
     * {@link #close()} releases the reservation exactly once. It is thread-safe and idempotent, so a
     * completion thread can release the lease and duplicate cleanup attempts cannot decrement the
     * active-operation count twice.
     * <p>
     * Retains the contextual owner itself so asynchronous completion never needs to resolve a CDI
     * proxy during shutdown.
     */
    static final class Lease implements AutoCloseable {
        private final TelemetrySdk owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(TelemetrySdk owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }

}
