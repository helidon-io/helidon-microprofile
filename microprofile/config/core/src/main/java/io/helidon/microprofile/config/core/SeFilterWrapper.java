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

package io.helidon.microprofile.config.core;

import io.helidon.config.spi.ConfigFilter;
import io.helidon.microprofile.config.core.spi.MpConfigFilter;

import org.eclipse.microprofile.config.Config;

class SeFilterWrapper implements MpConfigFilter {
    private final ConfigFilter delegate;

    private SeFilterWrapper(ConfigFilter delegate) {
        this.delegate = delegate;
    }

    static SeFilterWrapper wrap(ConfigFilter filter) {
        return new SeFilterWrapper(filter);
    }

    @Override
    public void init(Config config) {
        delegate.init(MpConfig.toHelidonConfig(config));
    }

    @Override
    public String apply(String propertyName, String value) {
        return delegate.apply(io.helidon.config.Config.Key.create(propertyName), value);
    }
}
