/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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
package io.helidon.microprofile.faulttolerance;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.process.internal.RequestContext;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.weld.se.WeldRequestScope;
import org.jboss.weld.context.WeldAlterableContext;
import org.jboss.weld.context.api.ContextualInstance;
import org.jboss.weld.context.bound.BoundLiteral;
import org.jboss.weld.context.bound.BoundRequestContext;
import org.jboss.weld.manager.api.WeldManager;

import static java.util.Objects.requireNonNull;

@Dependent
class RequestScopeHelper {

    /**
     * Store access to {@code WeldManager} for instance migration.
     */
    private final WeldManager weldManager;
    /**
     * A {@link Provider} of Jersey's request scope.
     */
    private final Provider<RequestScope> requestScopeProvider;
    private State state = State.CLEARED;
    /**
     * Jersey's request scope object.
     */
    private RequestContext jerseyRequestContext;
    /**
     * Jersey's request scope object. Will be non-null if request scope is active.
     */
    private RequestScope requestScope;
    /**
     * Jersey's injection manager.
     */
    private InjectionManager injectionManager;
    /**
     * Collection of instances in request scope.
     */
    private Collection<ContextualInstance<?>> requestScopeInstances;

    @Inject
    RequestScopeHelper(WeldManager weldManager, Provider<RequestScope> requestScopeProvider) {
        super();
        this.weldManager = requireNonNull(weldManager, "weldManager");
        this.requestScopeProvider = requireNonNull(requestScopeProvider, "requestScopeProvider");
    }

    /**
     * Store request context information from the current thread. State
     * related to Jersey and CDI to handle {@code @Context} and {@code @Inject}
     * injections.
     */
    void saveScope() {
        if (this.state == State.STORED) {
            throw new IllegalStateException("Request scope state already stored");
        }

        // Collect instances for request scope only
        for (WeldAlterableContext context : weldManager.getActiveWeldAlterableContexts()) {
            if (context.getScope() == RequestScoped.class) {
                this.requestScopeInstances = context.getAllContextualInstances();
            }
        }

        // Jersey scope
        this.injectionManager = WeldRequestScope.actualInjectorManager.get();        // thread local
        try {
            this.requestScope = this.requestScopeProvider.get();
            this.jerseyRequestContext = this.requestScope.referenceCurrent();
        } catch (Exception e) {
            // Ignored, Jersey request scope not active
        } finally {
            this.state = State.STORED;
        }
    }

    /**
     * Wraps a supplier into another supplier that actives the request scope
     * before calling it.
     *
     * @param supplier supplier to wrap
     * @return wrapped supplier
     */
    FtSupplier<Object> wrapInScope(FtSupplier<Object> supplier) {
        if (this.state != State.STORED) {
            throw new IllegalStateException("Request scope state never stored");
        }

        if (this.jerseyRequestContext == null) {
            return () -> {
                Runnable migrationCleaner = null;
                try {
                    migrationCleaner = this.migrateRequestContext();
                    return supplier.get();
                } finally {
                    if (migrationCleaner != null) {
                        migrationCleaner.run();
                    }
                }
            };
        } else {
            return () -> this.requestScope
                .runInScope(this.jerseyRequestContext,
                            (Callable<?>) (() -> {
                                    InjectionManager old = WeldRequestScope.actualInjectorManager.get();
                                    Runnable migrationCleaner = null;
                                    try {
                                        migrationCleaner = this.migrateRequestContext();
                                        WeldRequestScope.actualInjectorManager.set(this.injectionManager);
                                        return supplier.get();
                                    } catch (Throwable t) {
                                        throw t instanceof Exception
                                            ? ((Exception) t)
                                            : new RuntimeException(t);
                                    } finally {
                                        if (migrationCleaner != null) {
                                            migrationCleaner.run();
                                        }
                                        WeldRequestScope.actualInjectorManager.set(old);
                                    }
                                }));
        }
    }

    /**
     * Clears internal state saved by calling {@link #saveScope()}.
     */
    @PreDestroy
    private void clearScope() {
        if (this.jerseyRequestContext != null) {
            this.jerseyRequestContext.release();
            this.jerseyRequestContext = null;
        }
        this.injectionManager = null;
        if (this.requestScopeInstances != null) {
            this.requestScopeInstances.clear();
            this.requestScopeInstances = null;
        }
        this.state = State.CLEARED;
    }

    /**
     * Migrates a CDI request context into the new thread. This method will actually
     * set the instances from the original context into the new context so that code
     * running in the new thread can continue to access request scope beans. Note that
     * if a request scope bean in the original context was not accessed/proxied, it
     * will not be carried over.
     *
     * @return runnable that cleans up after migration or {@code null}
     */
    private Runnable migrateRequestContext() {
        if (this.requestScopeInstances != null) {
            // Access CDI context instance
            BoundRequestContext boundRequestContext = this.weldManager.instance()
                    .select(BoundRequestContext.class, BoundLiteral.INSTANCE).get();

            // Ensure a storage and activate if necessary
            Map<String, Object> requestMap = new HashMap<>();
            boolean wasAssociated = boundRequestContext.associate(requestMap);
            boundRequestContext.clearAndSet(this.requestScopeInstances);
            boolean wasActive = boundRequestContext.isActive();
            if (!wasActive) {
                boundRequestContext.activate();
            }

            // Return runnable that properly cleans up after context migration
            return () -> {
                if (!wasActive) {
                    boundRequestContext.deactivate();
                }
                if (wasAssociated) {
                    boundRequestContext.dissociate(requestMap);
                }
            };
        }
        return null;
    }

    enum State {
        CLEARED,
        STORED
    }
}
