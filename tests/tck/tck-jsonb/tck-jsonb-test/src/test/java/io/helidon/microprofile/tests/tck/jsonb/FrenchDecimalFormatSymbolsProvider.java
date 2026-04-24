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

package io.helidon.microprofile.tests.tck.jsonb;

import java.text.DecimalFormatSymbols;
import java.text.spi.DecimalFormatSymbolsProvider;
import java.util.Locale;

/**
 * Restores the historical French grouping separator expected by the JSON-B TCK.
 */
public class FrenchDecimalFormatSymbolsProvider extends DecimalFormatSymbolsProvider {
    private static final Locale[] SUPPORTED_LOCALES = {
            Locale.FRENCH,
            Locale.FRANCE
    };

    @Override
    public Locale[] getAvailableLocales() {
        return SUPPORTED_LOCALES.clone();
    }

    @Override
    public DecimalFormatSymbols getInstance(Locale locale) {
        Locale strippedLocale = locale.stripExtensions();
        if (!Locale.FRENCH.getLanguage().equals(strippedLocale.getLanguage())) {
            throw new IllegalArgumentException("Unsupported locale: " + locale);
        }

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setDecimalSeparator(',');
        symbols.setGroupingSeparator('\u00A0');
        return symbols;
    }
}
