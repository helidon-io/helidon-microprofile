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
package io.helidon.microprofile.telemetry;

import java.lang.reflect.Method;
import java.util.Arrays;

import jakarta.enterprise.context.RequestScoped;

/**
 * CDI access to the selected REST resource invocation. Completion callbacks retain the observation, not this proxy.
 */
@RequestScoped
class TelemetryRequest {
    private Method resourceMethod;
    private HttpServerObservation observation;

    void initialize(Method method, HttpServerObservation observation) {
        this.resourceMethod = method;
        this.observation = observation;
    }

    HttpServerObservation observation(Method method) {
        // Jersey's method may be declared by an interface or superclass rather than the intercepted implementation.
        return resourceMethod != null && resourceMethod.getName().equals(method.getName())
                && Arrays.equals(resourceMethod.getParameterTypes(), method.getParameterTypes())
                && (resourceMethod.getDeclaringClass().isAssignableFrom(method.getDeclaringClass())
                    || method.getDeclaringClass().isAssignableFrom(resourceMethod.getDeclaringClass()))
                ? observation : null;
    }
}
