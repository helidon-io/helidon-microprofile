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
package io.helidon.microprofile.tests.testing.junit5;

import java.util.ArrayList;
import java.util.List;

import io.helidon.microprofile.cdi.BuildTimeEnd;
import io.helidon.microprofile.cdi.BuildTimeStart;
import io.helidon.microprofile.cdi.CdiExtension;
import io.helidon.microprofile.cdi.RuntimeStart;
import io.helidon.microprofile.testing.AddExtension;
import io.helidon.microprofile.testing.DisableDiscovery;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

@HelidonTest(resetPerTest = true)
@AddExtension(TestLifecycleEvents.RecordingExtension.class)
class TestLifecycleEvents {
    @Test
    void testDiscoveryEnabled(SeContainer container) {
        assertEvents(container);
    }

    @Test
    @DisableDiscovery
    void testDiscoveryDisabled(SeContainer container) {
        assertEvents(container);
    }

    @Test
    @AddExtension(CdiExtension.class)
    void testExplicitExtensionWithDiscovery(SeContainer container) {
        assertEvents(container);
    }

    @Test
    @DisableDiscovery
    @AddExtension(CdiExtension.class)
    void testExplicitExtensionWithoutDiscovery(SeContainer container) {
        assertEvents(container);
    }

    private static void assertEvents(SeContainer container) {
        RecordingExtension extension = container.getBeanManager().getExtension(RecordingExtension.class);
        assertThat(extension.events, contains("build start", "build end", "runtime start", "initialized"));
    }

    public static class RecordingExtension implements Extension {
        private final List<String> events = new ArrayList<>();

        void buildTimeStart(@Observes @BuildTimeStart String event) {
            events.add("build start");
        }

        void buildTimeEnd(@Observes @BuildTimeEnd String event) {
            events.add("build end");
        }

        void runtimeStart(@Observes @RuntimeStart Config config) {
            events.add("runtime start");
        }

        void initialized(@Observes @Initialized(ApplicationScoped.class) Object event) {
            events.add("initialized");
        }
    }
}
