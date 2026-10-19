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

import java.util.Map;
import java.util.Set;

import io.helidon.microprofile.config.core.MpConfigSources;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;

import static io.helidon.microprofile.cdi.HelidonBeanDefiningAnnotations.addHelidonBeanDefiningAnnotations;
import static io.helidon.microprofile.cdi.HelidonCdiProperties.addCdiProperties;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonCdiPropertiesTest {
    @Test
    void testMappingAndPrecedence() {
        PropertyInitializer initializer = new PropertyInitializer();
        Set<Class<?>> annotations = Set.of(Test.class);
        initializer.addProperty("org.jboss.weld.se.additionalBeanDefiningAnnotations", annotations);
        initializer.addProperty("overlap", "before");
        Config config = config(Map.of("cdi.overlap", "configured",
                                      "cdi.org.jboss.weld.bootstrap.concurrentDeployment", "false",
                                      "cdi.custom.boolean.text", "true",
                                      "application.name", "ignored",
                                      "cdix.name", "ignored"));

        assertThat(addCdiProperties(initializer, config), sameInstance(initializer));
        assertThat(initializer.value("overlap"), is("configured"));
        assertThat(initializer.value("org.jboss.weld.bootstrap.concurrentDeployment"), is("false"));
        assertThat(initializer.value("custom.boolean.text"), is("true"));
        assertThat(initializer.value("org.jboss.weld.se.additionalBeanDefiningAnnotations"), sameInstance(annotations));
        assertThat(initializer.value("application.name"), nullValue());
        assertThat(initializer.value("name"), nullValue());
        assertThat(initializer.value("cdi.overlap"), nullValue());

        initializer.addProperty("overlap", "after");
        assertThat(initializer.value("overlap"), is("after"));
    }

    @Test
    void testWeldSeBooleanProperties() {
        // When you supply properties to an SeContainerInitializer implemented by Weld, you just have to know (from
        // documentation, etc.) which ones should be converted to Booleans. Somewhat interestingly, Weld's internal
        // configuration system will _not_ do type conversion if a given property is submitted as a System
        // property. That is probably a Weld issue.
        for (String name : Set.of("jakarta.enterprise.inject.scan.implicit",
                                  "org.jboss.weld.bootstrap.allowOptimizedCleanup",
                                  "org.jboss.weld.discovery.disableJandexDiscovery",
                                  "org.jboss.weld.se.archive.isolation",
                                  "org.jboss.weld.se.discovery.emptyBeansXmlModeAll",
                                  "org.jboss.weld.se.scan.classpath.entries",
                                  "org.jboss.weld.se.shutdownHook")) {
            for (String value : Set.of("true", "false", "TrUe")) {
                PropertyInitializer initializer = new PropertyInitializer();
                initializer.addProperty(name, !Boolean.parseBoolean(value));

                addCdiProperties(initializer, config(Map.of("cdi." + name, value)));

                assertThat(name + "=" + value, initializer.value(name), is(Boolean.valueOf(value)));
                initializer.addProperty(name, !Boolean.parseBoolean(value));
                assertThat(initializer.value(name), is(!Boolean.parseBoolean(value)));
            }
        }
    }

    @Test
    void testNoCdiPropertiesPreservesAnnotationHelper() {
        PropertyInitializer initializer = addHelidonBeanDefiningAnnotations(new PropertyInitializer());
        Object annotations = initializer.value("org.jboss.weld.se.additionalBeanDefiningAnnotations");
        initializer.addProperty("existing", 42);

        assertThat(addCdiProperties(initializer, config(Map.of())), sameInstance(initializer));
        assertThat(initializer.value("existing"), is(42));
        assertThat(initializer.value("org.jboss.weld.se.additionalBeanDefiningAnnotations"), sameInstance(annotations));
    }

    @Test
    void testCurrentConfigurationReadWhenCalled() {
        PropertyInitializer initializer = new PropertyInitializer();
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Config original = resolver.getConfig(loader);
        try {
            resolver.registerConfig(config(Map.of("cdi.value", "first")), loader);
            addCdiProperties(initializer);
            assertThat(initializer.value("value"), is("first"));

            resolver.registerConfig(config(Map.of("cdi.value", "second")), loader);
            addCdiProperties(initializer);
            assertThat(initializer.value("value"), is("second"));
        } finally {
            resolver.registerConfig(original, loader);
        }
    }

    @Test
    void testNullArguments() {
        assertThrows(NullPointerException.class, () -> addCdiProperties(null, config(Map.of())));
        assertThrows(NullPointerException.class, () -> addCdiProperties(new PropertyInitializer(), null));
    }

    private static Config config(Map<String, String> properties) {
        return ConfigProviderResolver.instance().getBuilder().withSources(MpConfigSources.create(properties)).build();
    }

    private static class PropertyInitializer extends Weld {
        Object value(String key) {
            return getPropertyValue(key, null);
        }
    }
}
