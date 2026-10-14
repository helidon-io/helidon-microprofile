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
package io.helidon.microprofile.servicecommon;

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.microprofile.cdi.CdiExtension;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonRestCdiExtensionTest {

    private Config firstConfig;
    private Config secondConfig;

    @BeforeEach
    void setUp() {
        this.firstConfig = Config.just(ConfigSources.create(Map.of("test.importance", "1")));
        this.secondConfig = Config.just(ConfigSources.create(Map.of("test.importance", "2")));
    }

    @Test
    void directCallerCanSupplyConfiguration() {
        var extension = new TestExtension();
        extension.prepareRuntime(this.firstConfig);

        assertThat(extension.rootConfig(), is(sameInstance(this.firstConfig)));
        assertThat(extension.componentConfig().get("importance").asInt().get(), is(1));
    }

    @Test
    void componentConfigurationUsesLatestSuppliedRootBeforeFirstAccess() {
        var extension = new TestExtension();
        extension.prepareRuntime(this.firstConfig);
        extension.prepareRuntime(this.secondConfig);

        assertThat(extension.rootConfig(), is(sameInstance(this.secondConfig)));
        assertThat(extension.componentConfig().get("importance").asInt().get(), is(2));
    }

    @Test
    void previouslySelectedComponentKeepsItsConfiguration() {
        var extension = new TestExtension();
        extension.prepareRuntime(this.firstConfig);
        var componentConfig = extension.componentConfig();
        extension.prepareRuntime(this.secondConfig);

        assertThat(extension.rootConfig(), is(sameInstance(this.secondConfig)));
        assertThat(extension.componentConfig(), is(sameInstance(componentConfig)));
        assertThat(extension.componentConfig().get("importance").asInt().get(), is(1));
    }

    @Dependent
    static class TestBean {
    }

    static class TestExtension extends HelidonRestCdiExtension {
        TestExtension() {
            super(System.getLogger(TestExtension.class.getName()), "test");
        }
    }

    static class NonObservingExtension extends TestExtension {
        @Override
        public void prepareRuntime(Config config) {
            super.prepareRuntime(config);
        }
    }

    @Nested
    class NonObservingOverride {
        private final NonObservingExtension extension = new NonObservingExtension();
        private SeContainer container;

        @BeforeEach
        void start() {
            this.extension.prepareRuntime(firstConfig);
            this.container = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addExtensions(new CdiExtension(), this.extension)
                    .addBeanClasses(TestBean.class)
                    .initialize();
        }

        @AfterEach
        void stop() {
            if (this.container != null) {
                this.container.close();
            }
        }

        @Test
        void overridingWithoutObservesSuppressesInheritedObserver() {
            assertThat(this.extension.rootConfig(), is(sameInstance(firstConfig)));
            assertThat(this.extension.componentConfig().get("importance").asInt().get(), is(1));
        }
    }
}
