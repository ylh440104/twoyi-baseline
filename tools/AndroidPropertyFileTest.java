/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AndroidPropertyFileTest {
    public static void main(String[] args) throws IOException {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("persist.sys.language", "zh");
        defaults.put("persist.sys.country", "");
        String input = String.join("\r\n", "# Vendor defaults", "import /vendor/extra.prop",
                "  persist.sys.language = en", "ro.vendor.label=两仪\\image=board",
                "persist.sys.language=ja", "persist.sys.language.extra=unchanged");
        String expected = lines("# Vendor defaults", "import /vendor/extra.prop",
                "ro.vendor.label=两仪\\image=board", "persist.sys.language.extra=unchanged",
                "persist.sys.language=zh", "persist.sys.country=");
        expect(expected, merge(input, defaults));
        expect(expected, merge(expected, defaults));
        expect(lines("persist.sys.language=zh", "persist.sys.country="), merge(null, defaults));
    }

    private static String merge(String input, Map<String, String> defaults) throws IOException {
        StringWriter output = new StringWriter();
        try (BufferedReader reader = input == null ? null : new BufferedReader(new StringReader(input));
             BufferedWriter writer = new BufferedWriter(output)) {
            AndroidPropertyFile.merge(reader, writer, defaults);
        }
        return output.toString();
    }

    private static String lines(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Unexpected property output: " + actual);
    }
}
