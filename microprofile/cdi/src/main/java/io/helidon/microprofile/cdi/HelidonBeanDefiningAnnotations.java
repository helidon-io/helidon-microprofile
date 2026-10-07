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

import java.lang.System.Logger;
import java.util.Set;

import jakarta.enterprise.inject.se.SeContainerInitializer;

import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.getLogger;
import static java.util.HashSet.newHashSet;
import static java.util.Objects.requireNonNull;

/**
 * An installer of Helidon-specific bean-defining annotations.
 *
 * @see #addHelidonBeanDefiningAnnotations(SeContainerInitializer, ClassLoader)
 */
public final class HelidonBeanDefiningAnnotations {
    private static final Logger LOGGER = getLogger(HelidonBeanDefiningAnnotations.class.getName());
    private static final Set<String> HELIDON_BEAN_DEFINING_ANNOTATION_CLASSNAMES =
        Set.of("jakarta.ws.rs.Path",
               "jakarta.ws.rs.ext.Provider",
               "jakarta.websocket.server.ServerEndpoint",
               "org.eclipse.microprofile.graphql.GraphQLApi",
               "org.eclipse.microprofile.graphql.Input",
               "org.eclipse.microprofile.graphql.Interface",
               "org.eclipse.microprofile.graphql.Type");

    private HelidonBeanDefiningAnnotations() {
        super();
    }

    /**
     * Installs additional Helidon-specific bean-defining annotations into an {@link SeContainerInitializer} that
     * supports this facility.
     *
     * @param <T> the {@link SeContainerInitializer} type
     *
     * @param seContainerInitializer a non-{@code null} {@link SeContainerInitializer}
     *
     * @return the supplied {@link SeContainerInitializer}
     *
     * @throws NullPointerException if {@code seContainerInitializer} is {@code null}
     *
     * @see #addHelidonBeanDefiningAnnotations(SeContainerInitializer, ClassLoader)
     */
    public static <T extends SeContainerInitializer> T addHelidonBeanDefiningAnnotations(T seContainerInitializer) {
        return addHelidonBeanDefiningAnnotations(seContainerInitializer, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Installs additional Helidon-specific bean-defining annotations into an {@link SeContainerInitializer} that
     * supports this facility.
     *
     * @param <T> the {@link SeContainerInitializer} type
     *
     * @param seContainerInitializer a non-{@code null} {@link SeContainerInitializer}
     *
     * @param classLoader a {@link ClassLoader}
     *
     * @return the supplied {@link SeContainerInitializer}
     *
     * @throws NullPointerException if {@code seContainerInitializer} is {@code null}
     */
    public static <T extends SeContainerInitializer> T addHelidonBeanDefiningAnnotations(T seContainerInitializer,
                                                                                         ClassLoader classLoader) {
        requireNonNull(seContainerInitializer, "seContainerInitializer");
        Set<Class<?>> classes = newHashSet(HELIDON_BEAN_DEFINING_ANNOTATION_CLASSNAMES.size());
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = HelidonBeanDefiningAnnotations.class.getClassLoader();
            }
        }
        for (String className : HELIDON_BEAN_DEFINING_ANNOTATION_CLASSNAMES) {
            try {
                classes.add(Class.forName(className, false, classLoader));
            } catch (ClassNotFoundException e) {
                LOGGER.log(TRACE, () -> className + " is not in the classpath, it will be ignored by CDI", e);
            }
        }
        if (!classes.isEmpty()) {
            seContainerInitializer.addProperty("org.jboss.weld.se.additionalBeanDefiningAnnotations", classes);
        }
        return seContainerInitializer;
    }

    /**
     * A convenience method that returns the fully-qualified class names of the bean-defining annotations added via the
     * {@link #addHelidonBeanDefiningAnnotations(SeContainerInitializer, ClassLoader)} method.
     *
     * <p>There are no guarantees that any identified classes are loadable. Such class names will be skipped.</p>
     *
     * @return an immutable {@link Set}
     *
     * @see #addHelidonBeanDefiningAnnotations(SeContainerInitializer, ClassLoader)
     */
    public static Set<String> helidonBeanDefiningAnnotationClassNames() {
        return HELIDON_BEAN_DEFINING_ANNOTATION_CLASSNAMES;
    }

}
