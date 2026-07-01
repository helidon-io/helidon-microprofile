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

import io.helidon.grpc.api.Grpc;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("deprecation")
class GrpcClientAnnotationsTest {

    @Test
    void resolvesReplacementChannelAnnotation() {
        assertThat(GrpcClientAnnotations.channelName(ReplacementChannel.class).orElseThrow(), is("replacement"));
    }

    @Test
    void resolvesLegacyChannelAnnotation() {
        assertThat(GrpcClientAnnotations.channelName(LegacyChannel.class).orElseThrow(), is("legacy"));
    }

    @Test
    void acceptsMatchingChannelAnnotations() {
        assertThat(GrpcClientAnnotations.channelName(MatchingChannels.class).orElseThrow(), is("matching"));
    }

    @Test
    void rejectsConflictingChannelAnnotations() {
        assertThrows(IllegalArgumentException.class,
                     () -> GrpcClientAnnotations.channelName(ConflictingChannels.class));
    }

    @GrpcChannel("replacement")
    private static class ReplacementChannel {
    }

    @Grpc.GrpcChannel("legacy")
    private static class LegacyChannel {
    }

    @GrpcChannel("matching")
    @Grpc.GrpcChannel("matching")
    private static class MatchingChannels {
    }

    @GrpcChannel("replacement")
    @Grpc.GrpcChannel("legacy")
    private static class ConflictingChannels {
    }
}
