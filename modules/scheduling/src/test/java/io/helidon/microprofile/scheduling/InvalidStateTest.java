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

package io.helidon.microprofile.scheduling;

import java.time.format.DateTimeParseException;
import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.scheduling.Scheduling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.fail;

public class InvalidStateTest {

    @Test
    void zeroRate() {
        assertDeploymentException(IllegalArgumentException.class, ZeroRateBean.class);
    }

    @Test
    void invalidCron() {
        assertDeploymentException(IllegalArgumentException.class, InvalidCronBean.class);
    }

    @Test
    void invalidAnnotations() {
        assertDeploymentException(DeploymentException.class, DoubleAnnotationBean.class);
    }

    @Test
    void unresolvedCronPlaceholder() {
        assertDeploymentException(io.helidon.config.ConfigException.class, UnresolvedPlaceholderBean.class);
    }

    @Test
    void negativeDelay() {
        assertDeploymentException(IllegalArgumentException.class, NegativeDelay.class);
    }

    @Test
    void invalidTimeUnit() {
        assertDeploymentException(DateTimeParseException.class,
                                  Map.of("test.duration",
                                         "LIGHT_YEAR"),
                                  InvalidDuration.class);
    }

    void assertDeploymentException(Class<? extends Throwable> expected, Class<?>... beans) {
        assertDeploymentException(expected, Map.of(), beans);
    }

    @SuppressWarnings("unchecked")
    void assertDeploymentException(Class<? extends Throwable> expected, Map<String, String> configMap, Class<?>... beans) {
        Config config = ConfigProviderResolver.instance().getBuilder()
                .withSources(MpConfigSources.create(configMap),
                             MpConfigSources.create(Map.of("mp.initializer.allow", "true")))
                .build();

        ConfigProviderResolver.instance()
                .registerConfig(config, Thread.currentThread().getContextClassLoader());

        SeContainerInitializer initializer = SeContainerInitializer.newInstance();
        initializer.addExtensions(SchedulingCdiExtension.class);
        initializer.addBeanClasses(beans);
        try (SeContainer _ = initializer.initialize()) {
            fail("Expected " + expected.getName());
        } catch (AssertionFailedError e) {
            throw e;
        } catch (Throwable e) {
            assertThat(e, instanceOf(expected));
        }
    }

    @ApplicationScoped
    static class InvalidCronBean {
        @Scheduling.Cron("invalid cron")
        void invalidCron() {
        }
    }

    @ApplicationScoped
    static class DoubleAnnotationBean {
        @Scheduling.Cron("0/2 * * * * ? *")
        @Scheduling.FixedRate("PT2S")
        void invalidAnnotations() {
        }
    }

    @ApplicationScoped
    static class UnresolvedPlaceholderBean {
        @Scheduling.Cron("${unresolved}")
        void invalidAnnotations() {
        }
    }

    @ApplicationScoped
    static class NegativeDelay {
        @Scheduling.FixedRate("PT-1S")
        void negativeDelay() {
        }
    }

    @ApplicationScoped
    static class ZeroRateBean {
        @Scheduling.FixedRate("PT0S")
        void zeroRate() {
        }
    }

    @ApplicationScoped
    static class InvalidDuration {
        @Scheduling.FixedRate("${test.duration:PT10S}")
        void invalidTimeUnitMethod() {
        }
    }
}
