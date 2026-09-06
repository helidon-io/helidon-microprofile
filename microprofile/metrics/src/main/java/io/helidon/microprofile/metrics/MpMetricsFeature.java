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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderValues;
import io.helidon.http.HttpException;
import io.helidon.http.Status;
import io.helidon.http.media.json.JsonSupport;
import io.helidon.json.JsonObject;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MeterRegistryFormatter;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.spi.MeterRegistryFormatterProvider;
import io.helidon.service.registry.Services;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.SecureHandler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.observe.metrics.MetricsObserverConfig;

import static io.helidon.http.HeaderNames.ALLOW;
import static io.helidon.http.Status.METHOD_NOT_ALLOWED_405;
import static io.helidon.http.Status.NOT_FOUND_404;
import static io.helidon.http.Status.OK_200;

final class MpMetricsFeature {
    private static final Handler DISABLED_ENDPOINT_HANDLER = (req, res) -> res.status(Status.NOT_FOUND_404)
            .send("Metrics are disabled");
    private static final System.Logger LOGGER = System.getLogger(MpMetricsFeature.class.getName());

    private final MetricsObserverConfig metricsObserverConfig;
    private final MetricsConfig metricsConfig;
    private final MeterRegistry meterRegistry;
    private final RegistryFactory registryFactory;
    private final List<MeterRegistryFormatterProvider> formatterProviders;

    MpMetricsFeature(MetricsObserverConfig config) {
        this.metricsObserverConfig = config;
        Optional<MeterRegistry> configuredMeterRegistry = config.meterRegistry();
        this.meterRegistry = configuredMeterRegistry.orElseGet(() -> Services.get(MeterRegistry.class));
        this.metricsConfig = configuredMeterRegistry.isPresent()
                ? config.metricsConfig()
                : meterRegistry.metricsFactory().metricsConfig();
        this.registryFactory = RegistryFactory.getInstance();
        this.formatterProviders = Services.all(MeterRegistryFormatterProvider.class);
    }

    void register(HttpRouting.Builder routing, String endpoint) {
        routing.register(endpoint, new MetricsService());
    }

    private static MediaType bestAccepted(ServerRequest req) {
        return req.headers()
                .bestAccepted(MediaTypes.TEXT_PLAIN,
                              MediaTypes.APPLICATION_OPENMETRICS_TEXT,
                              MediaTypes.APPLICATION_JSON)
                .orElse(null);
    }

    private static MediaType bestAcceptedForMetadata(ServerRequest req) {
        return req.headers()
                .bestAccepted(MediaTypes.APPLICATION_JSON)
                .orElse(null);
    }

    private static Set<String> values(Iterable<String> values) {
        var result = new TreeSet<String>();
        values.forEach(result::add);
        return result;
    }

    private static Optional<?> merge(List<Object> output) {
        if (output.isEmpty()) {
            return Optional.empty();
        }
        if (output.size() == 1) {
            return Optional.of(output.getFirst());
        }
        if (output.getFirst() instanceof JsonObject) {
            JsonObject.Builder result = JsonObject.builder();
            output.forEach(item -> result.from((JsonObject) item));
            return Optional.of(result.build());
        }
        if (output.getFirst() instanceof String) {
            return Optional.of(mergeText(output));
        }
        throw new IllegalStateException("Cannot merge metrics formatter output of type "
                                                + output.getFirst().getClass().getName());
    }

    private static String mergeText(List<Object> output) {
        StringBuilder result = new StringBuilder();
        boolean openMetrics = false;
        for (Object item : output) {
            String text = (String) item;
            int eofIndex = text.lastIndexOf("# EOF");
            if (eofIndex >= 0 && text.substring(eofIndex + "# EOF".length()).isBlank()) {
                text = text.substring(0, eofIndex);
                openMetrics = true;
            }
            result.append(text);
            if (!text.endsWith("\n")) {
                result.append('\n');
            }
        }
        if (openMetrics) {
            result.append("# EOF\n");
        }
        return result.toString();
    }

    private MeterRegistryFormatter chooseFormatter(MediaType mediaType,
                                                    Map<String, Collection<String>> tagSelection,
                                                    Iterable<String> nameSelection) {
        Optional<MeterRegistryFormatter> formatter = formatterProviders.stream()
                .map(provider -> provider.formatter(mediaType,
                                                    metricsConfig,
                                                    meterRegistry,
                                                    tagSelection,
                                                    nameSelection))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        if (formatter.isPresent()) {
            return formatter.get();
        }
        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(System.Logger.Level.TRACE, "Failed to find MeterRegistryFormatter for media type: " + mediaType);
        }
        throw new HttpException("Unsupported media type for metrics formatting: " + mediaType,
                                Status.UNSUPPORTED_MEDIA_TYPE_415,
                                true);
    }

    private Optional<?> output(ServerRequest req,
                               MediaType mediaType,
                               Iterable<String> scopeSelection,
                               Iterable<String> nameSelection) {
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG, "[" + req.serverSocketId() + " "
                    + req.socketId() + "] Preparing MP metrics output");
        }
        return format(mediaType, scopeSelection, nameSelection, MeterRegistryFormatter::format);
    }

    private Optional<?> outputMetadata(MediaType mediaType,
                                       Iterable<String> scopeSelection,
                                       Iterable<String> nameSelection) {
        return format(mediaType, scopeSelection, nameSelection, MeterRegistryFormatter::formatMetadata);
    }

    private Optional<?> format(MediaType mediaType,
                               Iterable<String> scopeSelection,
                               Iterable<String> nameSelection,
                               FormatterOperation formatterOperation) {
        List<Object> output = new ArrayList<>();
        nameGroups(scopeSelection, nameSelection).forEach((scopes, names) -> {
            MeterRegistryFormatter formatter = chooseFormatter(mediaType,
                                                               Map.of(MpScope.TAG_NAME, scopes),
                                                               names);
            formatterOperation.apply(formatter).ifPresent(output::add);
        });
        return merge(output);
    }

    private Map<Set<String>, Set<String>> nameGroups(Iterable<String> scopeSelection,
                                                     Iterable<String> nameSelection) {
        Set<String> requestedScopes = values(scopeSelection);
        Set<String> requestedNames = values(nameSelection);
        Set<String> candidateScopes = new TreeSet<>(registryFactory.scopes());
        if (!requestedScopes.isEmpty()) {
            candidateScopes.retainAll(requestedScopes);
        }
        Map<String, Set<String>> scopesByName = new TreeMap<>();

        for (String scope : candidateScopes) {
            for (String name : registryFactory.registry(scope).getNames()) {
                if (requestedNames.isEmpty() || requestedNames.contains(name)) {
                    scopesByName.computeIfAbsent(name, ignored -> new TreeSet<>()).add(scope);
                }
            }
        }

        Map<Set<String>, Set<String>> result = new LinkedHashMap<>();
        scopesByName.forEach((name, scopes) -> result.computeIfAbsent(Set.copyOf(scopes), ignored -> new TreeSet<>())
                .add(name));
        return result;
    }

    private void getAll(ServerRequest req, ServerResponse res) {
        getMatching(req,
                    res,
                    req.query().all("scope", List::of),
                    req.query().all("name", List::of));
    }

    private void getByScope(ServerRequest req, ServerResponse res) {
        getMatching(req,
                    res,
                    Set.of(req.path().pathParameters().get("scope")),
                    req.query().all("name", List::of));
    }

    private void getByScopeAndName(ServerRequest req, ServerResponse res) {
        getMatching(req,
                    res,
                    Set.of(req.path().pathParameters().get("scope")),
                    Set.of(req.path().pathParameters().get("metric")));
    }

    private void getMatching(ServerRequest req,
                             ServerResponse res,
                             Iterable<String> scopeSelection,
                             Iterable<String> nameSelection) {
        MediaType mediaType = bestAccepted(req);
        res.header(HeaderValues.CACHE_NO_CACHE)
                .header(HeaderValues.X_CONTENT_TYPE_OPTIONS_NOSNIFF);
        if (mediaType == null) {
            res.status(Status.NOT_ACCEPTABLE_406);
            res.send();
            return;
        }

        getOrOptionsMatching(mediaType,
                             req,
                             res,
                             () -> output(req, mediaType, scopeSelection, nameSelection));
    }

    private void getOrOptionsMatching(MediaType mediaType,
                                      ServerRequest req,
                                      ServerResponse res,
                                      Supplier<Optional<?>> dataSupplier) {
        Optional<?> output = dataSupplier.get();

        if (output.isPresent()) {
            res.status(OK_200)
                    .headers()
                    .contentType(mediaType);
            Object entity = output.get();
            if (entity instanceof JsonObject jsonObject) {
                JsonSupport.serverResponseWriter()
                        .write(JsonSupport.JSON_OBJECT_TYPE,
                               jsonObject,
                               res.outputStream(),
                               req.headers(),
                               res.headers());
            } else {
                res.send(entity);
            }
        } else {
            res.status(NOT_FOUND_404);
            res.send();
        }
    }

    private void setUpEndpoints(HttpRules rules) {
        MetricsConfig observerMetricsConfig = metricsObserverConfig.metricsConfig();
        if (!observerMetricsConfig.permitAll()) {
            rules.any(SecureHandler.authorize(observerMetricsConfig.roles().toArray(new String[0])));
        }
        rules.get("/", this::getAll)
                .get("/{scope}", this::getByScope)
                .get("/{scope}/{metric}", this::getByScopeAndName)
                .options("/", this::optionsAll)
                .options("/{scope}", this::optionsByScope)
                .options("/{scope}/{metric}", this::optionsByScopeAndName);
    }

    private void optionsAll(ServerRequest req, ServerResponse res) {
        optionsMatching(req,
                        res,
                        req.query().all("scope", List::of),
                        req.query().all("name", List::of));
    }

    private void optionsByScope(ServerRequest req, ServerResponse res) {
        optionsMatching(req,
                        res,
                        Set.of(req.path().pathParameters().get("scope")),
                        req.query().all("name", List::of));
    }

    private void optionsByScopeAndName(ServerRequest req, ServerResponse res) {
        optionsMatching(req,
                        res,
                        Set.of(req.path().pathParameters().get("scope")),
                        Set.of(req.path().pathParameters().get("metric")));
    }

    private void optionsMatching(ServerRequest req,
                                 ServerResponse res,
                                 Iterable<String> scopeSelection,
                                 Iterable<String> nameSelection) {
        MediaType mediaType = bestAcceptedForMetadata(req);
        if (mediaType == null) {
            res.header(ALLOW, "GET");
            res.status(METHOD_NOT_ALLOWED_405);
            res.send();
            return;
        }

        getOrOptionsMatching(mediaType,
                             req,
                             res,
                             () -> outputMetadata(mediaType, scopeSelection, nameSelection));
    }

    private void setUpDisabledEndpoints(HttpRules rules) {
        rules.get("/", DISABLED_ENDPOINT_HANDLER)
                .get("/{scope}", DISABLED_ENDPOINT_HANDLER)
                .get("/{scope}/{metric}", DISABLED_ENDPOINT_HANDLER)
                .options("/", DISABLED_ENDPOINT_HANDLER)
                .options("/{scope}", DISABLED_ENDPOINT_HANDLER)
                .options("/{scope}/{metric}", DISABLED_ENDPOINT_HANDLER);
    }

    private boolean enabled() {
        return metricsObserverConfig.enabled()
                && metricsObserverConfig.metricsConfig().enabled()
                && formatterProviders.stream()
                .map(provider -> provider.formatter(MediaTypes.TEXT_PLAIN,
                                                    metricsConfig,
                                                    meterRegistry,
                                                    Map.of(),
                                                    List.of()))
                .anyMatch(Optional::isPresent);
    }

    private interface FormatterOperation {
        Optional<?> apply(MeterRegistryFormatter formatter);
    }

    private class MetricsService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
            if (enabled()) {
                setUpEndpoints(rules);
            } else {
                setUpDisabledEndpoints(rules);
            }
        }
    }
}
