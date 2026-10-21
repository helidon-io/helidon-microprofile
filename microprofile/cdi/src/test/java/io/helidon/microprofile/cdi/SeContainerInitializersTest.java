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

package io.helidon.microprofile.cdi;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.microprofile.cdi.SeContainerInitializers.initialize;
import static io.helidon.microprofile.cdi.SeContainerInitializers.registerCleanupAction;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeContainerInitializersTest {
    private List<TestInitializer> initializers;
    private List<String> cleanupCalls;

    @BeforeEach
    void setUp() {
        this.initializers = new ArrayList<>();
        this.cleanupCalls = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        for (TestInitializer initializer : this.initializers.reversed()) {
            if (initializer.container != null && initializer.container.isRunning()) {
                initializer.container.close();
            }
        }
    }

    @Test
    void successfulInitializationReturnsOriginalContainerAndDiscardsActions() {
        TestInitializer initializer = initializer(() -> { });
        initializer.addExtensions(new Extension() {
            void beforeBeanDiscovery(@Observes BeforeBeanDiscovery event) {
                registerCleanupAction(() -> SeContainerInitializersTest.this.cleanupCalls.add("successful"));
            }
        });

        try (SeContainer container = initialize(initializer)) {
            assertThat(container, sameInstance(initializer.container));
            assertThat(container.isRunning(), is(true));
            assertThat(this.cleanupCalls, empty());
        }

        assertThrows(IllegalStateException.class, () -> initialize(initializer(() -> {
            throw new IllegalStateException("Subsequent initialization failed");
        })));
        assertThat(this.cleanupCalls, empty());
    }

    @Test
    void failureRunsActionsInReverseOrderAndPreservesException() {
        IllegalStateException failure = new IllegalStateException("Initialization failed");
        TestInitializer initializer = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("first"));
            registerCleanupAction(() -> this.cleanupCalls.add("second"));
            throw failure;
        });

        assertThat(assertThrows(IllegalStateException.class, () -> initialize(initializer)), sameInstance(failure));
        assertThat(this.cleanupCalls, contains("second", "first"));
    }

    @Test
    void errorAlsoRunsCleanup() {
        AssertionError failure = new AssertionError("Initialization failed");
        TestInitializer initializer = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("cleanup"));
            throw failure;
        });

        assertThat(assertThrows(AssertionError.class, () -> initialize(initializer)), sameInstance(failure));
        assertThat(this.cleanupCalls, contains("cleanup"));
    }

    @Test
    void cleanupFailuresAreSuppressedWithoutSkippingOtherActions() {
        IllegalStateException failure = new IllegalStateException("Initialization failed");
        IllegalStateException cleanupException = new IllegalStateException("Cleanup failed");
        AssertionError cleanupError = new AssertionError("Cleanup failed");
        TestInitializer initializer = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("first"));
            registerCleanupAction(() -> {
                this.cleanupCalls.add("second");
                throw cleanupException;
            });
            registerCleanupAction(() -> {
                this.cleanupCalls.add("third");
                throw cleanupError;
            });
            throw failure;
        });

        assertThat(assertThrows(IllegalStateException.class, () -> initialize(initializer)), sameInstance(failure));
        assertThat(this.cleanupCalls, contains("third", "second", "first"));
        assertThat(failure.getSuppressed(), arrayContaining(cleanupError, cleanupException));
    }

    @Test
    void cleanupRethrowingOriginalFailureDoesNotPreventOtherCleanup() {
        IllegalStateException failure = new IllegalStateException("Initialization failed");
        TestInitializer initializer = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("cleanup"));
            registerCleanupAction(() -> {
                throw failure;
            });
            throw failure;
        });

        assertThat(assertThrows(IllegalStateException.class, () -> initialize(initializer)), sameInstance(failure));
        assertThat(this.cleanupCalls, contains("cleanup"));
        assertThat(failure.getSuppressed().length, is(0));
    }

    @Test
    void registrationOutsideInitializationIsIgnored() {
        registerCleanupAction(() -> this.cleanupCalls.add("outside"));
        assertThrows(IllegalStateException.class, () -> initialize(initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("inside"));
            throw new IllegalStateException("Initialization failed");
        })));
        registerCleanupAction(() -> this.cleanupCalls.add("after"));
        assertThrows(IllegalStateException.class, () -> initialize(initializer(() -> {
            throw new IllegalStateException("Subsequent initialization failed");
        })));

        assertThat(this.cleanupCalls, contains("inside"));
    }

    @Test
    void nestedFailureCleansOnlyInnerAttemptAndRestoresOuterRegistration() {
        IllegalStateException innerFailure = new IllegalStateException("Inner initialization failed");
        IllegalStateException outerFailure = new IllegalStateException("Outer initialization failed");
        TestInitializer inner = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("inner"));
            throw innerFailure;
        });
        TestInitializer outer = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("outer-before"));
            assertThat(assertThrows(IllegalStateException.class, () -> initialize(inner)), sameInstance(innerFailure));
            assertThat(this.cleanupCalls, contains("inner"));
            registerCleanupAction(() -> this.cleanupCalls.add("outer-after"));
            throw outerFailure;
        });

        assertThat(assertThrows(IllegalStateException.class, () -> initialize(outer)), sameInstance(outerFailure));
        assertThat(this.cleanupCalls, contains("inner", "outer-after", "outer-before"));
    }

    @Test
    void successfulNestedInitializationSurvivesOuterFailure() {
        TestInitializer inner = initializer(() -> registerCleanupAction(() -> this.cleanupCalls.add("inner")));
        TestInitializer outer = initializer(() -> {
            registerCleanupAction(() -> this.cleanupCalls.add("outer"));
            initialize(inner);
            throw new IllegalStateException("Outer initialization failed");
        });

        assertThrows(IllegalStateException.class, () -> initialize(outer));
        assertThat(this.cleanupCalls, contains("outer"));
        assertThat(inner.container.isRunning(), is(true));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> initialize(null));
        assertThrows(NullPointerException.class, () -> registerCleanupAction(null));
    }

    private TestInitializer initializer(Runnable initialization) {
        TestInitializer initializer = new TestInitializer(initialization);
        this.initializers.add(initializer);
        return initializer;
    }

    @Dependent
    static class TestBean {
    }

    // (Note that Weld implements SeContainerInitializer.)
    private static class TestInitializer extends Weld {
        private final Runnable initialization;
        private WeldContainer container;

        private TestInitializer(Runnable initialization) {
            super();
            this.initialization = initialization;
            disableDiscovery().addBeanClasses(TestBean.class);
        }

        @Override // Weld (SeContainerInitializer)
        public WeldContainer initialize() {
            this.initialization.run();
            this.container = super.initialize();
            return this.container;
        }
    }
}
