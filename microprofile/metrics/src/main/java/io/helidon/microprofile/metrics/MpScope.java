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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.metrics.api.Tag;

import org.eclipse.microprofile.metrics.MetricRegistry;

final class MpScope {
    static final String TAG_NAME = "mp_scope";
    private static final ScopedValue<String> REGISTRATION_SCOPE = ScopedValue.newInstance();

    private MpScope() {
    }

    static Tag tag(String scope) {
        return new ScopeTag(TAG_NAME, Objects.requireNonNull(scope));
    }

    static String scope(Meter meter) {
        return meter.id().tagsMap().getOrDefault(TAG_NAME, MetricRegistry.APPLICATION_SCOPE);
    }

    static Iterable<Map.Entry<String, String>> validatedTags(Iterable<Map.Entry<String, String>> tags) {
        var result = new ArrayList<Map.Entry<String, String>>();
        tags.forEach(tag -> {
            if (tag.getKey().equals(TAG_NAME)) {
                throw new IllegalArgumentException("Illegal use of reserved tag name: " + TAG_NAME);
            }
            result.add(tag);
        });
        return result;
    }

    static Optional<String> registrationScope() {
        return REGISTRATION_SCOPE.isBound() ? Optional.of(REGISTRATION_SCOPE.get()) : Optional.empty();
    }

    @SuppressWarnings({"removal", "unchecked"})
    static <B extends Meter.Builder<B, M>, M extends Meter> M getOrCreate(MeterRegistry meterRegistry,
                                                                          MetricsFactory metricsFactory,
                                                                          String scope,
                                                                          B builder) {
        Objects.requireNonNull(meterRegistry);
        Objects.requireNonNull(metricsFactory);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(builder);
        boolean scopeTagPresent = validateScopeTag(builder, scope);

        if (!isMeterEnabled(metricsFactory.metricsConfig(), scope, builder.name())) {
            if (!scopeTagPresent) {
                builder.addTag(tag(scope));
            }
            return (M) metricsFactory.noOpMeter(builder);
        }

        if (REGISTRATION_SCOPE.isBound()) {
            throw new IllegalStateException("Nested MP meter registration for scope " + scope
                                                    + " while registering scope " + REGISTRATION_SCOPE.get());
        }

        try {
            return ScopedValue.where(REGISTRATION_SCOPE, scope)
                    .call(() -> getOrCreateAndVerify(meterRegistry, scope, builder));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MP meter registration failed", e);
        }
    }

    @SuppressWarnings("removal")
    static boolean isMeterEnabled(MetricsConfig metricsConfig, String scope, String meterName) {
        var scopeConfig = metricsConfig.scoping().scopes().get(scope);
        return metricsConfig.enabled()
                && (scopeConfig == null
                            || scopeConfig.enabled()
                            && scopeConfig.include().map(pattern -> pattern.matcher(meterName).matches()).orElse(true)
                            && scopeConfig.exclude().map(pattern -> !pattern.matcher(meterName).matches()).orElse(true));
    }

    private static boolean validateScopeTag(Meter.Builder<?, ?> builder, String expectedScope) {
        if (!builder.tags().containsKey(TAG_NAME)) {
            return false;
        }
        String actualScope = builder.tags().get(TAG_NAME);
        if (!expectedScope.equals(actualScope)) {
            throw new IllegalArgumentException("Conflicting " + TAG_NAME + " value " + actualScope
                                                       + "; expected " + expectedScope);
        }
        return true;
    }

    private static <B extends Meter.Builder<B, M>, M extends Meter> M getOrCreateAndVerify(MeterRegistry meterRegistry,
                                                                                           String scope,
                                                                                           B builder) {
        M meter = meterRegistry.getOrCreate(builder);
        String actualScope = meter.id().tagsMap().get(TAG_NAME);
        if (!scope.equals(actualScope)) {
            throw new IllegalStateException("MP meter registration for scope " + scope
                                                    + " produced scope " + actualScope);
        }
        return meter;
    }

    private record ScopeTag(String key, String value) implements Tag {
        @Override
        public <T> T unwrap(Class<? extends T> type) {
            return type.cast(this);
        }
    }
}
