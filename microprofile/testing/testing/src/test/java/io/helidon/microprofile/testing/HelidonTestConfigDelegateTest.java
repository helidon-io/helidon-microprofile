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

package io.helidon.microprofile.testing;

import java.util.Map;

import io.helidon.microprofile.config.core.MpConfigSources;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonTestConfigDelegateTest {

    @Test
    void removedValuesDoNotCreateNodes() {
        Config mpConfig = ConfigProviderResolver.instance().getBuilder()
                .withSources(MpConfigSources.create(Map.of("complex.present", "value",
                                                          "complex.removed", "",
                                                          "absent.removed", "")))
                .build();
        HelidonTestConfigDelegate config = new HelidonTestConfigDelegate() {
            @Override
            Config delegate() {
                return mpConfig;
            }
        };

        assertThat(config.get("complex").get("present").asString().get(), is("value"));
        assertThat(config.get("complex.removed").exists(), is(false));
        assertThat(config.get("absent").exists(), is(false));
    }
}
