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
package io.helidon.microprofile.telemetry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.helidon.tracing.Span;
import io.helidon.tracing.Tracing;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.ws.rs.container.AsyncResponse;

/**
 * Immutable span-creation policy for one intercepted method.
 * {@link TelemetryCdiExtension} prepares this metadata from a bean's effective CDI annotations,
 * including annotation changes made by extensions. {@link WithSpanInterceptor} reuses it to
 * select the span name, kind and parent-context policy, populate {@code code.function.name}
 * and parameter attributes, and identify asynchronous invocations.
 * <p>
 * Metadata is cached per bean, with interface and implementation method aliases resolved on lookup,
 * avoiding repeated annotation processing on each invocation. This type describes how to instrument
 * a method; the interceptor and {@link SpanLifecycle} manage span activation, completion and SDK lease ownership.
 */
final class SpanMethod {
    private final Method method;
    private final String name;
    private final String function;
    private final Span.Kind kind;
    private final boolean inheritContext;
    private final boolean asynchronous;
    private final List<Parameter> parameters;

    private SpanMethod(Method method, String name, Span.Kind kind, boolean inheritContext, List<Parameter> parameters) {
        this.method = method;
        this.name = name;
        this.function = method.getDeclaringClass().getName() + "." + method.getName();
        this.kind = kind;
        this.inheritContext = inheritContext;
        this.asynchronous = CompletionStage.class.isAssignableFrom(method.getReturnType());
        this.parameters = List.copyOf(parameters);
    }

    static SpanMethod create(Method method, AnnotatedMethod<?> annotated) {
        WithSpan annotation = annotated == null ? method.getAnnotation(WithSpan.class) : annotated.getAnnotation(WithSpan.class);
        Tracing.Traced legacy = annotated == null ? method.getAnnotation(Tracing.Traced.class)
                : annotated.getAnnotation(Tracing.Traced.class);
        String name = annotation != null ? annotation.value()
                : legacy != null ? String.format(legacy.value(), method.getDeclaringClass().getName(), method.getName()) : "";
        if (name.isEmpty()) {
            String className = method.getDeclaringClass().getName();
            name = className.substring(className.lastIndexOf('$') + 1) + "." + method.getName();
        }
        var parameters = new ArrayList<Parameter>();
        var reflected = method.getParameters();
        var effective = annotated == null ? null : annotated.getParameters();
        for (int i = 0; i < reflected.length; i++) {
            var parameter = effective == null ? null : effective.get(i);
            SpanAttribute attribute = parameter == null ? reflected[i].getAnnotation(SpanAttribute.class)
                    : parameter.getAnnotation(SpanAttribute.class);
            Tracing.ParamTag tag = parameter == null ? reflected[i].getAnnotation(Tracing.ParamTag.class)
                    : parameter.getAnnotation(Tracing.ParamTag.class);
            String parameterName = attribute != null ? attribute.value() : tag != null ? tag.value() : null;
            if (parameterName != null) {
                parameters.add(new Parameter(i, parameterName.isBlank() ? reflected[i].getName() : parameterName));
            }
        }
        return new SpanMethod(method, name, annotation == null ? Span.Kind.INTERNAL : Span.Kind.valueOf(annotation.kind().name()),
                              annotation == null || annotation.inheritContext(), parameters);
    }

    static SpanMethod lookup(Map<Method, SpanMethod> methods, Method method) {
        SpanMethod metadata = methods.get(method);
        if (metadata != null) {
            return metadata;
        }
        // Interface/implementation aliases are resolved once, within this bean's metadata only.
        for (var candidate : methods.values()) {
            if (candidate.method.getName().equals(method.getName())
                    && Arrays.equals(candidate.method.getParameterTypes(), method.getParameterTypes())) {
                methods.putIfAbsent(method, candidate);
                return candidate;
            }
        }
        var fallback = create(method, null);
        var previous = methods.putIfAbsent(method, fallback);
        return previous == null ? fallback : previous;
    }

    String name() {
        return name;
    }

    String function() {
        return function;
    }

    Span.Kind kind() {
        return kind;
    }

    boolean inheritContext() {
        return inheritContext;
    }

    boolean asynchronous(Object[] arguments) {
        if (asynchronous) {
            return true;
        }
        for (Object argument : arguments) {
            if (argument instanceof AsyncResponse) {
                return true;
            }
        }
        return false;
    }

    void attributes(Span.Builder<?> builder, Object[] arguments) {
        for (var parameter : parameters) {
            switch (arguments[parameter.index()]) {
                case null -> { }
                case String value -> builder.tag(parameter.name(), value);
                case Long value -> builder.tag(parameter.name(), value);
                case Double value -> builder.tag(parameter.name(), value);
                case Boolean value -> builder.tag(parameter.name(), value);
                default -> builder.tag(parameter.name(), arguments[parameter.index()].toString());
            }
        }
    }

    private record Parameter(int index, String name) {
    }
}
