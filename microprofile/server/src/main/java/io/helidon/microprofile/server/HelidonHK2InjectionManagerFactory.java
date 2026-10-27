/*
 * Copyright (c) 2022, 2026 Oracle and/or its affiliates.
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

package io.helidon.microprofile.server;

import java.lang.System.Logger.Level;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

import io.helidon.common.Api;
import io.helidon.microprofile.server.hk2.HelidonInjectionManager;

import jakarta.annotation.Priority;
import org.glassfish.jersey.inject.hk2.Hk2InjectionManagerFactory;
import org.glassfish.jersey.inject.hk2.ImmediateHk2InjectionManager;
import org.glassfish.jersey.internal.inject.Binder;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.ForeignDescriptor;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.server.ResourceConfig;

/**
 * Overrides Jersey's {@link Hk2InjectionManagerFactory} to enable Helidon's support for multiple JAX-RS applications.
 *
 * <p> For multiple applications, creates {@link HelidonInjectionManager} instances that separate shared providers from
 * providers registered by each application's {@code getClasses()} and {@code getSingletons()} methods. In the normal
 * case of a single application, reuses the supplied Jersey injection manager.</p>
 */
@Priority(11)   // overrides Jersey's
public class HelidonHK2InjectionManagerFactory extends Hk2InjectionManagerFactory {
    private static final System.Logger LOGGER = System.getLogger(HelidonHK2InjectionManagerFactory.class.getName());

    /**
     * Required public constructor for {@link java.util.ServiceLoader}.
     */
    @Api.Internal
    public HelidonHK2InjectionManagerFactory() {
    }

    @Override
    public InjectionManager create(Object parent) {
        InjectionManager result;

        if (parent == null) {
            result = super.create(null);
            LOGGER.log(Level.TRACE, () -> "Creating injection manager " + result);
        } else if (parent instanceof ImmediateHk2InjectionManager) {        // single JAX-RS app
            result = (InjectionManager) parent;
            LOGGER.log(Level.TRACE, () -> "Using injection manager for single app case " + result);
        } else if (parent instanceof InjectionManagerWrapper) {             // multiple JAX-RS apps
            InjectionManagerWrapper wrapper = (InjectionManagerWrapper) parent;
            InjectionManager forApplication = super.create(null);
            result = new HelidonInjectionManager(forApplication, wrapper.injectionManager, wrapper.application);
            LOGGER.log(Level.TRACE, () -> "Creating injection manager for multi app case " + forApplication
                    + " with shared " + wrapper.injectionManager);
        } else if (parent instanceof HelidonInjectionManager) {
            result = (InjectionManager) parent;
            LOGGER.log(Level.TRACE, () -> "Re-using existing Helidon injection manager " + result);
        } else {
            throw new IllegalStateException("Invalid parent injection manager");
        }
        return result;
    }

    /**
     * A simple {@code InjectionManager} wrapper to keep track of a resource config.
     * Methods in this class should never be called.
     */
    static class InjectionManagerWrapper implements InjectionManager {

        private final InjectionManager injectionManager;
        private final ResourceConfig application;

        InjectionManagerWrapper(InjectionManager injectionManager, ResourceConfig application) {
            this.injectionManager = injectionManager;
            this.application = application;
        }

        @Override
        public void completeRegistration() {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public boolean isShutdown() {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void register(Binding binding) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void register(Iterable<Binding> descriptors) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void register(Binder binder) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void register(Object provider) throws IllegalArgumentException {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public boolean isRegistrable(Class<?> clazz) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> T create(Class<T> createMe) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> T createAndInitialize(Class<T> createMe) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> List<ServiceHolder<T>> getAllServiceHolders(Class<T> contractOrImpl, Annotation... qualifiers) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> T getInstance(Class<T> contractOrImpl, Annotation... qualifiers) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> T getInstance(Class<T> contractOrImpl, String classAnalyzer) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> T getInstance(Class<T> contractOrImpl) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> T getInstance(Type contractOrImpl) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public Object getInstance(ForeignDescriptor foreignDescriptor) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public ForeignDescriptor createForeignDescriptor(Binding binding) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public <T> List<T> getAllInstances(Type contractOrImpl) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void inject(Object injectMe) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void inject(Object injectMe, String classAnalyzer) {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void preDestroy(Object preDestroyMe) {
            throw new UnsupportedOperationException("Not supported");
        }
    }
}
