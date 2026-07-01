/*
 * Copyright (c) 2019, 2026 Oracle and/or its affiliates.
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

package io.helidon.microprofile.grpc.core;

import java.lang.reflect.Method;
import java.util.AbstractMap;

import io.helidon.grpc.api.Grpc;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ModelHelperTest {

    @Test
    public void shouldGetAnnotatedSuperClass() {
        Class<?> cls = ModelHelper.getAnnotatedResourceClass(ChildOne.class, Grpc.GrpcService.class);
        assertThat(cls, equalTo(Parent.class));
    }

    @Test
    public void shouldGetSelfIfAnnotated() {
        Class<?> cls = ModelHelper.getAnnotatedResourceClass(Parent.class, Grpc.GrpcService.class);
        assertThat(cls, equalTo(Parent.class));
    }

    @Test
    public void shouldGetSelfIfNothingAnnotated() {
        Class<?> cls = ModelHelper.getAnnotatedResourceClass(NoAnnotated.class, Grpc.GrpcService.class);
        assertThat(cls, equalTo(NoAnnotated.class));
    }

    @Test
    public void shouldGetAnnotatedSuperClassBeforeInterface() {
        Class<?> cls = ModelHelper.getAnnotatedResourceClass(ChildTwo.class, Grpc.GrpcService.class);
        assertThat(cls, equalTo(Parent.class));
    }

    @Test
    public void shouldGetAnnotatedInterface() {
        Class<?> cls = ModelHelper.getAnnotatedResourceClass(ChildThree.class, Grpc.GrpcService.class);
        assertThat(cls, equalTo(IFaceOne.class));
    }

    @Test
    void shouldResolveMatchingMessageTypeAnnotations() throws Exception {
        Method method = ModelHelperTest.class.getMethod("matchingMessageTypes");

        assertThat(ModelHelper.getRequestType(method.getAnnotation(RequestType.class),
                                              method.getAnnotation(Grpc.RequestType.class),
                                              Object.class),
                   equalTo(Long.class));
        assertThat(ModelHelper.getResponseType(method.getAnnotation(ResponseType.class),
                                               method.getAnnotation(Grpc.ResponseType.class),
                                               Object.class),
                   equalTo(String.class));
    }

    @Test
    void shouldRejectConflictingMessageTypeAnnotations() throws Exception {
        Method method = ModelHelperTest.class.getMethod("conflictingMessageTypes");

        assertThrows(IllegalArgumentException.class,
                     () -> ModelHelper.getRequestType(method.getAnnotation(RequestType.class),
                                                      method.getAnnotation(Grpc.RequestType.class),
                                                      Object.class));
        assertThrows(IllegalArgumentException.class,
                     () -> ModelHelper.getResponseType(method.getAnnotation(ResponseType.class),
                                                       method.getAnnotation(Grpc.ResponseType.class),
                                                       Object.class));
    }

    @Test
    void shouldRejectConflictingMarshallerAnnotations() throws Exception {
        Method method = ModelHelperTest.class.getMethod("conflictingMarshaller");

        assertThrows(IllegalArgumentException.class,
                     () -> ModelHelper.getMarshallerSupplier(method.getAnnotation(GrpcMarshaller.class),
                                                             method.getAnnotation(Grpc.GrpcMarshaller.class)));
    }

    @Test
    void shouldRejectNullMpMarshallerAnnotation() {
        assertThrows(NullPointerException.class,
                     () -> ModelHelper.getMpMarshallerSupplier(null));
    }

    // ----- helper methods -------------------------------------------------

    @Grpc.GrpcMarshaller(Grpc.GrpcMarshaller.PROTO)
    public void protoMarshaller() {
    }

    @Grpc.GrpcMarshaller
    public void implicitDefaultMarshaller() {
    }

    @Grpc.GrpcMarshaller
    public void explicitDefaultMarshaller() {
    }

    @RequestType(Long.class)
    @Grpc.RequestType(Long.class)
    @ResponseType(String.class)
    @Grpc.ResponseType(String.class)
    public void matchingMessageTypes() {
    }

    @RequestType(Long.class)
    @Grpc.RequestType(Integer.class)
    @ResponseType(String.class)
    @Grpc.ResponseType(CharSequence.class)
    public void conflictingMessageTypes() {
    }

    @GrpcMarshaller("java")
    @Grpc.GrpcMarshaller("proto")
    public void conflictingMarshaller() {
    }

    @Grpc.GrpcService
    public interface IFaceOne {
    }

    public interface IFaceTwo
            extends IFaceOne {
    }

    @Grpc.GrpcService
    public static class GrandParent {
    }

    @Grpc.GrpcService
    public static class Parent
            extends GrandParent {
    }

    public static class ChildOne
            extends Parent {
    }

    public class ChildTwo
            extends Parent
            implements IFaceOne {
    }

    public class ChildThree
            implements IFaceOne {
    }

    public class ChildFour
            implements IFaceTwo {
    }

    public class ChildFive
            extends ChildFour {
    }

    public abstract class NoAnnotated
            extends AbstractMap {
    }
}
