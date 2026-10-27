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

package io.helidon.microprofile.server.hk2;

import java.lang.System.Logger.Level;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.helidon.common.Api;

import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;
import org.glassfish.jersey.internal.inject.Binder;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.ForeignDescriptor;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.server.ResourceConfig;

/**
 * <p>Helidon implementation of an injection manager. Based on two underlying injection managers:
 * one to handle application specific classes (returned by the {@code Application} subclass
 * methods) and one that is shared among all the {@code Application} subclasses. Thus, if a
 * Helidon application comprises N subclasses, then N+1 injection managers will be created.</p>
 *
 * <p>Creating a separate injection manager ensures that providers associated with a certain
 * subclass are not returned for others. There will be an instance of this class for each
 * {@code Application} subclass. This manager needs to get access to the values returned
 * by {@code getClasses} and {@code getSingletons} in order to provide the correct registration
 * semantics.</p>
 *
 * <p>The {@code hk2} package name is required by Jersey's CDI integration, which uses it to identify HK2 managers.</p>
 */
@Api.Internal
public final class HelidonInjectionManager implements InjectionManager {
    private static final System.Logger LOGGER = System.getLogger(HelidonInjectionManager.class.getName());

    private final ResourceConfig resourceConfig;
    private final InjectionManager shared;
    private final InjectionManager forApplication;

    /**
     * Create an injection manager for an application.
     *
     * @param forApplication manager for application-specific providers
     * @param shared manager for shared providers, or {@code null} to use the application manager
     * @param resourceConfig application configuration
     */
    public HelidonInjectionManager(InjectionManager forApplication, InjectionManager shared, ResourceConfig resourceConfig) {
        this.forApplication = forApplication;
        this.shared = shared != null ? shared : forApplication;       // for testing
        this.resourceConfig = resourceConfig;
    }

    @Override
    public void completeRegistration() {
        shared.completeRegistration();
        forApplication.completeRegistration();
    }

    @Override
    public void shutdown() {
        shared.shutdown();
        forApplication.shutdown();
    }

    @Override
    public boolean isShutdown() {
        return shared.isShutdown() && forApplication.isShutdown();
    }

    /**
     * Registers classes returned by {@code getClasses} in {@code forApplication} and
     * all other classes in {@code shared}. This is done to keep separation between
     * global providers and those that are specific to an {@code Application} class.
     *
     * @param binding the binding to register.
     */
    @Override
    public void register(Binding binding) {
        if (returnedByApplication(binding)) {
            forApplication.register(binding);
            LOGGER.log(Level.TRACE, () -> "register forApplication " + forApplication + " " + toString(binding));
        } else {
            shared.register(binding);
            LOGGER.log(Level.TRACE, () -> "register shared " + shared + " " + toString(binding));
        }
    }

    @Override
    public void register(Iterable<Binding> descriptors) {
        descriptors.forEach(this::register);
    }

    @Override
    public void register(Binder binder) {
        binder.getBindings().forEach(this::register);
    }

    @Override
    public void register(Object provider) throws IllegalArgumentException {
        if (getSingletons().contains(provider)) {
            forApplication.register(provider);
            LOGGER.log(Level.TRACE, () -> "register forApplication " + forApplication + " " + provider);
        } else {
            shared.register(provider);
            LOGGER.log(Level.TRACE, () -> "register shared " + forApplication + " " + provider);
        }
    }

    @Override
    public boolean isRegistrable(Class<?> clazz) {
        return shared.isRegistrable(clazz) || forApplication.isRegistrable(clazz);
    }

    @Override
    public <T> T create(Class<T> createMe) {
        try {
            return shared.create(createMe);
        } catch (Throwable t) {
            return forApplication.create(createMe);
        }
    }

    @Override
    public <T> T createAndInitialize(Class<T> createMe) {
        try {
            return shared.createAndInitialize(createMe);
        } catch (Throwable t) {
            return forApplication.createAndInitialize(createMe);
        }
    }

    /**
     * Collects all service holders, including those registered in the {@code shared}
     * and the {@code forApplication}.
     *
     * @param contractOrImpl contract or implementation class.
     * @param qualifiers     the qualifiers.
     * @param <T>            parameter type.
     * @return list of service holders.
     */
    @Override
    public <T> List<ServiceHolder<T>> getAllServiceHolders(Class<T> contractOrImpl, Annotation... qualifiers) {
        List<ServiceHolder<T>> sharedList = shared.getAllServiceHolders(contractOrImpl, qualifiers);
        if (LOGGER.isLoggable(Level.TRACE)) {
            sharedList.forEach(sh -> LOGGER.log(Level.TRACE, "getAllServiceHolders shared "
                    + shared + " "
                    + sh.getContractTypes().iterator().next()));
        }

        List<ServiceHolder<T>> forApplicationList = forApplication.getAllServiceHolders(contractOrImpl, qualifiers);

        if (LOGGER.isLoggable(Level.TRACE)) {
            forApplicationList.forEach(sh -> LOGGER.log(Level.TRACE, "getAllServiceHolders forApplication "
                    + forApplication + " "
                    + sh.getContractTypes().iterator().next()));
        }
        List<ServiceHolder<T>> result = new ArrayList<>(sharedList);
        result.addAll(forApplicationList);
        return result;
    }

    @Override
    public <T> T getInstance(Class<T> contractOrImpl, Annotation... qualifiers) {
        T t = shared.getInstance(contractOrImpl, qualifiers);
        return t != null ? t : forApplication.getInstance(contractOrImpl, qualifiers);
    }

    @Override
    public <T> T getInstance(Class<T> contractOrImpl, String classAnalyzer) {
        T t = shared.getInstance(contractOrImpl, classAnalyzer);
        return t != null ? t : forApplication.getInstance(contractOrImpl, classAnalyzer);
    }

    @Override
    public <T> T getInstance(Class<T> contractOrImpl) {
        T t = shared.getInstance(contractOrImpl);
        return t != null ? t : forApplication.getInstance(contractOrImpl);
    }

    @Override
    public <T> T getInstance(Type contractOrImpl) {
        T t = shared.getInstance(contractOrImpl);
        return t != null ? t : forApplication.getInstance(contractOrImpl);
    }

    @Override
    public Object getInstance(ForeignDescriptor foreignDescriptor) {
        Object o = shared.getInstance(foreignDescriptor);
        return o != null ? o : forApplication.getInstance(foreignDescriptor);
    }

    @Override
    public ForeignDescriptor createForeignDescriptor(Binding binding) {
        try {
            return shared.createForeignDescriptor(binding);
        } catch (Throwable t) {
            return forApplication.createForeignDescriptor(binding);
        }
    }

    @Override
    public <T> List<T> getAllInstances(Type contractOrImpl) {
        List<T> result = new ArrayList<>();
        result.addAll(shared.getAllInstances(contractOrImpl));
        result.addAll(forApplication.getAllInstances(contractOrImpl));
        return result;
    }

    @Override
    public void inject(Object injectMe) {
        try {
            shared.inject(injectMe);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Injection failed for " + injectMe + " using shared", t);
            forApplication.inject(injectMe);
        }
    }

    @Override
    public void inject(Object injectMe, String classAnalyzer) {
        try {
            shared.inject(injectMe, classAnalyzer);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Injection failed for " + injectMe + " using shared", t);
            forApplication.inject(injectMe, classAnalyzer);
        }
    }

    @Override
    public void preDestroy(Object preDestroyMe) {
        shared.preDestroy(preDestroyMe);
        forApplication.preDestroy(preDestroyMe);
    }

    /**
     * Convenience method to display a binding in logging messages.
     *
     * @param b the binding
     * @return string representation of binding
     */
    @SuppressWarnings("unchecked")
    private static String toString(Binding b) {
        StringBuilder sb = new StringBuilder();
        b.getContracts().forEach(c -> sb.append(" Cont ").append(c));
        if (b.getImplementationType() != null) {
            sb.append("\n\tImpl ").append(b.getImplementationType());
        }
        return sb.toString();
    }

    /**
     * Calls {@code getClasses} method in resource config.
     *
     * @return set of classes returned from {@code Application} object.
     */
    private Set<Class<?>> getClasses() {
        Application application = resourceConfig.getApplication();
        return application != null ? resourceConfig.getClasses() : Collections.emptySet();
    }

    /**
     * Calls {@code getSingletons} method in resource config.
     *
     * @return set of singletons returned from {@code Application} object.
     */
    private Set<Object> getSingletons() {
        Application application = resourceConfig.getApplication();
        return application != null ? resourceConfig.getSingletons() : Collections.emptySet();
    }

    /**
     * Checks if any of the contracts are returned by {@code getClasses()} or the implementation type
     * matches a singleton returned by {@code getSingletons}. For a provider such as a filter,
     * the contracts will include both the class implementing the filter,
     * returned by {@code getClasses()}, as well as the JAX-RS API filter interface.
     *
     * @param binding injection manager binding
     * @return outcome of test
     */
    @SuppressWarnings("unchecked")
    private boolean returnedByApplication(Binding binding) {
        // Jersey exposes the implementation type, but not the bound instance, on the module path.
        if (Singleton.class.equals(binding.getScope())
                && getSingletons().stream()
                        .anyMatch(singleton -> singleton.getClass().equals(binding.getImplementationType()))) {
            return true;
        }

        // Check any contract is returned by getClasses()
        return binding.getContracts().stream().anyMatch(c -> getClasses().contains(c));
    }
}
