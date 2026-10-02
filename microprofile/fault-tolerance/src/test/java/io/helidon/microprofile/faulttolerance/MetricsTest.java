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

import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.inject.Default;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.junit.jupiter.api.Test;

import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.BulkheadCallsTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.BulkheadExecutionsRunning;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.BulkheadExecutionsWaiting;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.BulkheadResult;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.BulkheadRunningDuration;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.BulkheadWaitingDuration;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.CircuitBreakerCallsTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.CircuitBreakerOpenedTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.CircuitBreakerResult;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.CircuitBreakerState;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.CircuitBreakerStateTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.InvocationFallback;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.InvocationFallback.NOT_DEFINED;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.InvocationResult;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.InvocationResult.EXCEPTION_THROWN;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.InvocationResult.VALUE_RETURNED;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.InvocationsTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.RetryCallsTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.RetryResult;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.RetryRetried;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.RetryRetriesTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.TimeoutCallsTotal;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.TimeoutExecutionDuration;
import static io.helidon.microprofile.faulttolerance.FaultToleranceMetrics.TimeoutTimedOut;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for bean metrics.
 */
class MetricsTest extends FaultToleranceTest {

    @Test
    void testInjectCounter(@Default MetricsBean bean) {
        assertThat(bean, notNullValue());
        bean.getCounter().inc();
        assertThat(bean.getCounter().getCount(), is(1L));
    }

    @Test
    void testInjectCounterProgrammatically(@Default MetricRegistry metricRegistry) {
        metricRegistry.counter(Metadata.builder()
                                       .withName("dcounter")
                                       .withUnit(MetricUnits.NONE)
                                       .build());
        metricRegistry.counter("dcounter").inc();
        assertThat(metricRegistry.counter("dcounter").getCount(), is(1L));
    }

    @Test
    void testGlobalCountersSuccess(@Default MetricsBean bean,
                                   @Default InvocationsTotal it) {
        bean.retryOne(5);

        Counter total = it.get(
                getMethodTag(bean, "retryOne"),
                VALUE_RETURNED.get(),
                NOT_DEFINED.get());
        assertThat(total.getCount(), is(1L));

        Counter failedTotal = it.get(
                getMethodTag(bean, "retryOne"),
                EXCEPTION_THROWN.get(),
                NOT_DEFINED.get());
        assertThat(failedTotal.getCount(), is(0L));
    }

    @Test
    void testGlobalCountersFailure(@Default MetricsBean bean,
                                   @Default InvocationsTotal it) {
        try {
            bean.retryTwo(10);
        } catch (Exception e) {
            // falls through
        }

        Counter total = it.get(
                getMethodTag(bean, "retryTwo"),
                VALUE_RETURNED.get(),
                NOT_DEFINED.get());
        assertThat(total.getCount(), is(0L));

        Counter failedTotal = it.get(
                getMethodTag(bean, "retryTwo"),
                EXCEPTION_THROWN.get(),
                NOT_DEFINED.get());
        assertThat(failedTotal.getCount(), is(1L));
    }

    @Test
    void testRetryCounters(@Default MetricsBean bean,
                           @Default RetryRetriesTotal rrt,
                           @Default RetryCallsTotal rct) {
        bean.retryThree(5);

        Counter retryRetriesTotal = rrt.get(
                getMethodTag(bean, "retryThree"));
        assertThat(retryRetriesTotal.getCount(), is(5L));

        Counter retryCallsTotal = rct.get(
                getMethodTag(bean, "retryThree"),
                RetryRetried.FALSE.get(),
                RetryResult.VALUE_RETURNED.get());
        assertThat(retryCallsTotal.getCount(), is(0L));

        retryCallsTotal = rct.get(
                getMethodTag(bean, "retryThree"),
                RetryRetried.TRUE.get(),
                RetryResult.VALUE_RETURNED.get());
        assertThat(retryCallsTotal.getCount(), is(1L));

        retryCallsTotal = rct.get(
                getMethodTag(bean, "retryThree"),
                RetryRetried.TRUE.get(),
                RetryResult.MAX_RETRIES_REACHED.get());
        assertThat(retryCallsTotal.getCount(), is(0L));
    }

    @Test
    void testRetryCountersFailure(@Default MetricsBean bean,
                                  @Default RetryRetriesTotal rrt,
                                  @Default RetryCallsTotal rct) {
        try {
            bean.retryFour(10);
        } catch (Exception e) {
            // falls through
        }

        Counter retryRetriesTotal = rrt.get(
                getMethodTag(bean, "retryFour"));
        assertThat(retryRetriesTotal.getCount(), is(5L));

        Counter retryCallsTotal = rct.get(
                getMethodTag(bean, "retryFour"),
                RetryRetried.FALSE.get(),
                RetryResult.VALUE_RETURNED.get());
        assertThat(retryCallsTotal.getCount(), is(0L));

        retryCallsTotal = rct.get(
                getMethodTag(bean, "retryFour"),
                RetryRetried.TRUE.get(),
                RetryResult.VALUE_RETURNED.get());
        assertThat(retryCallsTotal.getCount(), is(0L));

        retryCallsTotal = rct.get(
                getMethodTag(bean, "retryFour"),
                RetryRetried.TRUE.get(),
                RetryResult.MAX_RETRIES_REACHED.get());
        assertThat(retryCallsTotal.getCount(), is(1L));
    }

    @Test
    void testRetryCountersSuccess(@Default RetryRetriesTotal rrt,
                                  @Default RetryCallsTotal rct,
                                  @Default MetricsBean bean) {
        bean.retryFive(0);

        Counter retryRetriesTotal = rrt.get(
                getMethodTag(bean, "retryFive"));
        assertThat(retryRetriesTotal.getCount(), is(0L));

        Counter retryCallsTotal = rct.get(
                getMethodTag(bean, "retryFive"),
                RetryRetried.FALSE.get(),
                RetryResult.VALUE_RETURNED.get());
        assertThat(retryCallsTotal.getCount(), is(1L));

        retryCallsTotal = rct.get(
                getMethodTag(bean, "retryFive"),
                RetryRetried.TRUE.get(),
                RetryResult.VALUE_RETURNED.get());
        assertThat(retryCallsTotal.getCount(), is(0L));

        retryCallsTotal = rct.get(
                getMethodTag(bean, "retryFive"),
                RetryRetried.TRUE.get(),
                RetryResult.MAX_RETRIES_REACHED.get());
        assertThat(retryCallsTotal.getCount(), is(0L));
    }

    @Test
    void testTimeoutSuccess(@Default TimeoutCallsTotal tct,
                            @Default TimeoutExecutionDuration ted,
                            @Default MetricsBean bean) throws Exception {
        bean.noTimeout();

        Counter timeoutCallsTotal = tct.get(
                getMethodTag(bean, "noTimeout"),
                TimeoutTimedOut.TRUE.get());
        assertThat(timeoutCallsTotal.getCount(), is(0L));

        timeoutCallsTotal = tct.get(
                getMethodTag(bean, "noTimeout"),
                TimeoutTimedOut.FALSE.get());
        assertThat(timeoutCallsTotal.getCount(), is(1L));

        Histogram timeoutExecutionDuration = ted.get(
                getMethodTag(bean, "noTimeout"));
        assertThat(timeoutExecutionDuration.getCount(), is(1L));
    }

    @Test
    void testTimeoutFailure(@Default TimeoutExecutionDuration ted,
                            @Default TimeoutCallsTotal tct,
                            @Default MetricsBean bean) {
        try {
            bean.forceTimeout();
        } catch (Exception e) {
            // falls through
        }

        Counter timeoutCallsTotal = tct.get(
                getMethodTag(bean, "forceTimeout"),
                TimeoutTimedOut.TRUE.get());
        assertThat(timeoutCallsTotal.getCount(), is(1L));

        timeoutCallsTotal = tct.get(
                getMethodTag(bean, "forceTimeout"),
                TimeoutTimedOut.FALSE.get());
        assertThat(timeoutCallsTotal.getCount(), is(0L));

        Histogram timeoutExecutionDuration = ted.get(
                getMethodTag(bean, "forceTimeout"));
        assertThat(timeoutExecutionDuration.getCount(), is(1L));
    }

    @Test
    void testBreakerTrip(@Default MetricsBean bean,
                         @Default CircuitBreakerOpenedTotal cbot,
                         @Default CircuitBreakerCallsTotal cbct) {
        for (int i = 0; i < CircuitBreakerBean.REQUEST_VOLUME_THRESHOLD; i++) {
            assertThrows(RuntimeException.class, () -> bean.exerciseBreaker(false));
        }

        assertThrows(CircuitBreakerOpenException.class, () -> bean.exerciseBreaker(false));

        Counter circuitBreakerOpenedTotal = cbot.get(
                getMethodTag(bean, "exerciseBreaker"));
        assertThat(circuitBreakerOpenedTotal.getCount(), is(1L));

        Counter circuitBreakerCallsTotal = cbct.get(
                getMethodTag(bean, "exerciseBreaker"),
                CircuitBreakerResult.SUCCESS.get());
        assertThat(circuitBreakerCallsTotal.getCount(), is(0L));

        circuitBreakerCallsTotal = cbct.get(
                getMethodTag(bean, "exerciseBreaker"),
                CircuitBreakerResult.FAILURE.get());
        assertThat(circuitBreakerCallsTotal.getCount(), is((long) CircuitBreakerBean.REQUEST_VOLUME_THRESHOLD));

        circuitBreakerCallsTotal = cbct.get(
                getMethodTag(bean, "exerciseBreaker"),
                CircuitBreakerResult.CIRCUIT_BREAKER_OPEN.get());
        assertThat(circuitBreakerCallsTotal.getCount(), is(1L));
    }

    @Test
    void testBreakerGauges(@Default MetricsBean bean,
                           @Default CircuitBreakerStateTotal cbst) {
        Gauge<Long> closedStateTotal = null;
        Gauge<Long> openStateTotal = null;
        Gauge<Long> halfOpenStateTotal = null;

        for (int i = 0; i < CircuitBreakerBean.REQUEST_VOLUME_THRESHOLD - 1; i++) {
            assertThrows(RuntimeException.class, () -> bean.exerciseGauges(false));

            closedStateTotal = cbst.get(
                    getMethodTag(bean, "exerciseGauges"),
                    CircuitBreakerState.CLOSED.get());
            assertThat(closedStateTotal.getValue(), is(not(0L)));

            openStateTotal = cbst.get(
                    getMethodTag(bean, "exerciseGauges"),
                    CircuitBreakerState.OPEN.get());
            assertThat(openStateTotal.getValue(), is(0L));

            halfOpenStateTotal = cbst.get(
                    getMethodTag(bean, "exerciseGauges"),
                    CircuitBreakerState.HALF_OPEN.get());
            assertThat(halfOpenStateTotal.getValue(), is(0L));
        }
        assertThrows(RuntimeException.class, () -> bean.exerciseGauges(false));
        assertThrows(CircuitBreakerOpenException.class, () -> bean.exerciseGauges(false));

        assertThat(closedStateTotal.getValue(), is(not(0L)));
        assertThat(openStateTotal.getValue(), is(not(0L)));
        assertThat(halfOpenStateTotal.getValue(), is(0L));
    }

    @Test
    void testBreakerExceptionCounters(@Default MetricsBean bean,
                                      @Default CircuitBreakerCallsTotal cbct) throws Exception {
        Counter successCallsTotal = cbct.get(
                getMethodTag(bean, "exerciseBreakerException"),
                CircuitBreakerResult.SUCCESS.get());

        Counter failureCallsTotal = cbct.get(
                getMethodTag(bean, "exerciseBreakerException"),
                CircuitBreakerResult.FAILURE.get());

        Counter circuitBreakerOpenTotal = cbct.get(
                getMethodTag(bean, "exerciseBreakerException"),
                CircuitBreakerResult.CIRCUIT_BREAKER_OPEN.get());

        // First failure
        assertThrows(MetricsBean.TestException.class, () -> bean.exerciseBreakerException(false));  // failure
        assertThat(successCallsTotal.getCount(), is(0L));
        assertThat(failureCallsTotal.getCount(), is(1L));
        assertThat(circuitBreakerOpenTotal.getCount(), is(0L));

        // Second failure
        assertThrows(MetricsBean.TestException.class, () -> bean.exerciseBreakerException(false));  // failure
        assertThat(successCallsTotal.getCount(), is(0L));
        assertThat(failureCallsTotal.getCount(), is(2L));
        assertThat(circuitBreakerOpenTotal.getCount(), is(0L));

        assertThrows(Exception.class, () -> bean.exerciseBreakerException(true));  // failure
        assertThat(successCallsTotal.getCount(), is(0L));
        assertThat(failureCallsTotal.getCount(), is(2L));
        assertThat(circuitBreakerOpenTotal.getCount(), is(1L));

        // Sleep longer than circuit breaker delay
        Thread.sleep(1500);

        // Following calls should succeed
        for (int i = 0; i < 2; i++) {
            try {
                bean.exerciseBreakerException(true);    // success
            } catch (RuntimeException e) {
                // expected
            }
        }
        assertThat(successCallsTotal.getCount(), is(2L));
        assertThat(failureCallsTotal.getCount(), is(2L));
        assertThat(circuitBreakerOpenTotal.getCount(), is(1L));

        try {
            bean.exerciseBreakerException(true);    // success
        } catch (RuntimeException e) {
            // expected
        }
        assertThat(successCallsTotal.getCount(), is(3L));
        assertThat(failureCallsTotal.getCount(), is(2L));
        assertThat(circuitBreakerOpenTotal.getCount(), is(1L));
    }

    @Test
    void testFallbackMetrics(@Default MetricsBean bean,
                             @Default InvocationsTotal it) {
        Counter fallbackApplied = it.get(
                getMethodTag(bean, "fallback"),
                InvocationResult.VALUE_RETURNED.get(),
                InvocationFallback.APPLIED.get());
        Counter fallbackNotApplied = it.get(
                getMethodTag(bean, "fallback"),
                InvocationResult.VALUE_RETURNED.get(),
                InvocationFallback.NOT_APPLIED.get());
        Counter fallbackNotDefined = it.get(
                getMethodTag(bean, "fallback"),
                InvocationResult.VALUE_RETURNED.get(),
                InvocationFallback.NOT_DEFINED.get());

        assertThat(fallbackApplied.getCount(), is(0L));
        assertThat(fallbackNotApplied.getCount(), is(0L));
        assertThat(fallbackNotDefined.getCount(), is(0L));

        bean.fallback();

        assertThat(fallbackApplied.getCount(), is(1L));
        assertThat(fallbackNotApplied.getCount(), is(0L));
        assertThat(fallbackNotDefined.getCount(), is(0L));
    }

    @Test
    void testBulkheadMetrics(@Default MetricsBean bean,
                             @Default BulkheadCallsTotal bct,
                             @Default BulkheadRunningDuration brd,
                             @Default BulkheadExecutionsRunning ber,
                             @Default BulkheadExecutionsWaiting bew,
                             @Default BulkheadWaitingDuration bwd) {
        CompletableFuture<String>[] calls = getAsyncConcurrentCalls(
                () -> bean.concurrent(200), BulkheadBean.TOTAL_CALLS);
        waitFor(calls);

        Gauge<Long> executionsRunning = ber.get(
                getMethodTag(bean, "concurrent"));
        assertThat(executionsRunning.getValue(), is(0L));

        Gauge<Long> executionsWaiting = bew.get(
                getMethodTag(bean, "concurrent"));
        assertThat(executionsWaiting.getValue(), is(0L));

        Counter acceptedCallsTotal = bct.get(
                getMethodTag(bean, "concurrent"),
                BulkheadResult.ACCEPTED.get());
        assertThat(acceptedCallsTotal.getCount(), is((long) BulkheadBean.TOTAL_CALLS));

        Counter rejectedCallsTotal = bct.get(
                getMethodTag(bean, "concurrent"),
                BulkheadResult.REJECTED.get());
        assertThat(rejectedCallsTotal.getCount(), is(0L));

        Histogram runningDuration = brd.get(
                getMethodTag(bean, "concurrent"));
        assertThat(runningDuration.getCount(), is(greaterThan(0L)));

        Histogram awaitingDuration = bwd.get(
                getMethodTag(bean, "concurrent"));
        assertThat(awaitingDuration.getCount(), is(greaterThan(0L)));
    }

    @Test
    void testBulkheadMetricsAsync(@Default MetricsBean bean,
                                  @Default BulkheadExecutionsRunning ber,
                                  @Default BulkheadExecutionsWaiting bew,
                                  @Default BulkheadCallsTotal bct,
                                  @Default BulkheadRunningDuration brd,
                                  @Default BulkheadWaitingDuration bwd) throws Exception {
        CompletableFuture<String>[] calls = getConcurrentCalls(
                () -> {
                    try {
                        return bean.concurrentAsync(200).get();
                    } catch (Exception e) {
                        return "failure";
                    }
                }, BulkheadBean.TOTAL_CALLS);
        CompletableFuture.allOf(calls).get();

        Gauge<Long> executionsRunning = ber.get(
                getMethodTag(bean, "concurrentAsync"));
        assertThat(executionsRunning.getValue(), is(0L));

        Gauge<Long> executionsWaiting = bew.get(
                getMethodTag(bean, "concurrentAsync"));
        assertThat(executionsWaiting.getValue(), is(0L));

        Counter acceptedCallsTotal = bct.get(
                getMethodTag(bean, "concurrentAsync"),
                BulkheadResult.ACCEPTED.get());
        assertThat(acceptedCallsTotal.getCount(), is((long) BulkheadBean.TOTAL_CALLS));

        Counter rejectedCallsTotal = bct.get(
                getMethodTag(bean, "concurrentAsync"),
                BulkheadResult.REJECTED.get());
        assertThat(rejectedCallsTotal.getCount(), is(0L));

        Histogram runningDuration = brd.get(
                getMethodTag(bean, "concurrentAsync"));
        assertThat(runningDuration.getCount(), is(greaterThan(0L)));

        Histogram awaitingDuration = bwd.get(
                getMethodTag(bean, "concurrentAsync"));
        assertThat(awaitingDuration.getCount(), is(greaterThan(0L)));
    }
}
