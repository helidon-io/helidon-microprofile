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

/**
 * Provides a historical MicroProfile Config module name for dependencies relying on it.
 *
 * <p>This module effectively "forwards" a requirement of {@code microprofile.config.api} to the now-explicit {@code
 * org.eclipse.microprofile.config} module name. Because of this, this module deliberately does not start with {@code
 * io.helidon}.</p>
 *
 * <p>This module should not be required except in very unusual circumstances.</p>
 */
@SuppressWarnings({"checkstyle:ModuleName", "requires-transitive-automatic"})
module microprofile.config.api {

    requires transitive org.eclipse.microprofile.config;

}
