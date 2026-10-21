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
package io.helidon.microprofile.testing;

import java.util.List;
import java.util.Map;

import io.helidon.microprofile.cdi.ServiceRegistryExtension;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.microprofile.config.ConfigProvider.getConfig;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonTestContainerTest {
    private ConfigProviderResolver configResolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private List<Recording> originalRecordings;
    private HelidonTestScope scope;
    private HelidonTestContainer container;
    private TestExtension extension;
    private boolean failStartup;
    private boolean failExtensionCreation;

    @BeforeEach
    void setUp() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
        this.configResolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.configResolver.getConfig(this.classLoader);
        this.configResolver.registerConfig(this.configResolver.getBuilder()
                                                  .withSources(MpConfigSources.create(Map.of("cleanup.marker", "original")))
                                                  .build(),
                                          this.classLoader);
        this.originalRecordings = FlightRecorder.getFlightRecorder().getRecordings();
        TestBean.afterStops = 0;
        TestBean.markerAtStop = null;
        TestBean.failPreparation = false;
        TestBean.failAfterStop = false;
        this.scope = HelidonTestScope.ofContainer();
        HelidonTestInfo<?> testInfo = HelidonTestInfo.classInfo(TestBean.class, type -> new HelidonTestDescriptorBase<>(type) {
            @Override
            public boolean pinningDetection() {
                return true;
            }

            @Override
            public List<AddExtension> addExtensions() {
                if (TestBean.failPreparation) {
                    return List.of(Proxies.annotation(AddExtension.class, _ -> TestExtension.class));
                }
                return super.addExtensions();
            }
        });
        this.container = new HelidonTestContainer(testInfo, this.scope, (info, testScope) -> {
            if (this.failExtensionCreation) {
                throw new IllegalStateException("Deliberate extension creation failure");
            }
            this.extension = new TestExtension(info, testScope, this.failStartup);
            return this.extension;
        });
    }

    @AfterEach
    void tearDown() {
        try {
            this.container.close();
        } finally {
            this.scope.close();
            Config currentConfig = this.configResolver.getConfig(this.classLoader);
            if (currentConfig != this.originalConfig) {
                this.configResolver.releaseConfig(currentConfig);
            }
            this.configResolver.registerConfig(this.originalConfig, this.classLoader);
            newRecordings().forEach(Recording::close);
        }
    }

    @Test
    void startupFailureClosesOwnedRegistryAndRetainsFailure() {
        this.failStartup = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                                                    () -> this.container.resolveInstance(SeContainer.class));

        assertThat(failure, sameInstance(this.extension.failure));
        assertThat(this.extension.startups, is(1));
        assertThat(this.container.initFailed(), is(true));
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
        assertThat(TestBean.afterStops, is(1));
        assertThat(TestBean.markerAtStop, is("test"));
        assertThat(newRecordings(), is(List.of()));

        HelidonTestContainer.InitializationFailed subsequentFailure =
                assertThrows(HelidonTestContainer.InitializationFailed.class,
                             () -> this.container.resolveInstance(SeContainer.class));
        assertThat(subsequentFailure.getCause(), sameInstance(failure));
        assertThat(this.extension.startups, is(1));

        this.container.close();
        this.container.close();

        assertThat(TestBean.afterStops, is(1));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
    }

    @Test
    void successfulInitializationKeepsRegistryUntilClose() {
        SeContainer started = this.container.resolveInstance(SeContainer.class);

        assertThat(started.isRunning(), is(true));
        assertThat(this.container.resolveInstance(SeContainer.class), sameInstance(started));
        assertThat(this.extension.startups, is(1));
        assertThat(this.container.initFailed(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(true));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("test"));
        assertThat(TestBean.afterStops, is(0));
        assertThat(newRecordings().size(), is(1));

        this.container.close();

        assertThat(started.isRunning(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
        assertThat(TestBean.afterStops, is(1));
        assertThat(TestBean.markerAtStop, is("test"));
        assertThat(newRecordings(), is(List.of()));

        this.container.close();

        assertThat(TestBean.afterStops, is(1));
    }

    @Test
    void invalidExtensionRestoresConfigBeforeInitialization() {
        TestBean.failPreparation = true;

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                                       () -> this.container.resolveInstance(SeContainer.class));

        assertThat(failure.getMessage(), is(TestExtension.class.getName() + " is not public"));
        assertThat(this.extension.startups, is(0));
        assertThat(this.container.initFailed(), is(true));
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
        // Discovery has not yet registered the AfterStop method.
        assertThat(TestBean.afterStops, is(0));
        assertThat(newRecordings(), is(List.of()));
    }

    @Test
    void failingAfterStopRestoresConfigAndPreservesStartupFailure() {
        this.failStartup = true;
        TestBean.failAfterStop = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                                                    () -> this.container.resolveInstance(SeContainer.class));

        assertThat(failure, sameInstance(this.extension.failure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0].getCause().getCause(), sameInstance(TestBean.CLEANUP_FAILURE));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
        assertThat(TestBean.afterStops, is(1));
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThat(newRecordings(), is(List.of()));

        this.container.close();

        assertThat(TestBean.afterStops, is(1));
    }

    @Test
    void extensionCreationFailureClosesRecorder() {
        this.failExtensionCreation = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                                                    () -> this.container.resolveInstance(SeContainer.class));

        assertThat(failure.getMessage(), is("Deliberate extension creation failure"));
        assertThat(this.container.initFailed(), is(true));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
        assertThat(newRecordings(), is(List.of()));
    }

    @Test
    void shutdownFailureStillClosesRecorder() {
        SeContainer started = this.container.resolveInstance(SeContainer.class);
        this.extension.failShutdown = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, this.container::close);

        assertThat(failure, sameInstance(this.extension.shutdownFailure));
        assertThat(started.isRunning(), is(false));
        assertThat(this.container.closed(), is(true));
        assertThat(GlobalServiceRegistry.configured(), is(false));
        assertThat(getConfig().getValue("cleanup.marker", String.class), is("original"));
        assertThat(TestBean.afterStops, is(1));
        assertThat(newRecordings(), is(List.of()));

        this.container.close();

        assertThat(TestBean.afterStops, is(1));
    }

    private List<Recording> newRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> !this.originalRecordings.contains(recording))
                .toList();
    }

    @DisableDiscovery
    @AddExtension(ServiceRegistryExtension.class)
    @AddConfig(key = "cleanup.marker", value = "test")
    public static class TestBean {
        private static final IllegalStateException CLEANUP_FAILURE = new IllegalStateException("Deliberate cleanup failure");
        private static int afterStops;
        private static String markerAtStop;
        private static boolean failPreparation;
        private static boolean failAfterStop;

        @AfterStop
        static void afterStop() {
            afterStops++;
            markerAtStop = getConfig().getValue("cleanup.marker", String.class);
            if (failAfterStop) {
                throw CLEANUP_FAILURE;
            }
        }
    }

    static class TestExtension extends HelidonTestExtension {
        private final IllegalStateException failure = new IllegalStateException("Deliberate startup failure");
        private final IllegalStateException shutdownFailure = new IllegalStateException("Deliberate shutdown failure");
        private final boolean failStartup;
        private boolean failShutdown;
        private int startups;

        TestExtension(HelidonTestInfo<?> info, HelidonTestScope scope, boolean failStartup) {
            super(info, scope);
            this.failStartup = failStartup;
        }

        void startup(@Observes Startup event) {
            this.startups++;
            assertThat(GlobalServiceRegistry.configured(), is(true));
            if (this.failStartup) {
                throw this.failure;
            }
        }

        void shutdown(@Observes Shutdown event) {
            if (this.failShutdown) {
                throw this.shutdownFailure;
            }
        }
    }
}
