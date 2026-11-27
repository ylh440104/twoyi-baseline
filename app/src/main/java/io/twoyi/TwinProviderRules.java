/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

/**
 * Local validation for the model-connection form.
 *
 * The newest build rejects a connection before any network call, with specific
 * copy for each failure: an empty key, a key carrying non-English/full-width
 * characters or a line break, and a URL that is not http(s). Keeping the rules
 * here (no Android types) means they are testable on the host, where a wrong rule
 * costs a CI run instead of a user's tap.
 */
public final class TwinProviderRules {
    private TwinProviderRules() {}

    public enum KeyProblem { NONE, REQUIRED, BAD_CHARS }
    public enum UrlProblem { NONE, EMPTY, NOT_HTTP, MALFORMED }

    /** Longest key we accept; a real key is far shorter than this. */
    public static final int MAX_KEY_LENGTH = 512;

    /**
     * A key is valid when it is not blank, has no character outside 0x21..0x7e
     * (which rejects spaces, line breaks, full-width and other non-English
     * characters), and fits the length bound.
     */
    public static KeyProblem checkKey(String key) {
        if (key == null || key.trim().isEmpty()) return KeyProblem.REQUIRED;
        if (key.length() > MAX_KEY_LENGTH) return KeyProblem.BAD_CHARS;
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (character < 0x21 || character > 0x7e) return KeyProblem.BAD_CHARS;
        }
        return KeyProblem.NONE;
    }

    /**
     * A URL must be absolute http(s) with a host and no whitespace. Query strings
     * and paths are allowed; a trailing slash is not required.
     */
    public static UrlProblem checkBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) return UrlProblem.NONE; // optional
        String value = url.trim();
        String lower = value.toLowerCase(java.util.Locale.US);
        boolean http = lower.startsWith("http://");
        boolean https = lower.startsWith("https://");
        if (!http && !https) return UrlProblem.NOT_HTTP;
        String rest = value.substring(http ? 7 : 8);
        if (rest.isEmpty()) return UrlProblem.MALFORMED;
        for (int index = 0; index < rest.length(); index++) {
            char character = rest.charAt(index);
            if (character <= 0x20 || character == 0x7f) return UrlProblem.MALFORMED;
        }
        String authority = rest;
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        int question = authority.indexOf('?');
        if (question >= 0) authority = authority.substring(0, question);
        int hash = authority.indexOf('#');
        if (hash >= 0) authority = authority.substring(0, hash);
        if (authority.isEmpty()) return UrlProblem.MALFORMED;
        // A scheme-only host like "http://:443" has no name.
        String host = authority;
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(']') < colon) {
            String port = host.substring(colon + 1);
            host = host.substring(0, colon);
            if (port.isEmpty()) return UrlProblem.MALFORMED;
            for (int index = 0; index < port.length(); index++) {
                if (!Character.isDigit(port.charAt(index))) return UrlProblem.MALFORMED;
            }
        }
        return host.isEmpty() ? UrlProblem.MALFORMED : UrlProblem.NONE;
    }

    /** The model name is required and must not contain whitespace at the edges. */
    public static boolean isModelNameValid(String model) {
        return model != null && !model.trim().isEmpty() && model.trim().equals(model);
    }

    /** True when the form may be submitted (model name is checked separately). */
    public static boolean canConnect(String key, String baseUrl, String model) {
        return checkKey(key) == KeyProblem.NONE
                && checkBaseUrl(baseUrl) == UrlProblem.NONE
                && isModelNameValid(model);
    }
}