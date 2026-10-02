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

package io.helidon.microprofile.faulttolerance;

import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

import static java.util.Objects.requireNonNull;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.BASE;

/**
 * Utility class housing FT metrics.
 */
final class FaultToleranceMetrics {

    private FaultToleranceMetrics() {
    }

    enum InvocationResult implements Supplier<Tag> {
        VALUE_RETURNED("valueReturned"),
        EXCEPTION_THROWN("exceptionThrown");

        private final Tag metricTag;

        InvocationResult(String value) {
            metricTag = new Tag("result", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    enum InvocationFallback implements Supplier<Tag> {
        APPLIED("applied"),
        NOT_APPLIED("notApplied"),
        NOT_DEFINED("notDefined");

        private final Tag metricTag;

        InvocationFallback(String value) {
            metricTag = new Tag("fallback", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    // -- Invocations ---------------------------------------------------------

    enum RetryResult implements Supplier<Tag> {
        VALUE_RETURNED("valueReturned"),
        EXCEPTION_NOT_RETRYABLE("exceptionNotRetryable"),
        MAX_RETRIES_REACHED("maxRetriesReached"),
        MAX_DURATION_REACHED("maxDurationReached");

        private final Tag metricTag;

        RetryResult(String value) {
            metricTag = new Tag("retryResult", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    enum RetryRetried implements Supplier<Tag> {
        TRUE("true"),
        FALSE("false");

        private final Tag metricTag;

        RetryRetried(String value) {
            metricTag = new Tag("retried", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    enum TimeoutTimedOut implements Supplier<Tag> {
        TRUE("true"),
        FALSE("false");

        private final Tag metricTag;

        TimeoutTimedOut(String value) {
            this.metricTag = new Tag("timedOut", value);
        }

        public Tag get() {
            return metricTag;
        }
    }

    // -- Retries -------------------------------------------------------------

    enum CircuitBreakerResult implements Supplier<Tag> {
        SUCCESS("success"),
        FAILURE("failure"),
        CIRCUIT_BREAKER_OPEN("circuitBreakerOpen");

        private final Tag metricTag;

        CircuitBreakerResult(String value) {
            metricTag = new Tag("circuitBreakerResult", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    enum CircuitBreakerState implements Supplier<Tag> {
        OPEN("open"),
        CLOSED("closed"),
        HALF_OPEN("halfOpen");

        private final Tag metricTag;

        CircuitBreakerState(String value) {
            metricTag = new Tag("state", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    enum BulkheadResult implements Supplier<Tag> {
        ACCEPTED("accepted"),
        REJECTED("rejected");

        private final Tag metricTag;

        BulkheadResult(String value) {
            metricTag = new Tag("bulkheadResult", value);
        }

        @Override
        public Tag get() {
            return metricTag;
        }
    }

    // -- Timeouts ------------------------------------------------------------

    /**
     * Base class for Fault Tolerance metrics. Shares common logic for registration
     * and lookup of metrics.
     */
    abstract static class FaultToleranceMetric {

        private final MetricRegistry mr;

        FaultToleranceMetric(MetricRegistry mr) {
            super();
            this.mr = requireNonNull(mr, "mr");
        }

        abstract String name();

        abstract String description();

        abstract Class<? extends Metric> metricType();

        abstract String unit();

        protected Counter getCounter(Tag... tags) {
            MetricID metricID = new MetricID(name(), tags);
            return (Counter) this.mr.getMetrics().get(metricID);
        }

        protected Counter registerCounter(Tag... tags) {
            Counter counter = getCounter(tags);
            if (counter == null) {
                Metadata metadata = Metadata.builder()
                        .withName(name())
                        .withDescription(description())
                        .withUnit(unit())
                        .build();
                try {
                    counter = this.mr.counter(metadata, tags);
                } catch (IllegalArgumentException e) {
                    // Looks like we lost registration race
                    counter = getCounter(tags);
                    requireNonNull(counter);
                }
            }
            return counter;
        }

        protected Histogram getHistogram(Tag... tags) {
            MetricID metricID = new MetricID(name(), tags);
            return (Histogram) this.mr.getMetrics().get(metricID);
        }

        protected Histogram registerHistogram(Tag... tags) {
            Histogram histogram = getHistogram(tags);
            if (histogram == null) {
                Metadata metadata = Metadata.builder()
                        .withName(name())
                        .withDescription(description())
                        .withUnit(unit())
                        .build();
                try {
                    histogram = this.mr.histogram(metadata, tags);
                } catch (IllegalArgumentException e) {
                    // Looks like we lost the registration race
                    histogram = getHistogram(tags);
                    requireNonNull(histogram);
                }
            }
            return histogram;
        }

        @SuppressWarnings("unchecked")
        protected <T extends Number> Gauge<T> getGauge(Tag... tags) {
            MetricID metricID = new MetricID(name(), tags);
            return (Gauge<T>) this.mr.getMetrics().get(metricID);
        }

        protected <T extends Number> Gauge<T> registerGauge(Gauge<T> newGauge, Tag... tags) {
            Gauge<T> gauge = getGauge(tags);
            if (gauge == null) {
                Metadata metadata = Metadata.builder()
                        .withName(name())
                        .withDescription(description())
                        .withUnit(unit())
                        .build();
                try {
                    gauge = this.mr.gauge(metadata, newGauge::getValue, tags);
                } catch (IllegalArgumentException e) {
                    // Looks like we lost the registration race
                    gauge = getGauge(tags);
                    requireNonNull(gauge);
                }
            }
            return gauge;
        }
    }

    /**
     * Class for "ft.invocations.total" counters.
     */
    @Singleton
    static class InvocationsTotal extends FaultToleranceMetric {

        @Inject
        InvocationsTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.invocations.total";
        }

        @Override
        String description() {
            return "The number of times the method was called";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.retry.calls.total" counters.
     */
    @Singleton
    static class RetryCallsTotal extends FaultToleranceMetric {

        @Inject
        RetryCallsTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.retry.calls.total";
        }

        @Override
        String description() {
            return "The number of times the retry logic was run. This will always be once per method call.";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    // --- CircuitBreakers ----------------------------------------------------

    /**
     * Class for "ft.retry.retries.total" counters.
     */
    @Singleton
    static class RetryRetriesTotal extends FaultToleranceMetric {

        @Inject
        RetryRetriesTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.retry.retries.total";
        }

        @Override
        String description() {
            return "The number of times the method was retried";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.timeout.calls.total" counters.
     */
    @Singleton
    static class TimeoutCallsTotal extends FaultToleranceMetric {

        @Inject
        private TimeoutCallsTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.timeout.calls.total";
        }

        @Override
        String description() {
            return "The number of times the timeout logic was run. This will usually be once "
                    + "per method call, but may be zero times if the circuit breaker prevents "
                    + "execution or more than once if the method is retried.";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.timeout.executionDuration" histograms.
     */
    @Singleton
    static class TimeoutExecutionDuration extends FaultToleranceMetric {

        @Inject
        TimeoutExecutionDuration(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Histogram get(Tag... tags) {
            return this.registerHistogram(tags);
        }

        @Override
        String name() {
            return "ft.timeout.executionDuration";
        }

        @Override
        String description() {
            return "Histogram of execution times for the method";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Histogram.class;
        }

        @Override
        String unit() {
            return MetricUnits.NANOSECONDS;
        }
    }

    @Singleton
    static class CircuitBreakerCallsTotal extends FaultToleranceMetric {

        @Inject
        CircuitBreakerCallsTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.circuitbreaker.calls.total";
        }

        @Override
        String description() {
            return "The number of times the circuit breaker logic was run. This will usually be once "
                    + "per method call, but may be more than once if the method call is retried.";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.circuitbreaker.state.total" gauges.
     */
    @Singleton
    static class CircuitBreakerStateTotal extends FaultToleranceMetric {

        @Inject
        CircuitBreakerStateTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Gauge<Long> get(Tag... tags) {
            return this.getGauge(tags);
        }

        Gauge<Long> register(Gauge<Long> gauge, Tag... tags) {
            return this.registerGauge(gauge, tags);
        }

        @Override
        String name() {
            return "ft.circuitbreaker.state.total";
        }

        @Override
        String description() {
            return "Amount of time the circuit breaker has spent in each state";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Gauge.class;
        }

        @Override
        String unit() {
            return MetricUnits.NANOSECONDS;
        }
    }

    // --- Bulkheads ----------------------------------------------------------

    /**
     * Class for "ft.circuitbreaker.opened.total" counters.
     */
    @Singleton
    static class CircuitBreakerOpenedTotal extends FaultToleranceMetric {

        @Inject
        CircuitBreakerOpenedTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        Counter register(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.circuitbreaker.opened.total";
        }

        @Override
        String description() {
            return "Number of times the circuit breaker has moved from closed state to open state";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.bulkhead.calls.total" counters.
     */
    @Singleton
    static class BulkheadCallsTotal extends FaultToleranceMetric {

        @Inject
        BulkheadCallsTotal(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Counter get(Tag... tags) {
            return this.registerCounter(tags);
        }

        @Override
        String name() {
            return "ft.bulkhead.calls.total";
        }

        @Override
        String description() {
            return "The number of times the bulkhead logic was run. This will usually be once per "
                    + "method call, but may be zero times if the circuit breaker prevented execution "
                    + "or more than once if the method call is retried.";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Counter.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.bulkhead.executionsRunning" gauges.
     */
    @Singleton
    static class BulkheadExecutionsRunning extends FaultToleranceMetric {

        @Inject
        BulkheadExecutionsRunning(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Gauge<Long> get(Tag... tags) {
            return this.getGauge(tags);
        }

        Gauge<Long> register(Gauge<Long> gauge, Tag... tags) {
            return this.registerGauge(gauge, tags);
        }

        @Override
        String name() {
            return "ft.bulkhead.executionsRunning";
        }

        @Override
        String description() {
            return "Number of currently running executions";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Gauge.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.bulkhead.executionsWaiting" gauges.
     */
    @Singleton
    static class BulkheadExecutionsWaiting extends FaultToleranceMetric {

        @Inject
        BulkheadExecutionsWaiting(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Gauge<Long> register(Gauge<Long> gauge, Tag... tags) {
            return this.registerGauge(gauge, tags);
        }

        Gauge<Long> get(Tag... tags) {
            return this.getGauge(tags);
        }

        @Override
        String name() {
            return "ft.bulkhead.executionsWaiting";
        }

        @Override
        String description() {
            return "Number of executions currently waiting in the queue";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Gauge.class;
        }

        @Override
        String unit() {
            return MetricUnits.NONE;
        }
    }

    /**
     * Class for "ft.bulkhead.runningDuration" histograms.
     */
    @Singleton
    static class BulkheadRunningDuration extends FaultToleranceMetric {

        @Inject
        BulkheadRunningDuration(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Histogram get(Tag... tags) {
            return this.registerHistogram(tags);
        }

        @Override
        String name() {
            return "ft.bulkhead.runningDuration";
        }

        @Override
        String description() {
            return "Histogram of the time that method executions spent running";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Histogram.class;
        }

        @Override
        String unit() {
            return MetricUnits.NANOSECONDS;
        }
    }

    /**
     * Class for "ft.bulkhead.waitingDuration" histograms.
     */
    @Singleton
    static class BulkheadWaitingDuration extends FaultToleranceMetric {

        @Inject
        BulkheadWaitingDuration(@RegistryType(type = BASE) MetricRegistry mr) {
            super(mr);
        }

        Histogram get(Tag... tags) {
            return this.registerHistogram(tags);
        }

        @Override
        String name() {
            return "ft.bulkhead.waitingDuration";
        }

        @Override
        String description() {
            return "Histogram of the time that method executions spent waiting in the queue";
        }

        @Override
        Class<? extends Metric> metricType() {
            return Histogram.class;
        }

        @Override
        String unit() {
            return MetricUnits.NANOSECONDS;
        }
    }
}
