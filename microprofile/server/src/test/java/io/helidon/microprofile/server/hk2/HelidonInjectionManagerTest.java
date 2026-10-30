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

package io.helidon.microprofile.server.hk2;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;
import org.glassfish.jersey.inject.hk2.Hk2InjectionManagerFactory;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

import static org.glassfish.jersey.inject.hk2.Bindings.serviceAsContract;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonInjectionManagerTest {
    @Test
    void distinctSingletonOfSameClassRemainsShared() {
        Hk2InjectionManagerFactory factory = new Hk2InjectionManagerFactory();
        InjectionManager shared = factory.create(null);
        InjectionManager application = factory.create(null);
        InjectionManager otherApplication = factory.create(null);
        Object applicationSingleton = new Object();
        Object sharedSingleton = new Object();
        ResourceConfig config = ResourceConfig.forApplication(new Application() {
            @Override
            public Set<Object> getSingletons() {
                return Set.of(applicationSingleton);
            }
        });
        InjectionManager manager = HelidonInjectionManager.create(application, shared, config);
        InjectionManager otherManager = HelidonInjectionManager.create(otherApplication, shared, new ResourceConfig());
        try {
            manager.register(serviceAsContract(applicationSingleton));
            manager.register(serviceAsContract(sharedSingleton));

            assertThat(application.getInstance(Object.class), sameInstance(applicationSingleton));
            assertThat(shared.getInstance(Object.class), sameInstance(sharedSingleton));
            assertThat(otherManager.getInstance(Object.class), sameInstance(sharedSingleton));
            assertThat(otherApplication.getInstance(Object.class), nullValue());
        } finally {
            otherApplication.shutdown();
            application.shutdown();
            shared.shutdown();
        }
    }

    @Test
    void singletonClassBindingRemainsSharedAndLazy() {
        Hk2InjectionManagerFactory factory = new Hk2InjectionManagerFactory();
        InjectionManager shared = factory.create(null);
        InjectionManager application = factory.create(null);
        LifecycleSingleton singleton = new LifecycleSingleton();
        ResourceConfig config = new ResourceConfig().register(singleton);
        InjectionManager manager = HelidonInjectionManager.create(application, shared, config);
        int initialConstructions = LifecycleSingleton.CONSTRUCTIONS.get();
        try {
            manager.register(serviceAsContract(LifecycleSingleton.class).in(Singleton.class));

            assertThat(LifecycleSingleton.CONSTRUCTIONS.get(), is(initialConstructions));
            assertThat(application.getInstance(LifecycleSingleton.class), nullValue());
            assertThat(shared.getInstance(LifecycleSingleton.class), not(sameInstance(singleton)));
            assertThat(LifecycleSingleton.CONSTRUCTIONS.get(), is(initialConstructions + 1));
        } finally {
            application.shutdown();
            shared.shutdown();
        }
    }

    @Test
    void inspectingInstanceDoesNotRunLifecycleCallbacks() {
        Hk2InjectionManagerFactory factory = new Hk2InjectionManagerFactory();
        InjectionManager shared = factory.create(null);
        InjectionManager application = factory.create(null);
        LifecycleSingleton singleton = new LifecycleSingleton();
        ResourceConfig config = new ResourceConfig().register(singleton);
        InjectionManager manager = HelidonInjectionManager.create(application, shared, config);
        int initialConstructions = LifecycleSingleton.CONSTRUCTIONS.get();
        try {
            manager.register(serviceAsContract(singleton));

            assertThat(application.getInstance(LifecycleSingleton.class), sameInstance(singleton));
            assertThat(shared.getInstance(LifecycleSingleton.class), nullValue());
            assertThat(LifecycleSingleton.CONSTRUCTIONS.get(), is(initialConstructions));
            assertThat(singleton.initializations, is(0));
            assertThat(singleton.destructions, is(0));
        } finally {
            application.shutdown();
            shared.shutdown();
        }
        assertThat(singleton.destructions, is(0));
    }

    public static class LifecycleSingleton {
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        private int initializations;
        private int destructions;

        // HK2's default class analyzer requires a public no-argument constructor.
        @SuppressWarnings("checkstyle:RedundantModifier")
        public LifecycleSingleton() {
            CONSTRUCTIONS.incrementAndGet();
        }

        @PostConstruct
        public void initialize() {
            initializations++;
        }

        @PreDestroy
        public void destroy() {
            destructions++;
        }
    }
}
