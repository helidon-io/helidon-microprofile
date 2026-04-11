/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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
package io.helidon.microprofile.testing.testng;

import java.lang.reflect.AnnotatedElement;

import io.helidon.microprofile.testing.HelidonTestDescriptorBase;

/**
 * TestNG descriptor implementation.
 */
class HelidonTestDescriptorImpl<T extends AnnotatedElement> extends HelidonTestDescriptorBase<T> {

    HelidonTestDescriptorImpl(T element) {
        super(element);
    }

    @Override
    public long pinningThreshold() {
        return annotations(HelidonTest.class)
                .findFirst()
                .map(HelidonTest::pinningThreshold)
                .orElse(20L);
    }

    @Override
    protected boolean lookupResetPerTest() {
        return annotations(HelidonTest.class)
                .findFirst()
                .map(HelidonTest::resetPerTest)
                .orElse(false);
    }

    @Override
    protected boolean lookupPinningDetection() {
        return annotations(HelidonTest.class)
                .findFirst()
                .map(HelidonTest::pinningDetection)
                .orElse(false);
    }
}
