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

package io.helidon.jersey.media.json.binding;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.helidon.common.GenericType;
import io.helidon.common.LruCache;
import io.helidon.json.binding.JsonBinding;
import io.helidon.json.binding.JsonBindingFactory;
import io.helidon.json.binding.JsonSerializer;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

/**
 * Reads and writes JSON entities supported by Helidon JSON Binding.
 */
@Provider
@Priority(Priorities.ENTITY_CODER)
@Consumes({ "application/json", "text/json", "*/*" })
@Produces({ "application/json", "text/json", "*/*" })
public class JsonBindingProvider implements MessageBodyReader<Object>, MessageBodyWriter<Object> {
    private final JsonBinding deserializerBinding = JsonBinding.create();
    private final JsonBinding serializerBinding = JsonBinding.create();
    private final LruCache<Type, Boolean> supportedTypes = LruCache.create(1_000);

    private JsonBindingProvider() {
    }

    static JsonBindingProvider create() {
        return new JsonBindingProvider();
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return supports(genericType) && supportsMediaType(mediaType);
    }

    @Override
    public Object readFrom(Class<Object> type,
                           Type genericType,
                           Annotation[] annotations,
                           MediaType mediaType,
                           MultivaluedMap<String, String> httpHeaders,
                           InputStream entityStream) throws IOException, WebApplicationException {
        PushbackInputStream inputStream = new PushbackInputStream(entityStream);
        int firstByte = inputStream.read();
        if (firstByte == -1) {
            throw new NoContentException("No content to deserialize");
        }
        inputStream.unread(firstByte);
        return deserializerBinding.deserialize(new InputStreamReader(inputStream, charset(mediaType)),
                                               GenericType.create(genericType));
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return supports(genericType) && supportsMediaType(mediaType);
    }

    @Override
    public void writeTo(Object object,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        OutputStreamWriter writer = new OutputStreamWriter(entityStream, charset(mediaType));
        serializerBinding.serialize(writer, object, GenericType.create(genericType));
        writer.flush();
    }

    private static boolean supportsMediaType(MediaType mediaType) {
        return mediaType.getSubtype().equals("json") || mediaType.getSubtype().endsWith("+json");
    }

    private static Charset charset(MediaType mediaType) {
        String charset = mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        return charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
    }

    private boolean supports(Type type) {
        return supportedTypes.computeValue(type, () -> Optional.of(supportsType(type))).orElseThrow();
    }

    private boolean supportsType(Type type) {
        if (type instanceof TypeVariable<?> || type instanceof WildcardType || type instanceof GenericArrayType) {
            return false;
        }
        GenericType<?> genericType = GenericType.create(type);
        Class<?> rawType = genericType.rawType();
        if (genericType.isClass()
                && (rawType == Object.class
                || List.class.isAssignableFrom(rawType)
                || Map.class.isAssignableFrom(rawType)
                || Optional.class.isAssignableFrom(rawType)
                || Set.class.isAssignableFrom(rawType))) {
            return false;
        }
        boolean registeredConverter = serializerBinding.prototype().serializers().stream()
                .anyMatch(serializer -> serializer.type().equals(genericType))
                && deserializerBinding.prototype().deserializers().stream()
                        .anyMatch(deserializer -> deserializer.type().equals(genericType));
        if (registeredConverter) {
            return true;
        }

        boolean genericFactory = serializerBinding.prototype().bindingFactories().stream()
                .map(JsonBindingFactory::supportedTypes)
                .flatMap(java.util.Set::stream)
                .anyMatch(supportedType -> supportedType == rawType
                        || rawType.isArray() && supportedType == Array.class
                        || rawType.isEnum() && supportedType == Enum.class);
        if (!genericFactory) {
            return false;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (!Arrays.stream(typeArguments).allMatch(this::supports)) {
                return false;
            }
            return !Map.class.isAssignableFrom(rawType)
                    || serializerBinding.prototype().serializers().stream()
                            .filter(serializer -> serializer.type().equals(GenericType.create(typeArguments[0])))
                            .anyMatch(JsonSerializer::isMapKeySerializer);
        }
        return !rawType.isArray() || supports(rawType.getComponentType());
    }
}
