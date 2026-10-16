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

import io.helidon.microprofile.cdi.ServiceRegistryExtension;
import io.helidon.service.registry.GlobalServiceRegistry;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.se.SeContainer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonTestContainerTest {
    private ConfigProviderResolver configResolver;
    private ClassLoader classLoader;
    private Config originalConfig;
    private HelidonTestScope scope;
    private HelidonTestContainer container;
    private TestExtension extension;
    private boolean failStartup;

    @BeforeEach
    void setUp() {
        assertThat(GlobalServiceRegistry.configured(), is(false));
        this.configResolver = ConfigProviderResolver.instance();
        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.originalConfig = this.configResolver.getConfig(this.classLoader);
        this.scope = HelidonTestScope.ofContainer();
        this.container = new HelidonTestContainer(HelidonTestInfo.classInfo(TestBean.class), this.scope, (info, testScope) -> {
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

        HelidonTestContainer.InitializationFailed subsequentFailure =
                assertThrows(HelidonTestContainer.InitializationFailed.class,
                             () -> this.container.resolveInstance(SeContainer.class));
        assertThat(subsequentFailure.getCause(), sameInstance(failure));
        assertThat(this.extension.startups, is(1));
    }

    @Test
    void successfulInitializationKeepsRegistryUntilClose() {
        SeContainer started = this.container.resolveInstance(SeContainer.class);

        assertThat(started.isRunning(), is(true));
        assertThat(this.container.resolveInstance(SeContainer.class), sameInstance(started));
        assertThat(this.extension.startups, is(1));
        assertThat(this.container.initFailed(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(true));

        this.container.close();

        assertThat(started.isRunning(), is(false));
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    @DisableDiscovery
    @AddExtension(ServiceRegistryExtension.class)
    public static class TestBean {
    }

    static class TestExtension extends HelidonTestExtension {
        private final IllegalStateException failure = new IllegalStateException("Deliberate startup failure");
        private final boolean failStartup;
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
    }
}
