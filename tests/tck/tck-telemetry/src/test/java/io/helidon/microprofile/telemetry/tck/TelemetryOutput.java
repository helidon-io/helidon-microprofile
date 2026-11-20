/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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

package io.helidon.microprofile.telemetry.tck;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

import org.testng.ISuite;
import org.testng.ISuiteListener;

/**
 * Captures both SDK console exporters and JUL exporters in the file the unmodified TCK reads.
 */
public class TelemetryOutput implements ISuiteListener {
    private final ReentrantLock lock = new ReentrantLock();
    private PrintStream stdout;
    private PrintStream stderr;
    private OutputStream output;

    @Override
    public void onStart(ISuite suite) {
        stdout = System.out;
        stderr = System.err;
        try {
            output = Files.newOutputStream(Path.of(System.getProperty("mptelemetry.tck.log.file.path")));
            System.setOut(new PrintStream(new Tee(stdout), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(stderr), true, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onFinish(ISuite suite) {
        System.out.flush();
        System.err.flush();
        System.setOut(stdout);
        System.setErr(stderr);
        try {
            output.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final class Tee extends OutputStream {
        private final PrintStream delegate;

        private Tee(PrintStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            lock.lock();
            try {
                delegate.write(bytes, offset, length);
                output.write(bytes, offset, length);
                output.flush();
            } finally {
                lock.unlock();
            }
        }
    }
}
