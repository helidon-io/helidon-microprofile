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
package io.helidon.microprofile.cdi;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.helidon.common.Api;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A qualifier qualifying an event that was fired by earlier versions of Helidon MicroProfile.
 *
 * <p>{@link CdiExtension} fires this event with the string payload {@code "STATIC_INSTANCE"} during {@link
 * jakarta.enterprise.inject.spi.AfterDeploymentValidation}, at priority {@link
 * jakarta.interceptor.Interceptor.Priority#PLATFORM_BEFORE}, after {@link BuildTimeStart} and before {@link
 * RuntimeStart}.  All three events occur within ordinary CDI initialization and no longer represent separate build and
 * runtime phases.</p>
 *
 * @deprecated Observe standard CDI lifecycle events appropriate to the work being performed.
 */
@Api.Stable
@Deprecated(since = "28.0.0")
@Documented
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, PARAMETER, FIELD})
public @interface BuildTimeEnd {
    /**
     * Annotation literal to use when an annotation instance is needed.
     *
     * @deprecated This literal supports the {@link BuildTimeEnd} event.
     */
    @Deprecated(since = "28.0.0")
    final class Literal extends AnnotationLiteral<BuildTimeEnd> implements BuildTimeEnd {
        /**
         * Singleton instance of a literal of this annotation.
         */
        public static final Literal INSTANCE = new Literal();

        private static final long serialVersionUID = 1L;

        /**
         * Creates a new {@link Literal}.
         *
         * @deprecated In prior revisions of this class, the default constructor was not specified, so was implicitly
         * {@code public}. This explicit constructor replaces the default constructor and is {@code public} for
         * backwards compatibility only. It should not be used.
         */
        @Deprecated(since = "28.0.0")
        @SuppressWarnings("checkstyle:RedundantModifier") // checkstyle bug
        public Literal() {
        }
    }
}
