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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonCdiShutdownHookTest {
    @TempDir
    private Path directory;

    private Path marker;
    private Path output;
    private Process process;

    @BeforeEach
    void setUp() {
        this.marker = this.directory.resolve("destroyed.txt");
        this.output = this.directory.resolve("output.txt");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.process != null && this.process.isAlive()) {
            this.process.destroyForcibly();
            assertThat(this.process.waitFor(10, SECONDS), is(true));
        }
    }

    @Test
    void testEnabledShutdownHook() throws Exception {
        startApplication("true");
        assertShutdownHook(true);
    }

    @Test
    void testDisabledShutdownHook() throws Exception {
        startApplication("false");
        assertShutdownHook(false);
    }

    @Test
    void testDefaultShutdownHook() throws Exception {
        startApplication("default");
        assertShutdownHook(true);
    }

    private void startApplication(String value) throws IOException {
        // Weld shares a shutdown hook across containers, so each case needs its own JVM.
        this.process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                          "-cp", System.getProperty("java.class.path"),
                                          Application.class.getName(), this.marker.toString(), value)
                .redirectErrorStream(true)
                .redirectOutput(this.output.toFile())
                .start();
    }

    private void assertShutdownHook(boolean expectedDestruction) throws Exception {
        boolean exited = this.process.waitFor(60, SECONDS);
        String log = Files.readString(this.output);
        assertThat(log, exited, is(true));
        assertThat(log, this.process.exitValue(), is(0));
        assertThat(log, containsString("STARTED"));
        assertThat(log, Files.exists(this.marker), is(expectedDestruction));
        if (expectedDestruction) {
            assertThat(Files.readString(this.marker), is("destroyed"));
        }
    }

    public static class Application {
        @SuppressWarnings("resource")
        public static void main(String[] args) {
            Map<String, String> properties = "default".equals(args[1])
                    ? Map.of()
                    : Map.of("cdi.org.jboss.weld.se.shutdownHook", args[1]);
            Config config = ConfigProviderResolver.instance().getBuilder()
                    .withSources(MpConfigSources.create(properties))
                    .build();
            SeContainer container = addCdiProperties(SeContainerInitializer.newInstance()
                                                            .disableDiscovery()
                                                            .addBeanClasses(ShutdownBean.class), config)
                    .initialize();
            container.select(ShutdownBean.class).get().marker(Path.of(args[0]));
            // Do not close the container: destruction must come from Weld's JVM shutdown hook.
            System.out.println("STARTED");
        }
    }

    @ApplicationScoped
    static class ShutdownBean {
        private Path marker;

        void marker(Path marker) {
            this.marker = marker;
        }

        @PreDestroy
        void destroy() {
            try {
                Files.writeString(this.marker, "destroyed");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
