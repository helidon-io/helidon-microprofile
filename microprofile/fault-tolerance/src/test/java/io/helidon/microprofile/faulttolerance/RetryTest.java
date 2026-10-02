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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.helidon.microprofile.testing.AddBean;

import jakarta.enterprise.inject.Default;
import jakarta.inject.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Test cases for @Retry.
 */
@AddBean(RetryBean.class)
@AddBean(SyntheticRetryBean.class)
class RetryTest extends FaultToleranceTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryBean(BeanKind kind,
                       @Default RetryBean managed,
                       @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        assertThat(bean.getInvocations(), is(0));
        bean.retry();
        assertThat(bean.getInvocations(), is(3));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryBeanFallback(BeanKind kind,
                               @Default RetryBean managed,
                               @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        assertThat(bean.getInvocations(), is(0));
        String value = bean.retryWithFallback();
        assertThat(bean.getInvocations(), is(2));
        assertThat(value, is("fallback"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryAsync(BeanKind kind,
                        @Default RetryBean managed,
                        @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) throws Exception {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        CompletableFuture<String> future = bean.retryAsync();
        future.get();
        assertThat(bean.getInvocations(), is(3));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryWithDelayAndJitter(BeanKind kind,
                                     @Default RetryBean managed,
                                     @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        long millis = System.currentTimeMillis();
        bean.retryWithDelayAndJitter();
        assertThat(System.currentTimeMillis() - millis, greaterThan(200L));
    }

    /**
     * Inspired by a TCK test which makes sure failed executions propagate correctly.
     *
     * @param kind the kind of bean to invoke
     * @param managed the managed bean
     * @param synthetic the synthetic bean
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryWithException(BeanKind kind,
                                @Default RetryBean managed,
                                @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        CompletionStage<String> future = bean.retryWithException();
        assertCompleteExceptionally(future.toCompletableFuture(), IOException.class, "Simulated error");
        assertThat(bean.getInvocations(), is(3));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryCompletionStageWithEventualSuccess(BeanKind kind,
                                                     @Default RetryBean managed,
                                                     @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        assertCompleteOk(bean.retryWithUltimateSuccess(), "success");
        assertThat(bean.getInvocations(), is(3));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BeanKind.class)
    void testRetryWithCustomRuntimeException(BeanKind kind,
                                             @Default RetryBean managed,
                                             @Named("SyntheticRetryBean") SyntheticRetryBean synthetic) {
        RetryBean bean = kind == BeanKind.MANAGED ? managed : synthetic;
        bean.reset();
        assertThat(bean.getInvocations(), is(0));
        assertCompleteOk(bean.retryOnCustomRuntimeException(), "success");
        assertThat(bean.getInvocations(), is(3));
    }

    enum BeanKind {
        MANAGED, SYNTHETIC
    }
}
