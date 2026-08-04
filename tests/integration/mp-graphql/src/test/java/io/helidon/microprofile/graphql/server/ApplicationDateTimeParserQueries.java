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

import java.lang.reflect.Type;
import java.time.format.DateTimeParseException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Input;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Queries for application date/time parser tests.
 */
@GraphQLApi
@ApplicationScoped
public class ApplicationDateTimeParserQueries {

    /**
     * Parse an input using an application-provided JSON-B deserializer.
     *
     * @param input input to parse
     * @return parsed value
     */
    @Query
    public String parseApplicationDateTime(@Name("input") ApplicationDateTimeInput input) {
        return input.getValue();
    }

    /**
     * Input with an application-provided JSON-B deserializer.
     */
    @Input
    public static class ApplicationDateTimeInput {
        @JsonbTypeDeserializer(ApplicationDateTimeDeserializer.class)
        private String value;

        /**
         * Get the value.
         *
         * @return value
         */
        public String getValue() {
            return value;
        }

        /**
         * Set the value.
         *
         * @param value value to set
         */
        public void setValue(String value) {
            this.value = value;
        }
    }

    /**
     * Application deserializer that fails with a date/time parsing exception.
     */
    public static class ApplicationDateTimeDeserializer implements JsonbDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
            throw new DateTimeParseException("Application parser detail", "invalid", 0);
        }
    }
}
