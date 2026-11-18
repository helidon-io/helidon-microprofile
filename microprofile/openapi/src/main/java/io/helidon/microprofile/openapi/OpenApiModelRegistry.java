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
package io.helidon.microprofile.openapi;

import java.beans.Introspector;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Constructible;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;

/**
 * Immutable property metadata from the standard MP interfaces, independent of document formats and model providers.
 */
final class OpenApiModelRegistry {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ref", "$ref"), Map.entry("enumeration", "enum"), Map.entry("defaultValue", "default"),
            Map.entry("schemaDialect", "$schema"), Map.entry("comment", "$comment"), Map.entry("constValue", "const"),
            Map.entry("ifSchema", "if"), Map.entry("thenSchema", "then"), Map.entry("elseSchema", "else"),
            Map.entry("additionalPropertiesSchema", "additionalProperties"));
    private static final Map<Class<?>, String> UNWRAPPED = Map.of(
            Paths.class, "pathItems", Callback.class, "pathItems", Content.class, "mediaTypes",
            APIResponses.class, "APIResponses", SecurityRequirement.class, "schemes");
    private static final Map<Class<?>, Map<String, ModelProperty>> MODELS = metadata();

    private OpenApiModelRegistry() {
    }

    static Set<Class<?>> types() {
        return MODELS.keySet();
    }

    static Map<String, ModelProperty> properties(Class<?> type) {
        return MODELS.get(type);
    }

    static String unwrapped(Class<?> type) {
        return UNWRAPPED.get(type);
    }

    static Constructible create(Class<?> type) {
        return OASFactory.createObject(type.asSubclass(Constructible.class));
    }

    static Object invoke(Method method, Object instance, Object... arguments) {
        try {
            return method.invoke(instance, arguments);
        } catch (IllegalAccessException | InvocationTargetException _) {
            throw new IllegalArgumentException("OpenAPI property " + method.getName() + " could not be converted");
        }
    }

    private static Map<Class<?>, Map<String, ModelProperty>> metadata() {
        Map<Class<?>, Map<String, ModelProperty>> result = new LinkedHashMap<>();
        Arrays.stream(OASFactory.class.getMethods())
                .filter(method -> method.getParameterCount() == 0 && Constructible.class.isAssignableFrom(method.getReturnType()))
                .sorted(Comparator.comparing(Method::getName))
                .forEach(factory -> {
                    Class<?> type = factory.getReturnType();
                    Map<String, ModelProperty> properties = new LinkedHashMap<>();
                    Arrays.stream(type.getMethods()).sorted(Comparator.comparing(Method::getName)).forEach(getter -> {
                        if (!getter.getName().startsWith("get") || getter.getParameterCount() != 0) {
                            return;
                        }
                        String suffix = getter.getName().substring(3);
                        String name = Introspector.decapitalize(suffix);
                        if (name.equals("all") || name.equals("booleanSchema") || name.equals("additionalPropertiesBoolean")) {
                            return;
                        }
                        try {
                            Method setter = type.getMethod("set" + suffix, getter.getReturnType());
                            String wireName = ALIASES.getOrDefault(name, name);
                            if (suffix.equals(suffix.toUpperCase(Locale.ROOT))) {
                                wireName = suffix.toLowerCase(Locale.ROOT);
                            }
                            properties.put(wireName, new ModelProperty(getter, setter, getter.getGenericReturnType()));
                        } catch (NoSuchMethodException _) {
                            // Convenience accessors without a matching setter are not document properties.
                        }
                    });
                    result.put(type, Collections.unmodifiableMap(properties));
                });
        return Collections.unmodifiableMap(result);
    }

    record ModelProperty(Method getter, Method setter, Type type) {
    }
}
