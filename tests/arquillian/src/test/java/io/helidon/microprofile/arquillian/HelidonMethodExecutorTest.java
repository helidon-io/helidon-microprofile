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

package io.helidon.microprofile.arquillian;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.control.RequestContextController;
import org.jboss.arquillian.test.spi.TestMethodExecutor;
import org.jboss.arquillian.test.spi.TestResult;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class HelidonMethodExecutorTest {

    @Test
    public void testClearsInterruptedStatusBeforeInvocation() throws Exception {
        HelidonMethodExecutor executor = newExecutor();
        AtomicBoolean interruptedDuringInvocation = new AtomicBoolean(true);

        Thread.currentThread().interrupt();
        TestResult result = executor.invoke(new StubTestMethodExecutor(
                DummyTest.class.getDeclaredMethod("testMethod"),
                ignored -> interruptedDuringInvocation.set(Thread.currentThread().isInterrupted())));

        assertThat(result.getThrowable(), is(nullValue()));
        assertThat(interruptedDuringInvocation.get(), is(false));
        assertThat(Thread.currentThread().isInterrupted(), is(false));
    }

    @Test
    public void testClearsInterruptedStatusAfterInvocation() throws Exception {
        HelidonMethodExecutor executor = newExecutor();

        TestResult result = executor.invoke(new StubTestMethodExecutor(
                DummyTest.class.getDeclaredMethod("testMethod"),
                ignored -> Thread.currentThread().interrupt()));

        assertThat(result.getThrowable(), is(nullValue()));
        assertThat(Thread.currentThread().isInterrupted(), is(false));
    }

    private static HelidonMethodExecutor newExecutor() throws Exception {
        HelidonMethodExecutor executor = new HelidonMethodExecutor();
        Field enricherField = HelidonMethodExecutor.class.getDeclaredField("enricher");
        enricherField.setAccessible(true);
        enricherField.set(executor, new TestEnricher());
        return executor;
    }

    private static final class DummyTest {
        @SuppressWarnings("unused")
        void testMethod() {
        }
    }

    private static final class TestEnricher extends HelidonContainerExtension.HelidonCDIInjectionEnricher {

        private static final Object[] EMPTY = new Object[0];
        private final RequestContextController controller = new RequestContextController() {
            @Override
            public boolean activate() {
                return true;
            }

            @Override
            public void deactivate() {
            }
        };

        @Override
        public RequestContextController getRequestContextController() {
            return controller;
        }

        @Override
        public void enrich(Object testCase) {
        }

        @Override
        public Object[] resolve(Method method) {
            return EMPTY;
        }
    }

    private static final class StubTestMethodExecutor implements TestMethodExecutor {
        private final DummyTest instance = new DummyTest();
        private final Method method;
        private final ThrowingInvocation invocation;

        private StubTestMethodExecutor(Method method, ThrowingInvocation invocation) {
            this.method = method;
            this.invocation = invocation;
        }

        @Override
        public String getMethodName() {
            return method.getName();
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object getInstance() {
            return instance;
        }

        @Override
        public void invoke(Object... args) throws Throwable {
            invocation.invoke(args);
        }
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void invoke(Object[] args) throws Throwable;
    }
}
