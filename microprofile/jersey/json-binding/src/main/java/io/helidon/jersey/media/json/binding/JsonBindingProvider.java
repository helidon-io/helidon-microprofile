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
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.DateTimeException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.helidon.common.GenericType;
import io.helidon.common.LruCache;
import io.helidon.json.JsonException;
import io.helidon.json.binding.JsonBinding;
import io.helidon.json.binding.JsonBindingFactory;
import io.helidon.json.binding.JsonComponent;
import io.helidon.json.binding.JsonSerializer;

import jakarta.annotation.Priority;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
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
    private final RuntimeType runtimeType;
    private final JsonBinding deserializerBinding = JsonBinding.create();
    private final JsonBinding serializerBinding = JsonBinding.create();
    private final LruCache<Type, Boolean> supportedReadTypes = LruCache.create(1_000);
    private final LruCache<Type, Boolean> supportedWriteTypes = LruCache.create(1_000);

    private JsonBindingProvider(RuntimeType runtimeType) {
        this.runtimeType = Objects.requireNonNull(runtimeType);
    }

    static JsonBindingProvider create(RuntimeType runtimeType) {
        return new JsonBindingProvider(runtimeType);
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return supportsMediaType(mediaType) && supportsRead(genericType);
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
        Charset charset;
        try {
            charset = charset(mediaType, runtimeType == RuntimeType.SERVER);
        } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
            throw new ProcessingException(
                    "Unsupported JSON response charset: "
                            + mediaType.getParameters().get(MediaType.CHARSET_PARAMETER),
                    e);
        }
        try {
            if (StandardCharsets.UTF_8.equals(charset)) {
                return deserializerBinding.deserialize(inputStream, GenericType.create(genericType));
            }
            return deserializerBinding.deserialize(
                    new InputStreamReader(inputStream,
                                          charset.newDecoder()
                                                  .onMalformedInput(CodingErrorAction.REPORT)
                                                  .onUnmappableCharacter(CodingErrorAction.REPORT)),
                    GenericType.create(genericType));
        } catch (JsonException e) {
            if (e.getCause() instanceof IOException ioException
                    && !(ioException instanceof CharacterCodingException)) {
                throw ioException;
            }
            throw invalidJsonBody();
        } catch (IllegalArgumentException | DateTimeException _) {
            throw invalidJsonBody();
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof CharacterCodingException codingException) {
                if (runtimeType == RuntimeType.SERVER) {
                    throw new BadRequestException("Invalid JSON encoding for charset " + charset.name(), codingException);
                }
                throw new ProcessingException("Invalid JSON response encoding for charset " + charset.name(),
                                              codingException);
            }
            throw e.getCause();
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return supportsMediaType(mediaType) && supportsWrite(effectiveWriteType(type, genericType));
    }

    @Override
    public void writeTo(Object object,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        Type effectiveType = effectiveWriteType(type, genericType);
        Charset charset;
        boolean fallback;
        try {
            charset = charset(mediaType, false);
            fallback = !StandardCharsets.UTF_8.equals(charset) && !charset.canEncode();
        } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
            charset = StandardCharsets.UTF_8;
            fallback = true;
        }
        if (fallback) {
            charset = StandardCharsets.UTF_8;
            httpHeaders.putSingle(HttpHeaders.CONTENT_TYPE, mediaType.withCharset(charset.name()));
        }
        try {
            if (StandardCharsets.UTF_8.equals(charset)) {
                serializerBinding.serialize(entityStream, object, GenericType.create(effectiveType));
            } else {
                OutputStreamWriter writer = new OutputStreamWriter(
                        entityStream,
                        charset.newEncoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT));
                serializerBinding.serialize(writer, object, GenericType.create(effectiveType));
                writer.flush();
            }
        } catch (JsonException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Type effectiveWriteType(Class<?> type, Type genericType) {
        if (genericType == Object.class) {
            return type;
        }
        if (genericType instanceof Class<?> declaredType
                && declaredType != type
                && declaredType.isAssignableFrom(type)
                && !supportsWrite(genericType)) {
            return type;
        }
        return genericType;
    }

    private static boolean supportsMediaType(MediaType mediaType) {
        String subtype = mediaType.getSubtype();
        return subtype.equalsIgnoreCase("json")
                || subtype.regionMatches(true, subtype.length() - 5, "+json", 0, 5);
    }

    private RuntimeException invalidJsonBody() {
        if (runtimeType == RuntimeType.SERVER) {
            return new BadRequestException("Invalid JSON request body");
        }
        return new ProcessingException("Invalid JSON response body");
    }

    private static Charset charset(MediaType mediaType, boolean request) {
        String charset = mediaType.getType().equalsIgnoreCase("application")
                ? null
                : mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        if (charset == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charset);
        } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
            if (request) {
                throw new NotSupportedException("Unsupported JSON charset: " + charset, e);
            }
            throw e;
        }
    }

    private boolean supportsRead(Type type) {
        return supportedReadTypes.computeValue(
                type,
                () -> Optional.of(supportsType(type, deserializerBinding.prototype().deserializers(), false)))
                .orElseThrow();
    }

    private boolean supportsWrite(Type type) {
        return supportedWriteTypes.computeValue(type, () -> {
            if (supportsType(type, serializerBinding.prototype().serializers(), true)) {
                return Optional.of(true);
            }
            if (!(type instanceof ParameterizedType parameterizedType)) {
                return Optional.of(false);
            }
            Class<?> rawType = GenericType.create(type).rawType();
            if (!List.class.isAssignableFrom(rawType)
                    && !Map.class.isAssignableFrom(rawType)
                    && !Set.class.isAssignableFrom(rawType)) {
                return Optional.of(false);
            }
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (!Arrays.stream(typeArguments).allMatch(this::supportsBoth)) {
                return Optional.of(false);
            }
            boolean supported = !Map.class.isAssignableFrom(rawType)
                    || serializerBinding.prototype().serializers().stream()
                            .filter(serializer -> serializer.type().equals(GenericType.create(typeArguments[0])))
                            .anyMatch(JsonSerializer::isMapKeySerializer);
            return Optional.of(supported);
        }).orElseThrow();
    }

    private boolean supportsBoth(Type type) {
        return supportsType(type, serializerBinding.prototype().serializers(), true)
                && supportsType(type, deserializerBinding.prototype().deserializers(), false);
    }

    private boolean supportsType(Type type, List<? extends JsonComponent<?>> components, boolean searchInterfaces) {
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
        boolean registeredConverter = components.stream()
                .anyMatch(component -> component.type().equals(genericType));
        if (registeredConverter) {
            return true;
        }

        boolean genericFactory = serializerBinding.prototype().bindingFactories().stream()
                .map(JsonBindingFactory::supportedTypes)
                .flatMap(Set::stream)
                .anyMatch(supportedType -> supportedType == rawType
                        || rawType.isArray() && supportedType == Array.class
                        || rawType.isEnum() && supportedType == Enum.class);
        if (!genericFactory && searchInterfaces) {
            ArrayDeque<Class<?>> interfaceTypes = new ArrayDeque<>(Arrays.asList(rawType.getInterfaces()));
            Set<Class<?>> visitedInterfaces = new HashSet<>();
            while (!interfaceTypes.isEmpty()) {
                Class<?> interfaceType = interfaceTypes.removeFirst();
                if (!visitedInterfaces.add(interfaceType)) {
                    continue;
                }
                GenericType<?> interfaceGenericType = GenericType.create(interfaceType);
                boolean registeredInterfaceSerializer = components.stream()
                        .filter(component -> component.type().isClass())
                        .anyMatch(component -> component.type().equals(interfaceGenericType));
                if (registeredInterfaceSerializer) {
                    return true;
                }
                interfaceTypes.addAll(Arrays.asList(interfaceType.getInterfaces()));
            }
        }
        if (!genericFactory) {
            return false;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (!Arrays.stream(typeArguments).allMatch(this::supportsBoth)) {
                return false;
            }
            return !Map.class.isAssignableFrom(rawType)
                    || serializerBinding.prototype().serializers().stream()
                            .filter(serializer -> serializer.type().equals(GenericType.create(typeArguments[0])))
                            .anyMatch(JsonSerializer::isMapKeySerializer);
        }
        return !rawType.isArray() || supportsBoth(rawType.getComponentType());
    }
}
