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
package io.helidon.microprofile.metrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.Status;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webclient.http1.Http1ClientRequest;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.observe.metrics.MetricsObserverConfig;
import io.helidon.webserver.testing.junit5.DirectClient;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * Measures MP metrics routing and response serialization without network transport.
 * Public state and benchmark methods are required by the JMH integration contract.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Threads(1)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MpMetricsEndpointBenchmark {
    private static final String ENDPOINT = "/metrics";
    private static final String NAME_PREFIX = "benchmark.counter.";
    private static final List<String> SCOPES = List.of(MetricRegistry.APPLICATION_SCOPE,
                                                       MetricRegistry.BASE_SCOPE,
                                                       MetricRegistry.VENDOR_SCOPE);

    /**
     * Number of counter names registered in each of the three scopes.
     */
    @Param({"100", "1000"})
    public int meterNames;

    private ServiceRegistryManager manager;
    private DirectClient client;

    /**
     * Creates the registry, routes, and fixed dataset, and verifies the responses before measurement.
     */
    @Setup(Level.Trial)
    public void setUp() {
        if (meterNames < 1) {
            throw new IllegalArgumentException("meterNames must be positive: " + meterNames);
        }
        if (GlobalServiceRegistry.configured()) {
            throw new IllegalStateException("The benchmark requires an isolated service registry");
        }
        Config config = Config.just(ConfigSources.create(Map.of(
                "metrics.permit-all", "true",
                "metrics.virtual-threads.enabled", "false",
                "metrics.rest-request.enabled", "false")));
        manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                       .putContractInstance(Config.class, config)
                                                       .build());
        try {
            var services = manager.registry();
            GlobalServiceRegistry.registry(services);
            var factoryManager = services.get(RegistryFactoryManager.class);
            factoryManager.enable();
            RegistryFactory factory = factoryManager.registryFactory();
            MeterRegistry meterRegistry = services.get(MeterRegistry.class);

            // Remove built-in meters so JVM activity does not change the dataset or response size.
            List.copyOf(meterRegistry.meters()).forEach(meter -> meterRegistry.remove(meter.id()));
            for (int scopeIndex = 0; scopeIndex < SCOPES.size(); scopeIndex++) {
                MetricRegistry registry = factory.getRegistry(SCOPES.get(scopeIndex));
                for (int nameIndex = 0; nameIndex < meterNames; nameIndex++) {
                    registry.counter(NAME_PREFIX + nameIndex).inc((scopeIndex + 1) * 1000L + nameIndex + 1);
                }
            }

            var observerConfig = MetricsObserverConfig.builder()
                    .metricsConfig(meterRegistry.metricsFactory().metricsConfig())
                    .meterRegistry(meterRegistry)
                    .buildPrototype();
            var routing = HttpRouting.builder();
            new MpMetricsFeature(observerConfig).register(routing, ENDPOINT);
            client = new DirectClient(routing);

            verifyPrometheus(checkedResponse(false, MediaTypes.TEXT_PLAIN), false);
            verifyPrometheus(checkedResponse(true, MediaTypes.TEXT_PLAIN), true);
            verifyJson(checkedResponse(false, MediaTypes.APPLICATION_JSON), false);
            verifyJson(checkedResponse(true, MediaTypes.APPLICATION_JSON), true);
        } catch (RuntimeException | Error failure) {
            try {
                tearDown();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Scrapes all scopes as Prometheus text.
     *
     * @return serialized metrics
     */
    @Benchmark
    public String unfilteredPrometheus() {
        return response(false, MediaTypes.TEXT_PLAIN);
    }

    /**
     * Scrapes one named application counter as Prometheus text.
     *
     * @return serialized metrics
     */
    @Benchmark
    public String selectedPrometheus() {
        return response(true, MediaTypes.TEXT_PLAIN);
    }

    /**
     * Scrapes all scopes as JSON.
     *
     * @return serialized metrics
     */
    @Benchmark
    public String unfilteredJson() {
        return response(false, MediaTypes.APPLICATION_JSON);
    }

    /**
     * Scrapes one named application counter as JSON.
     *
     * @return serialized metrics
     */
    @Benchmark
    public String selectedJson() {
        return response(true, MediaTypes.APPLICATION_JSON);
    }

    /**
     * Releases routing and the service registry, including the global registry reference.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            client = null;
            if (manager != null) {
                try {
                    manager.shutdown();
                } finally {
                    manager = null;
                }
            }
        }
    }

    private Http1ClientRequest request(boolean selected, MediaType mediaType) {
        var request = client.get(ENDPOINT).accept(mediaType);
        if (selected) {
            request.queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                    .queryParam("name", NAME_PREFIX + "0");
        }
        return request;
    }

    private String response(boolean selected, MediaType mediaType) {
        try (Http1ClientResponse response = request(selected, mediaType).request()) {
            return response.as(String.class);
        }
    }

    private String checkedResponse(boolean selected, MediaType mediaType) {
        try (Http1ClientResponse response = request(selected, mediaType).request()) {
            String body = response.as(String.class);
            assertThat("selected=" + selected + " (" + mediaType + "): " + body, response.status(), is(Status.OK_200));
            return body;
        }
    }

    private void verifyPrometheus(String body, boolean selected) {
        List<String> samples = body.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertThat("Prometheus sample count", samples.size(), is(selected ? 1 : SCOPES.size() * meterNames));
        if (selected) {
            String sample = samples.getFirst();
            assertThat("Selected counter name", sample, startsWith("benchmark_counter_0_total{"));
            assertThat("Selected counter scope", sample, containsString("mp_scope=\"application\""));
            assertThat("Selected counter value",
                       Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1)),
                       is(1001.0));
        } else {
            for (String scope : SCOPES) {
                long count = samples.stream().filter(line -> line.contains("mp_scope=\"" + scope + "\"")).count();
                assertThat("Prometheus samples in " + scope, count, is((long) meterNames));
            }
        }
    }

    private void verifyJson(String body, boolean selected) {
        JsonObject output = JsonParser.create(body).readJsonObject();
        assertThat("JSON counter count", output.size(), is(selected ? 1 : SCOPES.size() * meterNames));
        int scopeCount = selected ? 1 : SCOPES.size();
        int nameCount = selected ? 1 : meterNames;
        for (int scopeIndex = 0; scopeIndex < scopeCount; scopeIndex++) {
            for (int nameIndex = 0; nameIndex < nameCount; nameIndex++) {
                String key = NAME_PREFIX + nameIndex + ";mp_scope=" + SCOPES.get(scopeIndex);
                assertThat("JSON counter " + key,
                           output.longValue(key, -1),
                           is((scopeIndex + 1) * 1000L + nameIndex + 1));
            }
        }
    }
}
