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
 * Jersey integration with Helidon JSON Binding.
 * <p>
 * Add the following dependency to automatically register the Helidon JSON Binding entity provider with Jersey server
 * and client runtimes:
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.helidon.jersey&lt;/groupId&gt;
 *     &lt;artifactId&gt;helidon-jersey-media-json-binding&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * <h2>Provider selection</h2>
 * The provider handles {@code application/json}, {@code text/json}, and structured-syntax-suffix media types such as
 * {@code application/problem+json}. It claims an entity only when Helidon JSON Binding has the component or factory
 * required for the read or write direction. Reads use the declared type. For writes, an unsupported declared class or
 * interface supertype, including one declared as a parameterized type, falls back to a supported runtime type when its
 * raw type can be resolved and is assignable from the runtime type. Other unsupported erased or generic shapes remain
 * available to other Jersey providers, including JSON-B.
 *
 * <h2>Encoding and failures</h2>
 * Application JSON media types use UTF-8. Declared character sets are honored for other JSON media types when
 * supported; unsupported output character sets fall back to UTF-8. Invalid server request bodies are reported as
 * {@link jakarta.ws.rs.BadRequestException}, while invalid client response bodies are reported as
 * {@link jakarta.ws.rs.ProcessingException}. Transport failures remain {@link java.io.IOException} instances.
 */
package io.helidon.jersey.media.json.binding;
