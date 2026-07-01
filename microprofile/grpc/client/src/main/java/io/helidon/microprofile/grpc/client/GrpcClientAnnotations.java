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

package io.helidon.microprofile.grpc.client;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;

import io.helidon.grpc.api.Grpc;

import jakarta.enterprise.inject.spi.Annotated;

final class GrpcClientAnnotations {
    private GrpcClientAnnotations() {
    }

    static boolean isProxy(Annotated annotated) {
        return annotated.isAnnotationPresent(GrpcProxy.class)
                || annotated.isAnnotationPresent(Grpc.GrpcProxy.class);
    }

    static Optional<String> channelName(Annotated annotated) {
        return channelName(annotated.getAnnotation(GrpcChannel.class),
                           annotated.getAnnotation(Grpc.GrpcChannel.class));
    }

    static Optional<String> channelName(AnnotatedElement annotated) {
        return channelName(annotated.getAnnotation(GrpcChannel.class),
                           annotated.getAnnotation(Grpc.GrpcChannel.class));
    }

    private static Optional<String> channelName(GrpcChannel annotation, Grpc.GrpcChannel legacyAnnotation) {
        if (annotation != null
                && legacyAnnotation != null
                && !annotation.value().equals(legacyAnnotation.value())) {
            throw new IllegalArgumentException("Conflicting @GrpcChannel annotations: '"
                                                       + annotation.value()
                                                       + "' and '"
                                                       + legacyAnnotation.value()
                                                       + "'");
        }
        if (annotation != null) {
            return Optional.of(annotation.value());
        }
        return legacyAnnotation == null ? Optional.empty() : Optional.of(legacyAnnotation.value());
    }
}
