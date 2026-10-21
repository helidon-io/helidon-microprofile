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

package io.helidon.microprofile.tests.testing.junit5;

import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.enterprise.inject.se.SeContainer;
import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.config.WeldConfiguration;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.jboss.weld.config.ConfigurationKey.CONCURRENT_DEPLOYMENT;

@HelidonTest(resetPerTest = true)
@AddConfig(key = "cdi.org.jboss.weld.bootstrap.concurrentDeployment", value = "false")
class TestCdiProperties {
    @Test
    void testClassConfiguration(SeContainer container) {
        assertConcurrentDeployment(container, false);
    }

    @Test
    @AddConfig(key = "cdi.org.jboss.weld.bootstrap.concurrentDeployment", value = "true")
    void testMethodConfiguration(SeContainer container) {
        assertConcurrentDeployment(container, true);
    }

    private static void assertConcurrentDeployment(SeContainer container, boolean expected) {
        WeldConfiguration configuration = BeanManagerProxy.unwrap(container.getBeanManager())
                .getServices().get(WeldConfiguration.class);
        assertThat(configuration.getBooleanProperty(CONCURRENT_DEPLOYMENT), is(expected));
    }
}
