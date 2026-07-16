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
package io.helidon.microprofile.messaging.connectors.wls.jms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertSame;

class ThinClientClassLoaderTest {

    @Test
    void usesDefiningClassLoaderWhenContextClassLoaderIsNull(@TempDir Path tempDir) throws IOException, ClassNotFoundException {
        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        Path thinJar = Files.createFile(tempDir.resolve("wlthint3client.jar"));
        ThinClientClassLoader.setThinJarLocation(thinJar.toString());
        thread.setContextClassLoader(null);
        try (ThinClientClassLoader classLoader = new ThinClientClassLoader()) {
            assertSame(IsolatedContextFactory.class,
                       classLoader.loadClass(IsolatedContextFactory.class.getName()));
        } finally {
            thread.setContextClassLoader(originalClassLoader);
            ThinClientClassLoader.setThinJarLocation("wlthint3client.jar");
        }
    }
}
