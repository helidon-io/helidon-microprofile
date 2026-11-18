/*
 * Copyright (c) 2020, 2026 Oracle and/or its affiliates.
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
package io.helidon.microprofile.openapi;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Vetoed;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.eclipse.microprofile.openapi.models.media.Schema;
import io.smallrye.openapi.model.Extensions;
import io.smallrye.openapi.api.models.media.SchemaImpl;

import static io.helidon.microprofile.openapi.TestUtil.config;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmallRyeOpenApiEngineTest {
    @Test
    void processingOrderAndInvocationCounts() throws Exception {
        Reader.calls.set(0);
        Filter.calls.set(0);
        OpenApiEngine engine = OpenApiEngine.create();
        OpenAPI model = engine.load(config(Map.of("mp.openapi.model.reader", Reader.class.getName(),
                                                 "mp.openapi.filter", Filter.class.getName())),
                getClass().getClassLoader(), () -> List.of(index(), index()), """
                        openapi: 3.0.3
                        info: {title: Static, version: '1'}
                        x-static: retained
                        """);
        assertThat(model.getInfo().getTitle(), is("Annotations filtered"));
        assertThat(model.getExtensions().get("x-reader"), is("retained"));
        assertThat(model.getExtensions().get("x-static"), is("retained"));
        assertThat(model.getOpenapi(), is("3.1.0"));
        assertThat(Reader.calls.get(), is(1));
        assertThat(Filter.calls.get(), is(1));
    }

    @Test
    void disabledScanningDoesNotRequestIndexes() {
        OpenAPI model = OpenApiEngine.create().load(config(Map.of("mp.openapi.scan.disable", "true")),
                getClass().getClassLoader(), () -> { throw new AssertionError("Scanning disabled"); },
                "openapi: 3.0.3\ninfo: {title: Static, version: '1'}");
        assertThat(model.getInfo().getTitle(), is("Static"));
    }

    @Test
    void concurrentLoadsAndFailureRecovery() throws Exception {
        OpenApiEngine engine = OpenApiEngine.create();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<OpenAPI>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String title = "Document " + i;
                tasks.add(() -> engine.load(config(Map.of("mp.openapi.scan.disable", "true")),
                        getClass().getClassLoader(), List::of, "info: {title: '" + title + "', version: '1'}"));
            }
            var results = executor.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get().getInfo().getTitle(), is("Document " + i));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> engine.load(
                config(Map.of("mp.openapi.scan.disable", "true")), getClass().getClassLoader(), List::of, "["));
        OpenAPI recovered = engine.load(config(Map.of("mp.openapi.scan.disable", "true")),
                getClass().getClassLoader(), List::of, "info: {title: Recovered, version: '1'}");
        assertThat(recovered.getInfo().getTitle(), is("Recovered"));
    }

    @Test
    void detachReaderModel() {
        OpenAPI model = OpenApiEngine.create().load(config(Map.of("mp.openapi.scan.disable", "true",
                "mp.openapi.model.reader", Reader.class.getName())), getClass().getClassLoader(), List::of, "");
        model.getInfo().setTitle("Changed");
        assertThat(Reader.model.getInfo().getTitle(), is("Reader"));
    }

    @Test
    void useApplicationClassloadersAndRecoverFromComponentFailures() throws Exception {
        OpenApiEngine engine = OpenApiEngine.create();
        var first = new RecordingClassLoader(getClass().getClassLoader());
        var second = new RecordingClassLoader(getClass().getClassLoader());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var config = config(Map.of("mp.openapi.scan.disable", "true", "mp.openapi.model.reader", Reader.class.getName()));
            var results = executor.invokeAll(List.<Callable<OpenAPI>>of(
                    () -> engine.load(config, first, List::of, "info: {title: First, version: '1'}"),
                    () -> engine.load(config, second, List::of, "info: {title: Second, version: '1'}")));
            assertThat(results.get(0).get().getInfo().getTitle(), is("First"));
            assertThat(results.get(1).get().getInfo().getTitle(), is("Second"));
            assertThat(first.readerLoads.get() > 0, is(true));
            assertThat(second.readerLoads.get() > 0, is(true));
        }
        for (String component : List.of("mp.openapi.model.reader", "mp.openapi.filter")) {
            String implementation = component.endsWith("reader") ? FailingReader.class.getName() : FailingFilter.class.getName();
            assertThrows(IllegalStateException.class, () -> engine.load(
                    config(Map.of(component, implementation, "mp.openapi.scan.disable", "true")),
                    getClass().getClassLoader(), List::of, "info: {title: Example, version: '1'}"));
            OpenAPI recovered = engine.load(config(Map.of("mp.openapi.scan.disable", "true")),
                    getClass().getClassLoader(), List::of, "info: {title: Recovered, version: '1'}");
            assertThat(recovered.getInfo().getTitle(), is("Recovered"));
        }
        assertThrows(IllegalStateException.class, () -> engine.load(config(), getClass().getClassLoader(),
                () -> { throw new IllegalStateException("Index discovery failed"); }, ""));
        assertThat(engine.load(config(Map.of("mp.openapi.scan.disable", "true")),
                getClass().getClassLoader(), List::of, "---").getOpenapi(), is("3.1.0"));
    }

    @Test
    void legacySchemaConstructionPreservesNameAndImplementation() {
        Class<?> implementation = OASFactory.createSchema().getClass();
        var description = ExpandedTypeDescription.create(Schema.class, implementation);
        var node = new Yaml().compose(new StringReader("name: ConfiguredName\ntype: string\n"));
        Schema schema = (Schema) description.newInstance(node);
        assertThat(schema.getClass(), is(implementation));
        assertThat(Extensions.getName(schema), is("ConfiguredName"));
        var custom = ExpandedTypeDescription.create(Schema.class, CustomSchema.class);
        Schema customSchema = (Schema) custom.newInstance(node);
        assertThat(customSchema.getClass(), is(CustomSchema.class));
        assertThat(Extensions.getName(customSchema), is("ConfiguredName"));
        assertThat(Extensions.getName((Schema) description.newInstance(
                new Yaml().compose(new StringReader("type: string\n")))), is(""));
    }

    private static Index index() {
        try {
            return Index.of(AnnotatedApplication.class, Resource.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Vetoed
    @ApplicationPath("/app")
    @OpenAPIDefinition(info = @Info(title = "Annotations", version = "1"))
    public static class AnnotatedApplication extends Application {
    }

    @Vetoed
    @Path("/resource")
    public static class Resource {
        @GET
        public String get() {
            return "example";
        }
    }

    public static class Reader implements OASModelReader {
        static final AtomicInteger calls = new AtomicInteger();
        static final OpenAPI model = OASFactory.createOpenAPI().info(OASFactory.createInfo().title("Reader"))
                .addExtension("x-reader", "retained");

        @Override
        public OpenAPI buildModel() {
            calls.incrementAndGet();
            return model;
        }
    }

    public static class Filter implements OASFilter {
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public void filterOpenAPI(OpenAPI model) {
            calls.incrementAndGet();
            model.getInfo().setTitle(model.getInfo().getTitle() + " filtered");
        }
    }
    public static class FailingReader implements OASModelReader {
        @Override
        public OpenAPI buildModel() {
            throw new IllegalStateException("Reader failed");
        }
    }

    public static class FailingFilter implements OASFilter {
        @Override
        public void filterOpenAPI(OpenAPI model) {
            throw new IllegalStateException("Filter failed");
        }
    }

    private static class RecordingClassLoader extends ClassLoader {
        private final AtomicInteger readerLoads = new AtomicInteger();

        RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Reader.class.getName())) {
                readerLoads.incrementAndGet();
            }
            return super.loadClass(name, resolve);
        }
    }

    @SuppressWarnings("removal")
    public static class CustomSchema extends SchemaImpl {
    }

}
