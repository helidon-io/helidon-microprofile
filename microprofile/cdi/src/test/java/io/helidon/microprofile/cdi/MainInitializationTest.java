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

import io.helidon.service.registry.GlobalServiceRegistry;

import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainInitializationTest {
    @Test
    void testClassInitializationAndShutdownDoNotStartCdi() throws ClassNotFoundException {
        // Surefire runs each test class in its own JVM, so Main has not been initialized yet.
        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));

        Class.forName(Main.class.getName());

        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));

        Main.shutdown();
        Main.shutdown();

        assertThrows(IllegalStateException.class, CDI::current);
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }
}
