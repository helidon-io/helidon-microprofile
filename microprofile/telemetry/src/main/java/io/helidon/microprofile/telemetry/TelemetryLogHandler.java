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
package io.helidon.microprofile.telemetry;

import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;

/**
 * Bridges JUL records without forwarding OpenTelemetry's own exporter diagnostics back to its SDK.
 */
final class TelemetryLogHandler extends Handler {
    private static final AttributeKey<String> LOGGER_NAME = AttributeKey.stringKey("log.logger");

    // Only bootstrap Boolean values are retained, never a deployment, SDK, handler, or record.
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);

    private final TelemetrySdk sdk;
    private final Logger logger;
    private final SimpleFormatter formatter = new SimpleFormatter();

    TelemetryLogHandler(TelemetrySdk sdk) {
        this.sdk = sdk;
        this.logger = sdk.telemetry().getLogsBridge().loggerBuilder("io.helidon.microprofile.telemetry").build();
        setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)
                || (record.getLoggerName() != null && record.getLoggerName().startsWith("io.opentelemetry"))) {
            return;
        }
        Severity severity = severity(record.getLevel());
        if (!logger.isEnabled(severity) || PUBLISHING.get()) {
            return;
        }
        var lease = sdk.acquire();
        if (lease == null) {
            return;
        }
        PUBLISHING.set(true);
        try {
            var builder = logger.logRecordBuilder()
                    .setTimestamp(record.getInstant())
                    .setObservedTimestamp(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                    .setSeverity(severity)
                    .setSeverityText(record.getLevel().getName())
                    .setBody(formatter.formatMessage(record));
            if (record.getLoggerName() != null) {
                builder.setAttribute(LOGGER_NAME, record.getLoggerName());
            }
            if (record.getThrown() != null) {
                builder.setException(record.getThrown());
            }
            builder.emit();
        } finally {
            PUBLISHING.set(false);
            lease.close();
        }
    }

    @Override
    public void flush() {
        // The SDK owns flushing and export scheduling.
    }

    @Override
    public void close() {
        // The CDI owner detaches this handler before shutting down the SDK.
    }

    private static Severity severity(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return Severity.ERROR;
        }
        if (value >= Level.WARNING.intValue()) {
            return Severity.WARN;
        }
        if (value >= Level.INFO.intValue()) {
            return Severity.INFO;
        }
        return value >= Level.FINE.intValue() ? Severity.DEBUG : Severity.TRACE;
    }
}
