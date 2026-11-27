/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.util.ArrayList;
import java.util.List;

/**
 * Model-catalog plumbing: URL building, response classification and the small
 * amount of JSON reading needed to list models.
 *
 * The newest build fetches the model list from the provider before letting the
 * user pick one, and maps failures to specific copy (`twin_key_invalid`,
 * `twin_model_not_found`, `twin_network_error`, `twin_http_error`). The mapping
 * rules live here without Android or org.json types so they can be tested on the
 * host; the HTTP call itself stays in the Android layer.
 */
public final class TwinModelCatalog {
    private TwinModelCatalog() {}

    public enum Failure { NONE, BAD_KEY, NOT_FOUND, SERVER, MALFORMED_BODY }

    /** Where the catalog lives for an OpenAI-compatible endpoint. */
    public static String modelsUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isEmpty()) return "";
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "/models";
    }

    /** Map an HTTP status to the user-facing failure class. */
    public static Failure classify(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return Failure.NONE;
        if (statusCode == 401 || statusCode == 403) return Failure.BAD_KEY;
        if (statusCode == 404) return Failure.NOT_FOUND;
        if (statusCode >= 500) return Failure.SERVER;
        return Failure.SERVER;
    }

    /**
     * Read the model ids out of an OpenAI-style catalog:
     * {@code {"data":[{"id":"gpt-4o",...},...]}}.
     *
     * A body carrying an {@code "error"} object is reported as a malformed/failed
     * catalog rather than an empty list, so the UI can say something useful.
     */
    public static List<String> parseModelIds(String body) {
        if (body == null || body.trim().isEmpty()) return new ArrayList<>();
        if (containsErrorObject(body)) return new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int key = body.indexOf("\"id\"", cursor);
            if (key < 0) break;
            int colon = skipWhitespace(body, key + 4);
            if (colon < 0 || colon >= body.length() || body.charAt(colon) != ':') {
                cursor = key + 4;
                continue;
            }
            int quoteStart = skipWhitespace(body, colon + 1);
            if (quoteStart < 0 || quoteStart >= body.length() || body.charAt(quoteStart) != '"') {
                cursor = key + 4;
                continue;
            }
            int valueStart = quoteStart + 1;
            int valueEnd = valueStart;
            boolean escaped = false;
            while (valueEnd < body.length()) {
                char character = body.charAt(valueEnd);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    break;
                }
                valueEnd++;
            }
            if (valueEnd >= body.length()) break;
            String id = body.substring(valueStart, valueEnd);
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            cursor = valueEnd + 1;
        }
        return ids;
    }

    /** True when the body looks like an error document rather than a catalog. */
    public static boolean containsErrorObject(String body) {
        if (body == null) return false;
        int marker = body.indexOf("\"error\"");
        if (marker < 0) return false;
        // "errors": [...] or a nested object key is not an error document.
        int after = skipWhitespace(body, marker + 7);
        if (after < 0 || after >= body.length() || body.charAt(after) != ':') return false;
        int value = skipWhitespace(body, after + 1);
        return value >= 0 && value < body.length() && body.charAt(value) == '{';
    }

    /** Read a human-readable message out of an error document, if present. */
    public static String errorMessage(String body) {
        if (body == null) return "";
        int marker = body.indexOf("\"message\"");
        if (marker < 0) return "";
        int after = skipWhitespace(body, marker + 9);
        if (after < 0 || after >= body.length() || body.charAt(after) != ':') return "";
        int quoteStart = skipWhitespace(body, after + 1);
        if (quoteStart < 0 || quoteStart >= body.length() || body.charAt(quoteStart) != '"') return "";
        int end = body.indexOf('"', quoteStart + 1);
        if (end < 0) return "";
        return body.substring(quoteStart + 1, end);
    }

    private static int skipWhitespace(String text, int from) {
        int index = from;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character != ' ' && character != '\t' && character != '\n' && character != '\r') {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** A catalog is usable when it lists at least one model. */
    public static boolean isUsable(String body) {
        return !parseModelIds(body).isEmpty();
    }
}