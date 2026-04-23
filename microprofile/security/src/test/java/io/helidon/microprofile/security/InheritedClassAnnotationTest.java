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

package io.helidon.microprofile.security;

import io.helidon.common.types.TypeName;
import io.helidon.security.Security;
import io.helidon.security.SecurityLevel;
import io.helidon.security.annotations.Authenticated;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class InheritedClassAnnotationTest {
    @Test
    void testInheritedAuthenticatedAnnotationVisibleInSecurityLevel() {
        assertThat(InheritedAuthenticatedResource.class.getAnnotationsByType(Authenticated.class).length, is(1));

        SecurityLevel directLevel = securityForClass(DirectAuthenticatedResource.class).securityLevels().getFirst();
        SecurityLevel inheritedLevel = securityForClass(InheritedAuthenticatedResource.class).securityLevels().getFirst();

        assertThat(classAnnotationCount(directLevel), is(1L));
        assertThat(classAnnotationCount(inheritedLevel), is(1L));
    }

    private SecurityDefinition securityForClass(Class<?> resourceClass) {
        Security security = Security.builder().build();
        SecurityFilter filter = new SecurityFilter(JerseySecurityFeature.builder(security)
                                                           .build()
                                                           .featureConfig(),
                                                   security,
                                                   security.createContext("inherited-class-annotation-test"));
        return filter.securityForClass(resourceClass, null);
    }

    private long classAnnotationCount(SecurityLevel securityLevel) {
        TypeName typeName = TypeName.create(Authenticated.class);
        return securityLevel.classAnnotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(typeName))
                .count();
    }

    @Authenticated
    private static class DirectAuthenticatedResource {
    }

    @Authenticated
    private static class AuthenticatedBaseResource {
    }

    private static class InheritedAuthenticatedResource extends AuthenticatedBaseResource {
    }
}
