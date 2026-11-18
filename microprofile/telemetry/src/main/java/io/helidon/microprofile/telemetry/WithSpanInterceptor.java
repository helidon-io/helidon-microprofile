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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.helidon.tracing.Scope;
import io.helidon.tracing.Span;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.semconv.CodeAttributes;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Intercept {@code HelidonWithSpan} annotated method and invoke tracer.
 */
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 7)
@HelidonWithSpan
class WithSpanInterceptor {
    private static final System.Logger LOGGER = System.getLogger(WithSpanInterceptor.class.getName());

    private final Tracer tracer;
    private final Map<Method, SpanMethod> methods;
    private final TelemetrySdk sdk;
    private final Instance<TelemetryRequest> requests;

    @Inject
    WithSpanInterceptor(Tracer tracer,
                        TelemetryCdiExtension extension,
                        TelemetrySdk sdk,
                        Instance<TelemetryRequest> requests,
                        @Intercepted Bean<?> bean) {
        this.tracer = tracer;
        this.methods = extension.methods(bean);
        this.sdk = sdk;
        this.requests = requests;
    }

    /**
     * Span around method invoke.
     *
     * @param context Invocation Context
     * @return Invocation proceed.
     * @throws Exception when something is wrong
     */
    @AroundInvoke
    public Object interceptSpan(InvocationContext context) throws Exception {

        var lease = sdk.acquire();
        if (lease == null) {
            return context.proceed();
        }

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(System.Logger.Level.TRACE, "Starting new Span on WithSpan annotated method");
        }

        boolean transferred = false;
        try {
            Method method = context.getMethod();
            SpanMethod metadata = SpanMethod.lookup(methods, method);
            var helidonSpanBuilder = tracer.spanBuilder(metadata.name())
                    .kind(metadata.kind())
                    .tag(CodeAttributes.CODE_FUNCTION_NAME.getKey(), metadata.function());
            if (!metadata.inheritContext()) {
                helidonSpanBuilder.parent(HelidonOpenTelemetry.create(Context.root()));
            } else {
                Span.current().map(Span::context).ifPresent(helidonSpanBuilder::parent);
            }
            Object[] arguments = context.getParameters();
            metadata.attributes(helidonSpanBuilder, arguments);
            boolean asyncResource = metadata.asynchronous(arguments);
            HttpServerObservation observation = asyncResource ? observation(method) : null;
            Span helidonSpan = helidonSpanBuilder.start();
            if (!asyncResource) {
                // Preserve synchronous callback ordering and avoid allocating asynchronous lifecycle state.
                try (Scope _ = helidonSpan.activate()) {
                    Object result;
                    try {
                        result = context.proceed();
                    } catch (Exception | Error e) {
                        try {
                            helidonSpan.end(e);
                        } catch (RuntimeException | Error cleanup) {
                            if (cleanup != e) {
                                e.addSuppressed(cleanup);
                            }
                        }
                        throw e;
                    }
                    helidonSpan.end();
                    return result;
                }
            }
            var completion = observation == null ? new SpanLifecycle(helidonSpan, lease, false)
                    : observation.startMethodSpan(helidonSpan, lease);
            transferred = true;
            boolean asynchronous = false;
            Throwable failure = null;
            try (Scope _ = completion.activate()) {
                Object result = context.proceed();
                if (result instanceof CompletionStage<?> stage) {
                    // Observe the original stage without replacing its identity, type, or cancellation semantics.
                    stage.whenComplete((_, thrown) -> completion.completed(thrown));
                    asynchronous = true;
                }
                return result;
            } catch (Exception | Error e) {
                failure = e;
                throw e;
            } finally {
                completion.returned(asynchronous, failure);
            }
        } finally {
            if (!transferred) {
                lease.close();
            }
        }
    }

    private HttpServerObservation observation(Method method) {
        try {
            return requests.get().observation(method);
        } catch (ContextNotActiveException _) {
            // A standalone CDI invocation has no HTTP response to await.
            return null;
        }
    }

}
