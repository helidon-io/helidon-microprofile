/*
 * Copyright (c) 2019, 2026 Oracle and/or its affiliates.
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

import java.lang.annotation.Annotation;
import java.util.Map;

import io.helidon.common.Api;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;
import org.jboss.weld.environment.se.Weld;

import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;

/**
 * Initializes a Weld container with Helidon's additional bean-defining annotations and CDI properties.
 *
 * @deprecated Use {@link jakarta.enterprise.inject.se.SeContainerInitializer#newInstance()} and apply
 *             {@link io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations} and
 *             {@link io.helidon.microprofile.cdi.HelidonCdiProperties} as needed.
 */
@Api.Stable
@Deprecated(since = "28.0.0")
public class HelidonContainerInitializer extends SeContainerInitializer {
    private final SeContainerInitializer delegate = new Weld();
    private ClassLoader classLoader;

    /**
     * Creates a Helidon CDI container initializer.
     */
    public HelidonContainerInitializer() {
        super();
    }

    @Override
    public SeContainerInitializer addBeanClasses(Class<?>... classes) {
        this.delegate.addBeanClasses(classes);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Class<?>... packageClasses) {
        this.delegate.addPackages(packageClasses);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Class<?>... packageClasses) {
        this.delegate.addPackages(scanRecursively, packageClasses);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Package... packages) {
        this.delegate.addPackages(packages);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Package... packages) {
        this.delegate.addPackages(scanRecursively, packages);
        return this;
    }

    @Override
    public SeContainerInitializer addExtensions(Extension... extensions) {
        this.delegate.addExtensions(extensions);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked") // heap pollution
    public SeContainerInitializer addExtensions(Class<? extends Extension>... extensions) {
        this.delegate.addExtensions(extensions);
        return this;
    }

    @Override
    public SeContainerInitializer enableInterceptors(Class<?>... interceptorClasses) {
        this.delegate.enableInterceptors(interceptorClasses);
        return this;
    }

    @Override
    public SeContainerInitializer enableDecorators(Class<?>... decoratorClasses) {
        this.delegate.enableDecorators(decoratorClasses);
        return this;
    }

    @Override
    public SeContainerInitializer selectAlternatives(Class<?>... alternativeClasses) {
        this.delegate.selectAlternatives(alternativeClasses);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked") // heap pollution
    public SeContainerInitializer selectAlternativeStereotypes(Class<? extends Annotation>... alternativeStereotypeClasses) {
        this.delegate.selectAlternativeStereotypes(alternativeStereotypeClasses);
        return this;
    }

    @Override
    public SeContainerInitializer addProperty(String key, Object value) {
        this.delegate.addProperty(key, value);
        return this;
    }

    @Override
    public SeContainerInitializer setProperties(Map<String, Object> properties) {
        this.delegate.setProperties(properties);
        return this;
    }

    @Override
    public SeContainerInitializer disableDiscovery() {
        this.delegate.disableDiscovery();
        return this;
    }

    @Override
    public SeContainerInitializer setClassLoader(ClassLoader classLoader) {
        this.delegate.setClassLoader(classLoader);
        this.classLoader = classLoader;
        return this;
    }

    @Override
    public SeContainer initialize() {
        if (this.classLoader == null) {
            addHelidonBeanDefiningAnnotations(this.delegate);
        } else {
            addHelidonBeanDefiningAnnotations(this.delegate, this.classLoader);
        }
        addCdiProperties(this.delegate);
        return SeContainerInitializers.initialize(this.delegate);
    }
}
