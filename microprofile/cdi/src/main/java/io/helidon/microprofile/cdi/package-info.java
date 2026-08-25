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
/**
 * CDI extension for Helidon MP.
 * <p>
 * Helidon MP owns the application-global service registry. It installs the registry before initializing CDI and keeps the
 * registry available until CDI shutdown, including bean {@code @PreDestroy} callbacks, has completed. Helidon MP then shuts
 * down the registry and its services.
 * <p>
 * MP Config can install a temporary application-global registry when configuration is accessed before Helidon MP starts.
 * Helidon MP recognizes, shuts down, and replaces that bootstrap registry during startup. Any other application-global
 * registry configured before Helidon MP starts is rejected; applications must not configure
 * {@link io.helidon.service.registry.GlobalServiceRegistry} or initialize it through
 * {@link io.helidon.service.registry.Services} before starting Helidon MP.
 *
 * @see jakarta.enterprise.context
 */
package io.helidon.microprofile.cdi;
