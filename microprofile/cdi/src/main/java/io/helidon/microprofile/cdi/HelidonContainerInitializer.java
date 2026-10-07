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

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;

/**
 * Initializes the Helidon CDI container through the CDI SE bootstrap API.
 */
public class HelidonContainerInitializer extends SeContainerInitializer {
    private final HelidonContainerImpl container = new HelidonContainerImpl();

    /**
     * Creates a Helidon CDI container initializer.
     */
    public HelidonContainerInitializer() {
    }

    @Override
    public SeContainerInitializer addBeanClasses(Class<?>... classes) {
        this.container.addBeanClasses(classes);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Class<?>... packageClasses) {
        this.container.addPackages(packageClasses);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Class<?>... packageClasses) {
        this.container.addPackages(scanRecursively, packageClasses);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Package... packages) {
        this.container.addPackages(packages);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Package... packages) {
        this.container.addPackages(scanRecursively, packages);
        return this;
    }

    @Override
    public SeContainerInitializer addExtensions(Extension... extensions) {
        this.container.addExtensions(extensions);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked") // heap pollution
    public SeContainerInitializer addExtensions(Class<? extends Extension>... extensions) {
        this.container.addExtensions(extensions);
        return this;
    }

    @Override
    public SeContainerInitializer enableInterceptors(Class<?>... interceptorClasses) {
        this.container.enableInterceptors(interceptorClasses);
        return this;
    }

    @Override
    public SeContainerInitializer enableDecorators(Class<?>... decoratorClasses) {
        this.container.enableDecorators(decoratorClasses);
        return this;
    }

    @Override
    public SeContainerInitializer selectAlternatives(Class<?>... alternativeClasses) {
        this.container.selectAlternatives(alternativeClasses);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked") // heap pollution
    public SeContainerInitializer selectAlternativeStereotypes(Class<? extends Annotation>... alternativeStereotypeClasses) {
        this.container.selectAlternativeStereotypes(alternativeStereotypeClasses);
        return this;
    }

    @Override
    public SeContainerInitializer addProperty(String key, Object value) {
        this.container.addProperty(key, value);
        return this;
    }

    @Override
    public SeContainerInitializer setProperties(Map<String, Object> properties) {
        this.container.setProperties(properties);
        return this;
    }

    @Override
    public SeContainerInitializer disableDiscovery() {
        this.container.disableDiscovery();
        return this;
    }

    @Override
    public SeContainerInitializer setClassLoader(ClassLoader classLoader) {
        this.container.setClassLoader(classLoader);
        return this;
    }

    @Override
    public SeContainer initialize() {
        if (HelidonContainerImpl.isRuntime()) {
            throw new IllegalStateException("Helidon CDI is already started, cannot create two instances in the same JVM");
        }
        ContainerInstanceHolder.set(container);
        this.container.initInContext();
        return this.container.start();
    }
}
