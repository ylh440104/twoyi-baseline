/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

final class AndroidPropertyFile {
    private AndroidPropertyFile() {}

    /** Keep Android property-file syntax intact while replacing host defaults. */
    static void merge(BufferedReader source, BufferedWriter destination,
                      Map<String, String> overrides) throws IOException {
        if (source != null) {
            String line;
            while ((line = source.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator >= 0 && overrides.containsKey(line.substring(0, separator).trim())) {
                    continue;
                }
                destination.write(line);
                destination.newLine();
            }
        }
        for (Map.Entry<String, String> property : overrides.entrySet()) {
            destination.write(property.getKey() + "=" + property.getValue());
            destination.newLine();
        }
    }
}
