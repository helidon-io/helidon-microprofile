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

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.service.registry.Services;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class RegistryTest {

    @Test
    void routesMetersUsingMpScopeTag() {
        ConfigProvider.getConfig();
        MetricsFactory metricsFactory = Services.get(MetricsFactory.class);
        io.helidon.metrics.api.MeterRegistry meterRegistry =
                metricsFactory.createMeterRegistry(metricsFactory.metricsConfig());
        RegistryFactory registryFactory = RegistryFactory.create(meterRegistry);
        Registry application = registryFactory.registry(MetricRegistry.APPLICATION_SCOPE);
        Registry base = registryFactory.registry(MetricRegistry.BASE_SCOPE);
        Registry vendor = registryFactory.registry(MetricRegistry.VENDOR_SCOPE);
        Registry custom = registryFactory.registry("custom");

        try {
            var applicationId = new MetricID("mp.application", new Tag("color", "blue"));
            var applicationCounter = application.counter(applicationId);
            var baseCounter = base.counter("mp.base");
            var vendorCounter = vendor.counter("mp.vendor");
            var customCounter = custom.counter("mp.custom");

            assertThat("Application metric ID retains only user tags",
                       application.getCounter(applicationId),
                       sameInstance(applicationCounter));
            assertMeterScope(meterRegistry, applicationId.getName(), MetricRegistry.APPLICATION_SCOPE);
            assertMeterScope(meterRegistry, "mp.base", MetricRegistry.BASE_SCOPE);
            assertMeterScope(meterRegistry, "mp.vendor", MetricRegistry.VENDOR_SCOPE);
            assertMeterScope(meterRegistry, "mp.custom", "custom");
            assertThat("Base metric routes to base registry",
                       base.getCounter(new MetricID("mp.base")),
                       sameInstance(baseCounter));
            assertThat("Vendor metric routes to vendor registry",
                       vendor.getCounter(new MetricID("mp.vendor")),
                       sameInstance(vendorCounter));
            assertThat("Custom metric routes to custom registry",
                       custom.getCounter(new MetricID("mp.custom")),
                       sameInstance(customCounter));
            assertThat("Scope tag is not exposed as an MP metric tag",
                       custom.getMetricIDs().iterator().next().getTags().containsKey(MpScope.TAG_NAME),
                       is(false));

            Meter baseMeter = meterRegistry.getOrCreate(metricsFactory.counterBuilder("core.base")
                                                                 .origin("io.helidon.metrics.systemmeters.SystemMetersProvider"));
            Meter vendorMeter = meterRegistry.getOrCreate(metricsFactory.counterBuilder("core.vendor")
                                                                   .origin("io.helidon.faulttolerance.FaultTolerance"));
            Meter applicationMeter = meterRegistry.getOrCreate(metricsFactory.counterBuilder("core.application"));

            assertMeterScope(baseMeter, MetricRegistry.BASE_SCOPE);
            assertMeterScope(vendorMeter, MetricRegistry.VENDOR_SCOPE);
            assertMeterScope(applicationMeter, MetricRegistry.APPLICATION_SCOPE);
            assertThat("Core base meter routes to base registry",
                       base.getCounter(new MetricID("core.base")),
                       notNullValue());
            assertThat("Core vendor meter routes to vendor registry",
                       vendor.getCounter(new MetricID("core.vendor")),
                       notNullValue());
            assertThat("Originless core meter routes to application registry",
                       application.getCounter(new MetricID("core.application")),
                       notNullValue());

            meterRegistry.remove(applicationMeter);
            assertThat("Removed core meter leaves the application registry",
                       application.getCounter(new MetricID("core.application")),
                       nullValue());
            assertThat("Add and remove callbacks never create a null-scoped registry",
                       registryFactory.scopes().contains(null),
                       is(false));

            var maliciousBuilder = metricsFactory.counterBuilder("neutral.illegal.scope.tag")
                    .addTag(metricsFactory.tagCreate(MpScope.TAG_NAME, MetricRegistry.VENDOR_SCOPE));
            IllegalArgumentException neutralException =
                    assertThrows(IllegalArgumentException.class, () -> meterRegistry.getOrCreate(maliciousBuilder));
            assertThat(neutralException.getMessage(), containsString(MpScope.TAG_NAME));

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                               () -> application.counter(
                                                                       "illegal.scope.tag",
                                                                       new Tag(MpScope.TAG_NAME,
                                                                               MetricRegistry.VENDOR_SCOPE)));
            assertThat(exception.getMessage(), containsString(MpScope.TAG_NAME));
        } finally {
            registryFactory.close();
            meterRegistry.close();
        }
    }

    @Test
    void registrationContextDoesNotLeakAfterFailure() {
        ConfigProvider.getConfig();
        MetricsFactory metricsFactory = Services.get(MetricsFactory.class);
        io.helidon.metrics.api.MeterRegistry failingMeterRegistry =
                (io.helidon.metrics.api.MeterRegistry) Proxy.newProxyInstance(
                        RegistryTest.class.getClassLoader(),
                        new Class<?>[] {io.helidon.metrics.api.MeterRegistry.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getOrCreate")) {
                                assertThat("Registration scope while creating an MP meter",
                                           MpScope.registrationScope(),
                                           is(Optional.of(MetricRegistry.BASE_SCOPE)));
                                throw new IllegalStateException("deliberate registration failure");
                            }
                            throw new AssertionError("Unexpected meter registry method " + method.getName());
                        });

        assertThrows(IllegalStateException.class,
                     () -> MpScope.getOrCreate(failingMeterRegistry,
                                              metricsFactory,
                                              MetricRegistry.BASE_SCOPE,
                                              metricsFactory.counterBuilder("context.failure")));

        var builderAfterFailure = metricsFactory.counterBuilder("context.after.failure");
        new MpMeterBuilderCustomizer().customize(builderAfterFailure);
        assertThat("Originless meter after failed MP registration",
                   builderAfterFailure.tags().get(MpScope.TAG_NAME),
                   is(MetricRegistry.APPLICATION_SCOPE));
    }

    @Test
    void testGaugeRegistrationIsNotBlockedByPendingRemove() throws Exception {
        ConfigProvider.getConfig();
        MetricsFactory metricsFactory = Services.get(MetricsFactory.class);
        io.helidon.metrics.api.MeterRegistry delegateMeterRegistry =
                metricsFactory.createMeterRegistry(metricsFactory.metricsConfig());
        CountDownLatch removeEntered = new CountDownLatch(1);
        CountDownLatch allowRemoveToProceed = new CountDownLatch(1);
        io.helidon.metrics.api.MeterRegistry blockingMeterRegistry =
                new BlockingRemoveMeterRegistry(delegateMeterRegistry, removeEntered, allowRemoveToProceed);
        RegistryFactory registryFactory = RegistryFactory.create(blockingMeterRegistry);
        MetricRegistry registry = registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE);

        MetricID removedGaugeId = new MetricID("gauge",
                                               new Tag("issue", "11604"),
                                               new Tag("sequence", "1"));
        MetricID addedGaugeId = new MetricID("gauge",
                                             new Tag("issue", "11604"),
                                             new Tag("sequence", "2"));

        Gauge<Integer> removedGauge = registry.gauge(removedGaugeId, () -> 1);
        assertThat("Initial gauge should be registered before removal",
                   registry.getGauge(removedGaugeId),
                   sameInstance(removedGauge));

        ExecutorService removeExecutor = Executors.newSingleThreadExecutor();
        ExecutorService addExecutor = Executors.newSingleThreadExecutor();
        Throwable failure = null;
        try {
            Future<Boolean> removeFuture = removeExecutor.submit(() -> registry.remove(removedGaugeId));
            awaitUnderlyingRemove(removeEntered, removeFuture);

            Future<Gauge<Integer>> addFuture = addExecutor.submit(() -> registry.gauge(addedGaugeId, () -> 2));

            Gauge<Integer> addedGauge;
            try {
                addedGauge = addFuture.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("Concurrent gauge registration blocked while remove was waiting on the underlying meter registry", e);
                return;
            }

            assertThat("New gauge should be registered while remove is still pending", addedGauge.getValue(), is(2));

            allowRemoveToProceed.countDown();
            assertThat("Expected remove to complete once released", removeFuture.get(5, TimeUnit.SECONDS), is(true));
        } catch (Throwable t) {
            failure = t;
        } finally {
            allowRemoveToProceed.countDown();
            removeExecutor.shutdownNow();
            addExecutor.shutdownNow();
            // If cleanup also fails, preserve the original failure and add cleanup problems as suppressed.
            failure = collectFailure(failure, closeFailure(registryFactory::close, "registry factory"));
            failure = collectFailure(failure, closeFailure(delegateMeterRegistry::close, "delegate meter registry"));
        }

        failure = collectFailure(failure, awaitTerminationFailure(removeExecutor, "remove executor"));
        failure = collectFailure(failure, awaitTerminationFailure(addExecutor, "add executor"));
        throwIfNeeded(failure);
    }

    private static void awaitUnderlyingRemove(CountDownLatch removeEntered, Future<Boolean> removeFuture) throws Exception {
        if (removeEntered.await(5, TimeUnit.SECONDS)) {
            return;
        }
        assertThat("Underlying meter removal did not start within 5 seconds and the removal task remains incomplete",
                   removeFuture.isDone(),
                   is(true));
        try {
            assertThat("Removal completed without invoking the underlying meter registry",
                       removeFuture.get(),
                       nullValue());
        } catch (ExecutionException e) {
            assertThat("Removal failed before invoking the underlying meter registry",
                       e.getCause(),
                       nullValue());
        }
    }

    private static void assertMeterScope(io.helidon.metrics.api.MeterRegistry meterRegistry,
                                         String name,
                                         String expectedScope) {
        Meter meter = meterRegistry.meters().stream()
                .filter(candidate -> candidate.id().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing meter " + name));
        assertMeterScope(meter, expectedScope);
    }

    private static void assertMeterScope(Meter meter, String expectedScope) {
        assertThat("MP scope tag for " + meter.id().name(),
                   meter.id().tagsMap().get(MpScope.TAG_NAME),
                   is(expectedScope));
    }

    private static Throwable closeFailure(Runnable closeAction, String description) {
        try {
            closeAction.run();
            return null;
        } catch (Throwable t) {
            return new AssertionError("Failed to close " + description, t);
        }
    }

    private static Throwable awaitTerminationFailure(ExecutorService executor, String description) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                return new AssertionError(description + " did not terminate in time");
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AssertionError("Interrupted while waiting for " + description + " termination", e);
        }
    }

    private static Throwable collectFailure(Throwable currentFailure, Throwable newFailure) {
        if (newFailure == null) {
            return currentFailure;
        }
        if (currentFailure == null) {
            return newFailure;
        }
        currentFailure.addSuppressed(newFailure);
        return currentFailure;
    }

    private static void throwIfNeeded(Throwable failure) throws Exception {
        switch (failure) {
        case null -> {
        }
        case Exception exception -> throw exception;
        case Error error -> throw error;
        default -> throw new RuntimeException(failure);
        }
    }

    private static final class BlockingRemoveMeterRegistry implements io.helidon.metrics.api.MeterRegistry {

        private final io.helidon.metrics.api.MeterRegistry delegate;
        private final CountDownLatch removeEntered;
        private final CountDownLatch allowRemoveToProceed;

        private BlockingRemoveMeterRegistry(io.helidon.metrics.api.MeterRegistry delegate,
                                            CountDownLatch removeEntered,
                                            CountDownLatch allowRemoveToProceed) {
            this.delegate = delegate;
            this.removeEntered = removeEntered;
            this.allowRemoveToProceed = allowRemoveToProceed;
        }

        @Override
        public List<io.helidon.metrics.api.Meter> meters() {
            return delegate.meters();
        }

        @Override
        public Collection<io.helidon.metrics.api.Meter> meters(Predicate<io.helidon.metrics.api.Meter> filter) {
            return delegate.meters(filter);
        }

        @Override
        public Iterable<io.helidon.metrics.api.Meter> meters(Iterable<String> scopeSelection) {
            return delegate.meters(scopeSelection);
        }

        @Override
        public Iterable<String> scopes() {
            return delegate.scopes();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean isMeterEnabled(String name, Map<String, String> tags, Optional<String> scope) {
            return delegate.isMeterEnabled(name, tags, scope);
        }

        @Override
        public io.helidon.metrics.api.Clock clock() {
            return delegate.clock();
        }

        @Override
        public <B extends io.helidon.metrics.api.Meter.Builder<B, M>, M extends io.helidon.metrics.api.Meter> M getOrCreate(
                B builder) {
            return delegate.getOrCreate(builder);
        }

        @Override
        public <M extends io.helidon.metrics.api.Meter> Optional<M> meter(Class<M> mClass,
                                                                           String name,
                                                                           Iterable<io.helidon.metrics.api.Tag> tags) {
            return delegate.meter(mClass, name, tags);
        }

        @Override
        public Optional<io.helidon.metrics.api.Meter> remove(io.helidon.metrics.api.Meter meter) {
            awaitRemoveRelease();
            return delegate.remove(meter);
        }

        @Override
        public Optional<io.helidon.metrics.api.Meter> remove(io.helidon.metrics.api.Meter.Id id) {
            return delegate.remove(id);
        }

        @Override
        public Optional<io.helidon.metrics.api.Meter> remove(io.helidon.metrics.api.Meter.Id id, String scope) {
            return delegate.remove(id, scope);
        }

        @Override
        public Optional<io.helidon.metrics.api.Meter> remove(String name,
                                                             Iterable<io.helidon.metrics.api.Tag> tags) {
            return delegate.remove(name, tags);
        }

        @Override
        public Optional<io.helidon.metrics.api.Meter> remove(String name,
                                                             Iterable<io.helidon.metrics.api.Tag> tags,
                                                             String scope) {
            return delegate.remove(name, tags, scope);
        }

        @Override
        public boolean isDeleted(io.helidon.metrics.api.Meter meter) {
            return delegate.isDeleted(meter);
        }

        @Override
        public io.helidon.metrics.api.MeterRegistry onMeterAdded(Consumer<io.helidon.metrics.api.Meter> onAddListener) {
            delegate.onMeterAdded(onAddListener);
            return this;
        }

        @Override
        public io.helidon.metrics.api.MeterRegistry onMeterRemoved(
                Consumer<io.helidon.metrics.api.Meter> onRemoveListener) {
            delegate.onMeterRemoved(onRemoveListener);
            return this;
        }

        @Override
        public <R> R unwrap(Class<? extends R> c) {
            return delegate.unwrap(c);
        }

        private void awaitRemoveRelease() {
            removeEntered.countDown();
            try {
                if (!allowRemoveToProceed.await(5, TimeUnit.SECONDS)) {
                    fail("Timed out waiting to release the underlying meter removal");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting to release the underlying meter removal", e);
            }
        }
    }
}
