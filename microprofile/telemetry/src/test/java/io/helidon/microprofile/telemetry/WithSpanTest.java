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

import java.util.List;
import java.util.stream.Stream;

import io.helidon.microprofile.testing.AddBean;
import io.helidon.tracing.Tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.CodeAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@AddBean(WithSpanTest.SeTracedBean.class)
@AddBean(WithSpanTest.RootBean.class)
class WithSpanTest extends WithSpanTestBase {

    @Inject
    SeTracedBean seTracedBean;
    @Inject
    RootBean rootBean;
    @Inject
    OpenTelemetry telemetry;

    static Stream<SpanPathTestInfo> testDefaultAppSpanNameFromPath() {
        return Stream.of(new SpanPathTestInfo("traced", "/traced"),
                         new SpanPathTestInfo("traced/sub/data", "/traced/sub/{name}"));
    }

    @Test
    void testSpanName() {
        withSpanBean.runWithAttrsScalar("nameTest", 3L);

        List<SpanData> spanData = testSpanExporter.spanData(1);
        assertThat("Test span name", spanData.get(0).getName(), is(WithSpanBean.TEST_SPAN_NAME_SCALAR));
    }

    @Test
    void testSpanAttributes() {
        withSpanBean.runWithAttrsScalar("attrTestScalar", 4L);
        withSpanBean.runWithAttrsObject("attrTestObject", 5L);
        List<SpanData> spanData = testSpanExporter.spanData(2);
        AttributeKey<String> stringKey = AttributeKey.stringKey(WithSpanBean.STRING_ATTR_NAME);
        AttributeKey<Long> longKey = AttributeKey.longKey(WithSpanBean.LONG_ATTR_NAME);

        Attributes attrs = spanData.get(0).getAttributes();

        assertThat("String attribute", attrs.get(stringKey), is("attrTestScalar"));
        assertThat("Long attribute", attrs.get(longKey), is(4L));

        attrs = spanData.get(1).getAttributes();
        assertThat("String attribute", attrs.get(stringKey), is("attrTestObject"));
        assertThat("Long attribute", attrs.get(longKey), is(5L));
    }

    @Test
    void testSeTracingWithoutWithSpan() {
        seTracedBean.run("se-only");

        SpanData span = testSpanExporter.spanData(1).getFirst();
        assertThat(span.getName(), is("seTraced"));
        assertThat(span.getAttributes().get(AttributeKey.stringKey("value")), is("se-only"));
    }

    @Test
    void testRootKindAndCodeAttribute() {
        var parent = telemetry.getTracer("test").spanBuilder("parent").startSpan();
        try (var _ = parent.makeCurrent()) {
            rootBean.run();
        } finally {
            parent.end();
        }
        var root = testSpanExporter.spanData(2).stream()
                .filter(span -> span.getName().equals("root")).findFirst().orElseThrow();
        assertThat(root.getKind(), is(SpanKind.CLIENT));
        assertThat(root.getParentSpanId(), is("0000000000000000"));
        assertThat(root.getAttributes().get(CodeAttributes.CODE_FUNCTION_NAME), is(RootBean.class.getName() + ".run"));
    }

    @ApplicationScoped
    static class RootBean {
        @WithSpan(value = "root", kind = SpanKind.CLIENT, inheritContext = false)
        void run() {
        }
    }

    @ParameterizedTest()
    @MethodSource()
    void testDefaultAppSpanNameFromPath(SpanPathTestInfo spanPathTestInfo) {
        testSpanNameFromPath(spanPathTestInfo);
    }

    @ApplicationScoped
    static class SeTracedBean {
        @Tracing.Traced("seTraced")
        void run(@Tracing.ParamTag("value") String value) {
        }
    }
}
