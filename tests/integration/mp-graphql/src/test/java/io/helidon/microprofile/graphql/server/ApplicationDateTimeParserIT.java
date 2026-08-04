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

package io.helidon.microprofile.graphql.server;

import java.io.IOException;

import io.helidon.microprofile.graphql.server.ApplicationDateTimeParserQueries.ApplicationDateTimeDeserializer;
import io.helidon.microprofile.graphql.server.ApplicationDateTimeParserQueries.ApplicationDateTimeInput;
import io.helidon.microprofile.testing.AddBean;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Tests masking of application date/time parsing failures.
 */
@AddBean(ApplicationDateTimeParserQueries.class)
class ApplicationDateTimeParserIT extends AbstractGraphQlCdiIT {

    @Inject
    ApplicationDateTimeParserIT(GraphQlCdiExtension graphQlCdiExtension) {
        super(graphQlCdiExtension);
    }

    @Test
    void testApplicationDateTimeParseExceptionIsMasked() throws IOException {
        setupIndex(indexFileName,
                   ApplicationDateTimeParserQueries.class,
                   ApplicationDateTimeInput.class,
                   ApplicationDateTimeDeserializer.class);
        assertMessageValue("query { parseApplicationDateTime(input: { value: \"Today\" }) }",
                           "Server Error",
                           true);
    }
}
