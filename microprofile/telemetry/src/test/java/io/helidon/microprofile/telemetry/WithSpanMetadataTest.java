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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.AddExtension;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

@HelidonTest
@AddBean(TestSpanExporter.class)
@AddBean(WithSpanMetadataTest.First.class)
@AddBean(WithSpanMetadataTest.Second.class)
@AddBean(WithSpanMetadataTest.Added.class)
@AddExtension(WithSpanMetadataTest.Transform.class)
@AddConfig(key = "otel.sdk.disabled", value = "false")
@AddConfig(key = "otel.traces.exporter", value = "in-memory")
class WithSpanMetadataTest {
    @Inject
    First first;
    @Inject
    Second second;
    @Inject
    Added added;
    @Inject
    TestSpanExporter exporter;
    @Inject
    TelemetrySdk owner;

    @Test
    void inheritedMethodsRetainEachBeansTransformedMetadataAndAddedAnnotations() {
        exporter.clear();
        var parent = owner.telemetry().getTracer("test").spanBuilder("parent").startSpan();
        try (var _ = parent.makeCurrent()) {
            first.run("one");
            second.run("two");
            added.run("three");
        } finally {
            parent.end();
        }
        ((OpenTelemetrySdk) owner.telemetry()).getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
        var spans = exporter.spanData(4);
        assertThat(spans.stream().map(data -> data.getName()).toList(), containsInAnyOrder("parent", "first", "second", "added"));
        var firstSpan = spans.stream().filter(data -> data.getName().equals("first")).findFirst().orElseThrow();
        var secondSpan = spans.stream().filter(data -> data.getName().equals("second")).findFirst().orElseThrow();
        assertThat(firstSpan.getKind(), is(SpanKind.CLIENT));
        assertThat(firstSpan.getParentSpanId(), is("0000000000000000"));
        assertThat(firstSpan.getAttributes().get(AttributeKey.stringKey("first-parameter")), is("one"));
        assertThat(secondSpan.getKind(), is(SpanKind.PRODUCER));
        assertThat(secondSpan.getParentSpanId(), is(parent.getSpanContext().getSpanId()));
        assertThat(secondSpan.getAttributes().get(AttributeKey.stringKey("second-parameter")), is("two"));
    }

    public static class Base {
        @WithSpan("base")
        public void run(@SpanAttribute("original") String value) {
        }
    }

    @ApplicationScoped
    public static class First extends Base {
    }

    @ApplicationScoped
    public static class Second extends Base {
    }

    @ApplicationScoped
    public static class Added {
        public void run(String value) {
        }
    }

    public static class Transform implements Extension {
        void transform(@Observes @Priority(Integer.MAX_VALUE - 1) ProcessAnnotatedType<?> event) {
            Class<?> type = event.getAnnotatedType().getJavaClass();
            if (!List.of(First.class, Second.class, Added.class).contains(type)) {
                return;
            }
            String name = type.getSimpleName().toLowerCase(Locale.ROOT);
            event.configureAnnotatedType().methods().stream()
                    .filter(method -> method.getAnnotated().getJavaMember().getName().equals("run"))
                    .forEach(method -> {
                        method.remove(annotation -> annotation.annotationType() == WithSpan.class)
                                .add(new SpanAnnotation(name));
                        method.params().getFirst().remove(annotation -> annotation.annotationType() == SpanAttribute.class)
                                .add(new ParameterAnnotation(name + "-parameter"));
                    });
        }
    }

    private static final class SpanAnnotation extends AnnotationLiteral<WithSpan> implements WithSpan {
        private final String name;

        private SpanAnnotation(String name) {
            this.name = name;
        }

        @Override
        public String value() {
            return name;
        }

        @Override
        public SpanKind kind() {
            return name.equals("first") ? SpanKind.CLIENT : SpanKind.PRODUCER;
        }

        @Override
        public boolean inheritContext() {
            return !name.equals("first");
        }
    }

    private static final class ParameterAnnotation extends AnnotationLiteral<SpanAttribute> implements SpanAttribute {
        private final String name;

        private ParameterAnnotation(String name) {
            this.name = name;
        }

        @Override
        public String value() {
            return name;
        }
    }
}
