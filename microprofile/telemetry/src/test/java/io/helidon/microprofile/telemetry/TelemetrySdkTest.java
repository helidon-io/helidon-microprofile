/*
 * Copyright (c) 2024, 2026 Oracle and/or its affiliates.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import io.helidon.microprofile.config.core.MpConfig;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetrySdkTest {
    @Test
    void disabledUnlessExplicitlyEnabled() {
        var owner = owner(Map.of());
        owner.initialize();
        try {
            var lease = owner.acquire();
            if (lease != null) {
                lease.close();
            }
            assertThat("Disabled SDK admits no instrumentation", lease, nullValue());
            assertThat(owner.telemetry().getTracer("test").spanBuilder("disabled").startSpan().isRecording(), is(false));
        } finally {
            owner.close();
        }
        assertThat(owner.acquire(), nullValue());
        owner.close();
    }

    @Test
    void explicitApplicationEnablementAndResource() {
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.resource.attributes", "service.name=resource-service"));
        owner.initialize();
        try {
            assertThat(owner.runtimeEnabled(), is(false));
            var span = owner.telemetry().getTracer("test").spanBuilder("enabled").startSpan();
            assertThat(span.isRecording(), is(true));
            span.end();
            assertThat(((ReadableSpan) span).toSpanData().getResource().getAttribute(ServiceAttributes.SERVICE_NAME),
                       is("resource-service"));
        } finally {
            owner.close();
        }
    }

    @Test
    void applicationNameIsOnlyAResourceDefault() {
        assertThat(resource(Map.of("mp.application.name", "application")).getAttribute(ServiceAttributes.SERVICE_NAME),
                   is("application"));
        assertThat(resource(Map.of("mp.application.name", "application", "otel.resource.attributes", "service.name=explicit"))
                           .getAttribute(ServiceAttributes.SERVICE_NAME),
                   is("explicit"));
        assertThat(resource(Map.of("mp.application.name", "application", "otel.resource.attributes", "service.name=resource",
                                   "otel.service.name", "configured")).getAttribute(ServiceAttributes.SERVICE_NAME),
                   is("configured"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider-service", "unknown_service:java"})
    void resourceProvidersOverrideApplicationNameDefault(String serviceName) {
        // The JAR detector deliberately replaces unknown_service names; isolate the application-name fallback here.
        var resource = resource(Map.of("mp.application.name", "application", SdkTestResources.SERVICE_NAME, serviceName,
                                       "otel.java.disabled.resource.providers",
                                       "io.opentelemetry.instrumentation.resources.JarServiceNameDetector"));
        assertThat(resource.getAttribute(ServiceAttributes.SERVICE_NAME), is(serviceName));
    }

    @Test
    void explicitConfigurationOverridesResourceProvider() {
        var resource = resource(Map.of("mp.application.name", "application", SdkTestResources.SERVICE_NAME, "provider",
                                       "otel.resource.attributes", "service.name=resource"));
        assertThat(resource.getAttribute(ServiceAttributes.SERVICE_NAME), is("resource"));
    }

    @Test
    void detectsHostOsProcessAndJvmResourcesAndRetainsConfiguredAttributes() {
        var resource = resource(Map.of("otel.resource.attributes", "host.name=configured-host,service.instance.id=instance"));
        assertThat(resource.getAttribute(AttributeKey.stringKey("host.name")), is("configured-host"));
        assertThat(resource.getAttribute(AttributeKey.stringKey("os.type")), notNullValue());
        assertThat(resource.getAttribute(AttributeKey.longKey("process.pid")), is(ProcessHandle.current().pid()));
        assertThat(resource.getAttribute(AttributeKey.stringKey("process.runtime.name")), is(System.getProperty("java.runtime.name")));
        assertThat(resource.getAttribute(ServiceAttributes.SERVICE_INSTANCE_ID), is("instance"));
        var detected = resource(Map.of());
        assertThat(detected.getAttribute(AttributeKey.stringKey("host.name")), notNullValue());
        assertThat(detected.getAttribute(ServiceAttributes.SERVICE_INSTANCE_ID), notNullValue());
    }

    @Test
    void honorsDisabledResourceProviders() {
        var resource = resource(Map.of("otel.java.disabled.resource.providers",
                                       "io.opentelemetry.instrumentation.resources.HostResourceProvider,"
                                               + "io.opentelemetry.instrumentation.resources.OsResourceProvider,"
                                               + "io.opentelemetry.instrumentation.resources.ProcessResourceProvider,"
                                               + "io.opentelemetry.instrumentation.resources.ProcessRuntimeResourceProvider"));
        assertThat(resource.getAttribute(AttributeKey.stringKey("host.name")), nullValue());
        assertThat(resource.getAttribute(AttributeKey.stringKey("os.type")), nullValue());
        assertThat(resource.getAttribute(AttributeKey.longKey("process.pid")), nullValue());
        assertThat(resource.getAttribute(AttributeKey.stringKey("process.runtime.name")), nullValue());
    }

    @Test
    void honorsEnabledResourceProvidersAndDisabledAttributeKeys() {
        var resource = resource(Map.of("otel.java.enabled.resource.providers",
                                       "io.opentelemetry.instrumentation.resources.OsResourceProvider"));
        assertThat(resource.getAttribute(AttributeKey.stringKey("os.type")), notNullValue());
        assertThat(resource.getAttribute(AttributeKey.stringKey("host.name")), nullValue());
        assertThat(resource.getAttribute(AttributeKey.longKey("process.pid")), nullValue());
        var filtered = resource(Map.of("mp.application.name", "application",
                                       "otel.resource.disabled.keys", "service.name,process.pid,host.name"));
        assertThat(filtered.getAttribute(ServiceAttributes.SERVICE_NAME), nullValue());
        assertThat(filtered.getAttribute(AttributeKey.longKey("process.pid")), nullValue());
        assertThat(filtered.getAttribute(AttributeKey.stringKey("host.name")), nullValue());
        assertThat(filtered.getAttribute(AttributeKey.stringKey("os.type")), notNullValue());
    }

    @Test
    void allSignalsUseTheSameResource() {
        SdkTestExporters.LOGS.clear();
        SdkTestExporters.METRICS.clear();
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "logging",
                                 "otel.metrics.exporter", "sdk-test", "otel.logs.exporter", "sdk-test",
                                 "mp.application.name", "shared-resource"));
        owner.initialize();
        try {
            var span = owner.telemetry().getTracer("test").spanBuilder("resource").startSpan();
            span.end();
            var resource = ((ReadableSpan) span).toSpanData().getResource();
            owner.telemetry().getMeter("test").counterBuilder("resource.counter").build().add(1);
            Logger.getLogger("resource-test").log(Level.INFO, "resource-record");
            var sdk = (OpenTelemetrySdk) owner.telemetry();
            sdk.getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            sdk.getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
            var metric = SdkTestExporters.METRICS.stream()
                    .filter(data -> data.getName().equals("resource.counter")).findFirst().orElseThrow();
            var log = SdkTestExporters.LOGS.stream()
                    .filter(data -> data.getBodyValue().asString().equals("resource-record")).findFirst().orElseThrow();
            assertThat(resource.getAttribute(ServiceAttributes.SERVICE_NAME), is("shared-resource"));
            assertThat(resource.getAttribute(AttributeKey.longKey("process.pid")), is(ProcessHandle.current().pid()));
            assertThat(metric.getResource(), is(resource));
            assertThat(log.getResource(), is(resource));
        } finally {
            owner.close();
        }
    }

    @Test
    void failedStartupDetachesHandlerAndClosesAdmission() {
        int handlers = Logger.getLogger("").getHandlers().length;
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.traces.sampler", "unknown-sampler"));
        assertThrows(RuntimeException.class, owner::initialize);
        assertThat(owner.acquire(), nullValue());
        assertThat(Logger.getLogger("").getHandlers().length, is(handlers));
        owner.close();
    }

    @Test
    void interruptedDrainCanBeRetriedWithoutReopeningAdmission() throws Exception {
        var owner = owner(Map.of("otel.sdk.disabled", "false"));
        owner.initialize();
        var lease = owner.acquire();
        assertThat(lease, notNullValue());
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        Thread closer = Thread.ofVirtual().start(() -> {
            entered.countDown();
            try {
                owner.close();
            } catch (IllegalStateException _) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS), is(true));
        closer.interrupt();
        closer.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(closer.isAlive(), is(false));
        assertThat(interrupted.get(), is(true));
        assertThat(owner.acquire(), nullValue());
        lease.close();
        owner.close();
        owner.close();
    }

    @Test
    void exportsMetricsAndCorrelatedJulRecordsThroughServiceLoader() {
        SdkTestExporters.LOGS.clear();
        SdkTestExporters.METRICS.clear();
        int handlers = Logger.getLogger("").getHandlers().length;
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "none",
                                 "otel.metrics.exporter", "sdk-test", "otel.logs.exporter", "sdk-test"));
        owner.initialize();
        try {
            var span = owner.telemetry().getTracer("test").spanBuilder("log-parent").startSpan();
            try (var _ = span.makeCurrent()) {
                Logger.getLogger("application-test").log(Level.WARNING, "message {0}", "parameter");
            } finally {
                span.end();
            }
            owner.telemetry().getMeter("test").counterBuilder("test.counter").build().add(3);
            var sdk = (OpenTelemetrySdk) owner.telemetry();
            sdk.getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
            sdk.getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            var record = SdkTestExporters.LOGS.stream()
                    .filter(log -> log.getBodyValue().asString().equals("message parameter")).findFirst().orElseThrow();
            assertThat(record.getSeverity(), is(Severity.WARN));
            assertThat(record.getSpanContext().getTraceId(), is(span.getSpanContext().getTraceId()));
            var metric = SdkTestExporters.METRICS.stream()
                    .filter(data -> data.getName().equals("test.counter")).findFirst().orElseThrow();
            assertThat(metric.getLongSumData().getPoints().iterator().next().getValue(), is(3L));
        } finally {
            owner.close();
        }
        assertThat(Logger.getLogger("").getHandlers().length, is(handlers));
    }

    @Test
    void disabledLogPipelineDoesNotFormatParameters() {
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "none",
                                 "otel.metrics.exporter", "none", "otel.logs.exporter", "none"));
        owner.initialize();
        try {
            var formatted = new AtomicInteger();
            var record = new LogRecord(Level.INFO, "{0}");
            record.setParameters(new Object[] {new Object() {
                @Override
                public String toString() {
                    formatted.incrementAndGet();
                    return "parameter";
                }
            }});
            new TelemetryLogHandler(owner).publish(record);
            assertThat(formatted.get(), is(0));
        } finally {
            owner.close();
        }
    }

    @Test
    void concurrentLogRecordsKeepTheirOwnContextAndAttributes() throws Exception {
        SdkTestExporters.LOGS.clear();
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "logging",
                                 "otel.metrics.exporter", "none", "otel.logs.exporter", "sdk-test"));
        owner.initialize();
        try {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var results = IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                    var span = owner.telemetry().getTracer("test").spanBuilder("parallel-" + index).setNoParent().startSpan();
                    try (var _ = span.makeCurrent()) {
                        if (index % 2 == 0) {
                            Logger.getLogger("application-test").log(Level.WARNING, "parallel-" + index,
                                                                     new IllegalStateException("example"));
                        } else {
                            Logger.getLogger("application-test").log(Level.INFO, "parallel-" + index);
                        }
                        return span.getSpanContext().getTraceId();
                    } finally {
                        span.end();
                    }
                })).toList();
                var traceIds = new ArrayList<String>();
                for (var result : results) {
                    traceIds.add(result.get(5, TimeUnit.SECONDS));
                }
                assertThat(traceIds.stream().distinct().count(), is(8L));
                ((OpenTelemetrySdk) owner.telemetry()).getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
                for (int i = 0; i < results.size(); i++) {
                    String body = "parallel-" + i;
                    var records = SdkTestExporters.LOGS.stream()
                            .filter(record -> body.equals(record.getBodyValue().asString())).toList();
                    assertThat(records.size(), is(1));
                    var record = records.getFirst();
                    assertThat(record.getSpanContext().getTraceId(), is(traceIds.get(i)));
                    assertThat(record.getSeverity(), is(i % 2 == 0 ? Severity.WARN : Severity.INFO));
                    String exceptionType = record.getAttributes().get(AttributeKey.stringKey("exception.type"));
                    if (i % 2 == 0) {
                        assertThat(exceptionType, is(IllegalStateException.class.getName()));
                    } else {
                        assertThat(exceptionType, nullValue());
                    }
                }
            }
        } finally {
            owner.close();
        }
    }

    @Test
    void preservesCurrentJulSeverityScopeAndLoggerAttribute() {
        SdkTestExporters.LOGS.clear();
        var owner = owner(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "none",
                                 "otel.metrics.exporter", "none", "otel.logs.exporter", "sdk-test"));
        owner.initialize();
        var handler = new TelemetryLogHandler(owner);
        try {
            var levels = List.of(Level.FINEST, Level.FINER, Level.FINE, Level.CONFIG, Level.INFO, Level.WARNING, Level.SEVERE);
            // Characterization of current behavior, not the proposed finer OTel severity mapping.
            var severities = List.of(Severity.TRACE, Severity.TRACE, Severity.DEBUG, Severity.DEBUG,
                                     Severity.INFO, Severity.WARN, Severity.ERROR);
            var timestamp = Instant.parse("2026-01-02T03:04:05.123456789Z");
            for (int i = 0; i < levels.size(); i++) {
                var record = new LogRecord(levels.get(i), "baseline-severity-" + i);
                record.setLoggerName("baseline-logger-" + i);
                record.setInstant(timestamp);
                handler.publish(record);
            }
            ((OpenTelemetrySdk) owner.telemetry()).getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
            for (int i = 0; i < levels.size(); i++) {
                String body = "baseline-severity-" + i;
                var record = SdkTestExporters.LOGS.stream()
                        .filter(log -> body.equals(log.getBodyValue().asString())).findFirst().orElseThrow();
                assertThat(record.getSeverity(), is(severities.get(i)));
                assertThat(record.getSeverityText(), is(levels.get(i).getName()));
                assertThat(record.getInstrumentationScopeInfo().getName(), is("io.helidon.microprofile.telemetry"));
                assertThat(record.getAttributes().get(AttributeKey.stringKey("log.logger")), is("baseline-logger-" + i));
                long epochNanos = timestamp.getEpochSecond() * 1_000_000_000L + timestamp.getNano();
                assertThat(record.getTimestampEpochNanos(), is(epochNanos));
            }
        } finally {
            handler.close();
            owner.close();
        }
    }

    @Test
    void runtimeConfigurationOverridesApplicationConfigurationAndExportsJvmMetrics() {
        Map<String, String> runtime = Map.of("otel.sdk.disabled", "false", "otel.metrics.exporter", "sdk-test",
                                             "otel.logs.exporter", "none", "otel.traces.exporter", "none");
        Map<String, String> previous = new HashMap<>();
        runtime.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
        var owner = owner(Map.of("otel.sdk.disabled", "true", "otel.service.name", "application-only",
                                 "mp.application.name", "application-only"));
        try {
            SdkTestExporters.METRICS.clear();
            owner.initialize();
            assertThat(owner.runtimeEnabled(), is(true));
            var sdk = (OpenTelemetrySdk) owner.telemetry();
            assertThat(sdk.getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS).isSuccess(), is(true));
            assertThat(SdkTestExporters.METRICS.stream().anyMatch(data -> data.getName().equals("jvm.memory.used")), is(true));
            assertThat(SdkTestExporters.METRICS.stream()
                    .noneMatch(data -> "application-only".equals(data.getResource()
                            .getAttribute(ServiceAttributes.SERVICE_NAME))),
                       is(true));
        } finally {
            owner.close();
            runtime.keySet().forEach(key -> {
                if (previous.get(key) == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, previous.get(key));
                }
            });
        }
    }

    @Test
    void borrowedGlobalRemainsUsableAfterClose() {
        GlobalOpenTelemetry.resetForTest();
        var exporter = new TestSpanExporter();
        var borrowed = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
        GlobalOpenTelemetry.set(borrowed);
        try {
            var owner = owner(Map.of("io.helidon.telemetry.otel.use-existing-instance", "true"));
            owner.initialize();
            owner.close();
            var span = borrowed.getTracer("borrowed").spanBuilder("still-running").setNoParent().startSpan();
            assertThat(span.isRecording(), is(true));
            span.end();
            assertThat(exporter.spanData(1).getFirst().getName(), is("still-running"));
        } finally {
            GlobalOpenTelemetry.resetForTest();
            borrowed.close();
        }
    }

    @Test
    void borrowedSdkExportsJulAndRetainsItsOwnership() {
        GlobalOpenTelemetry.resetForTest();
        SdkTestExporters.LOGS.clear();
        var borrowed = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesCustomizer(_ -> Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "none",
                                                     "otel.metrics.exporter", "none", "otel.logs.exporter", "sdk-test"))
                .disableShutdownHook().build().getOpenTelemetrySdk();
        GlobalOpenTelemetry.set(borrowed);
        int handlers = Logger.getLogger("").getHandlers().length;
        var owner = owner(Map.of("otel.sdk.disabled", "false", "io.helidon.telemetry.otel.use-existing-instance", "true"));
        try {
            owner.initialize();
            Logger.getLogger("borrowed-test").info("borrowed-integration-record");
            borrowed.getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(SdkTestExporters.LOGS.stream()
                               .filter(log -> "borrowed-integration-record".equals(log.getBodyValue().asString())).count(), is(1L));
            owner.close();
            assertThat(Logger.getLogger("").getHandlers().length, is(handlers));
            borrowed.getLogsBridge().get("borrowed-test").logRecordBuilder().setBody("still-open").emit();
            borrowed.getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(SdkTestExporters.LOGS.stream()
                               .filter(log -> "still-open".equals(log.getBodyValue().asString())).count(), is(1L));
        } finally {
            owner.close();
            GlobalOpenTelemetry.resetForTest();
            borrowed.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void borrowedRuntimeRegistersOnlyIntegrationOwnedInstrumentation(boolean agent) {
        Map<String, String> runtime = Map.of("otel.sdk.disabled", "false", "otel.metrics.exporter", "sdk-test",
                                             "otel.logs.exporter", "none", "otel.traces.exporter", "none");
        Map<String, String> previous = new HashMap<>();
        runtime.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
        GlobalOpenTelemetry.resetForTest();
        var borrowed = AutoConfiguredOpenTelemetrySdk.builder().disableShutdownHook().build().getOpenTelemetrySdk();
        GlobalOpenTelemetry.set(borrowed);
        var owner = owner(Map.of("otel.sdk.disabled", "true", "io.helidon.telemetry.otel.use-existing-instance", "true",
                                 HelidonOpenTelemetry.OTEL_AGENT_PRESENT_PROPERTY,
                                 Boolean.toString(agent)));
        int handlers = Logger.getLogger("").getHandlers().length;
        try {
            SdkTestExporters.METRICS.clear();
            owner.initialize();
            assertThat(owner.runtimeEnabled(), is(true));
            borrowed.getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(SdkTestExporters.METRICS.stream().anyMatch(data -> data.getName().equals("jvm.memory.used")), is(!agent));
            assertThat(Logger.getLogger("").getHandlers().length, is(handlers + (agent ? 0 : 1)));
            owner.close();
            assertThat(Logger.getLogger("").getHandlers().length, is(handlers));
            borrowed.getMeter("borrowed").counterBuilder("still.open").build().add(1);
            borrowed.getSdkMeterProvider().forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(SdkTestExporters.METRICS.stream().anyMatch(data -> data.getName().equals("still.open")), is(true));
        } finally {
            owner.close();
            GlobalOpenTelemetry.resetForTest();
            borrowed.close();
            runtime.keySet().forEach(key -> {
                if (previous.get(key) == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, previous.get(key));
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionUsesFinalServiceLoaderCustomizedEnablement(boolean disabled) {
        var owner = owner(Map.of("otel.sdk.disabled", Boolean.toString(!disabled),
                                 "otel.helidon.test.sdk-disabled", Boolean.toString(disabled),
                                 "otel.traces.exporter", "none", "otel.metrics.exporter", "none", "otel.logs.exporter", "none"));
        owner.initialize();
        try {
            var lease = owner.acquire();
            if (lease != null) {
                lease.close();
            }
            assertThat(lease == null, is(disabled));
        } finally {
            owner.close();
        }
    }

    @Test
    void recursiveExportLoggingIsSuppressedAndGuardIsResetForLaterRecords() {
        GlobalOpenTelemetry.resetForTest();
        var exported = new AtomicInteger();
        var provider = SdkLoggerProvider.builder().addLogRecordProcessor(new LogRecordProcessor() {
            @Override
            public void onEmit(Context context, ReadWriteLogRecord record) {
                if (exported.incrementAndGet() <= 2) {
                    Logger.getLogger("recursive-exporter").info("nested-export-diagnostic");
                }
            }
        }).build();
        var borrowed = OpenTelemetrySdk.builder().setLoggerProvider(provider).build();
        GlobalOpenTelemetry.set(borrowed);
        var owner = owner(Map.of("otel.sdk.disabled", "false", "io.helidon.telemetry.otel.use-existing-instance", "true"));
        try {
            owner.initialize();
            Logger.getLogger("recursive-test").info("first-outer-record");
            Logger.getLogger("recursive-test").info("second-outer-record");
            assertThat(exported.get(), is(2));
        } finally {
            owner.close();
            GlobalOpenTelemetry.resetForTest();
            borrowed.close();
        }
    }

    @Test
    void logFormattingFailureDoesNotSuppressLaterRecordsOrRetainSdkWork() {
        var owner = TelemetryTestSupport.owner(Map.of("otel.logs.exporter", "sdk-test"));
        owner.initialize();
        SdkTestExporters.LOGS.clear();
        try {
            var handler = new TelemetryLogHandler(owner);
            var failure = new IllegalStateException("Synthetic log record failure");
            var broken = new LogRecord(Level.INFO, "broken") {
                @Override
                public Instant getInstant() {
                    throw failure;
                }
            };
            assertThrows(IllegalStateException.class, () -> handler.publish(broken));
            handler.publish(new LogRecord(Level.INFO, "after-failure"));
            ((OpenTelemetrySdk) owner.telemetry()).getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(SdkTestExporters.LOGS.size(), is(1));
            assertThat(SdkTestExporters.LOGS.getFirst().getBodyValue().asString(), is("after-failure"));
        } finally {
            owner.close();
        }
    }

    private static Resource resource(Map<String, String> properties) {
        var settings = new HashMap<>(Map.of("otel.sdk.disabled", "false", "otel.traces.exporter", "logging",
                                           "otel.metrics.exporter", "none", "otel.logs.exporter", "none"));
        settings.putAll(properties);
        var owner = owner(settings);
        owner.initialize();
        try {
            var span = owner.telemetry().getTracer("test").spanBuilder("resource").startSpan();
            span.end();
            return ((ReadableSpan) span).toSpanData().getResource();
        } finally {
            owner.close();
        }
    }

    private static TelemetrySdk owner(Map<String, String> properties) {
        ConfigSource source = new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return properties;
            }

            @Override
            public Set<String> getPropertyNames() {
                return properties.keySet();
            }

            @Override
            public String getValue(String name) {
                return properties.get(name);
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        var config = ConfigProviderResolver.instance().getBuilder().withSources(source).build();
        return new TelemetrySdk(MpConfig.toHelidonConfig(config), config);
    }
}
