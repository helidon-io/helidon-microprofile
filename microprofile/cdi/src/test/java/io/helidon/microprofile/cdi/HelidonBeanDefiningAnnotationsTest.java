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

package io.helidon.microprofile.cdi;

import java.util.HashSet;
import java.util.Set;

import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.helidonBeanDefiningAnnotationClassNames;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonBeanDefiningAnnotationsTest {
    private ClassLoader originalClassLoader;
    private Weld initializer;

    @BeforeEach
    void setUp() {
        this.originalClassLoader = Thread.currentThread().getContextClassLoader();
        this.initializer = new Weld();
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(this.originalClassLoader);
    }

    @Test
    void testExplicitClassLoaderTakesPrecedence() {
        TrackingClassLoader contextClassLoader = new TrackingClassLoader();
        TrackingClassLoader suppliedClassLoader = new TrackingClassLoader();
        Thread.currentThread().setContextClassLoader(contextClassLoader);

        assertThat(addHelidonBeanDefiningAnnotations(this.initializer, suppliedClassLoader), sameInstance(this.initializer));
        assertThat(suppliedClassLoader.requestedAnnotations, is(helidonBeanDefiningAnnotationClassNames()));
        assertThat(contextClassLoader.requestedAnnotations, empty());
    }

    @Test
    void testDefaultUsesContextClassLoader() {
        TrackingClassLoader contextClassLoader = new TrackingClassLoader();
        Thread.currentThread().setContextClassLoader(contextClassLoader);

        assertThat(addHelidonBeanDefiningAnnotations(this.initializer), sameInstance(this.initializer));
        assertThat(contextClassLoader.requestedAnnotations, is(helidonBeanDefiningAnnotationClassNames()));
    }

    @Test
    void testDefaultWithoutContextClassLoader() {
        Thread.currentThread().setContextClassLoader(null);

        assertThat(addHelidonBeanDefiningAnnotations(this.initializer), sameInstance(this.initializer));
    }

    @Test
    void testNullArguments() {
        assertThrows(NullPointerException.class, () -> addHelidonBeanDefiningAnnotations(this.initializer, null));
        assertThrows(NullPointerException.class, () -> addHelidonBeanDefiningAnnotations(null));
        assertThrows(NullPointerException.class,
                     () -> addHelidonBeanDefiningAnnotations(null, HelidonBeanDefiningAnnotations.class.getClassLoader()));
    }

    private static class TrackingClassLoader extends ClassLoader {
        private final Set<String> requestedAnnotations = new HashSet<>();

        private TrackingClassLoader() {
            super(HelidonBeanDefiningAnnotations.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (helidonBeanDefiningAnnotationClassNames().contains(name)) {
                this.requestedAnnotations.add(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
