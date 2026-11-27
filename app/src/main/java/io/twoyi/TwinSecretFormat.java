/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

/**
 * The storage envelope for a provider key: a version tag, the GCM nonce and the
 * ciphertext, packed into one printable string.
 *
 * The encryption itself is done by the Android keystore in `TwinSecretStore`,
 * but packing the pieces is plain logic, and getting it wrong (truncated nonce,
 * missing tag) is the kind of bug that only shows up when a user's key fails to
 * come back. That is why the format lives here, with host tests.
 */
public final class TwinSecretFormat {
    private TwinSecretFormat() {}

    public static final String PREFIX = "v1:";
    public static final int NONCE_LENGTH = 12;
    /** AES-GCM tag length in bytes; a shorter blob cannot hold an auth tag. */
    public static final int TAG_LENGTH = 16;

    /** Pack nonce and ciphertext into one ASCII string. */
    public static String encode(byte[] nonce, byte[] ciphertext) {
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            throw new IllegalArgumentException("nonce must be " + NONCE_LENGTH + " bytes");
        }
        if (ciphertext == null || ciphertext.length < TAG_LENGTH) {
            throw new IllegalArgumentException("ciphertext is shorter than the auth tag");
        }
        byte[] combined = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, combined, 0, nonce.length);
        System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
        return PREFIX + androidFreeBase64(combined);
    }

    /** Split one stored string back into nonce and ciphertext. */
    public static byte[][] decode(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported secret envelope");
        }
        byte[] combined;
        try {
            combined = androidFreeBase64Decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("secret envelope is not base64: " + invalid.getMessage());
        }
        if (combined.length < NONCE_LENGTH + TAG_LENGTH) {
            throw new IllegalArgumentException("secret envelope is too short");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH);
        byte[] ciphertext = new byte[combined.length - NONCE_LENGTH];
        System.arraycopy(combined, NONCE_LENGTH, ciphertext, 0, ciphertext.length);
        return new byte[][]{nonce, ciphertext};
    }

    /** True when the value looks like a well-formed envelope. */
    public static boolean looksValid(String stored) {
        try {
            decode(stored);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    // A tiny base64 so the format stays testable without android.util.Base64.
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String androidFreeBase64(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        int index = 0;
        while (index + 2 < data.length) {
            int chunk = ((data[index] & 0xff) << 16)
                    | ((data[index + 1] & 0xff) << 8)
                    | (data[index + 2] & 0xff);
            out.append(ALPHABET[(chunk >> 18) & 0x3f]);
            out.append(ALPHABET[(chunk >> 12) & 0x3f]);
            out.append(ALPHABET[(chunk >> 6) & 0x3f]);
            out.append(ALPHABET[chunk & 0x3f]);
            index += 3;
        }
        int remaining = data.length - index;
        if (remaining == 1) {
            int chunk = (data[index] & 0xff) << 16;
            out.append(ALPHABET[(chunk >> 18) & 0x3f]);
            out.append(ALPHABET[(chunk >> 12) & 0x3f]);
            out.append("==");
        } else if (remaining == 2) {
            int chunk = ((data[index] & 0xff) << 16) | ((data[index + 1] & 0xff) << 8);
            out.append(ALPHABET[(chunk >> 18) & 0x3f]);
            out.append(ALPHABET[(chunk >> 12) & 0x3f]);
            out.append(ALPHABET[(chunk >> 6) & 0x3f]);
            out.append('=');
        }
        return out.toString();
    }

    static byte[] androidFreeBase64Decode(String text) {
        if (text == null) throw new IllegalArgumentException("null");
        int padding = 0;
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '=') {
            padding++;
            end--;
        }
        if (padding > 2) throw new IllegalArgumentException("too much padding");
        int byteCount = (end * 6) / 8;
        byte[] out = new byte[byteCount];
        int accumulator = 0;
        int bits = 0;
        int written = 0;
        for (int index = 0; index < end; index++) {
            int value = valueOf(text.charAt(index));
            accumulator = (accumulator << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (written < out.length) {
                    out[written++] = (byte) ((accumulator >> bits) & 0xff);
                }
            }
        }
        if (written != out.length) {
            byte[] trimmed = new byte[written];
            System.arraycopy(out, 0, trimmed, 0, written);
            return trimmed;
        }
        return out;
    }

    private static int valueOf(char character) {
        if (character >= 'A' && character <= 'Z') return character - 'A';
        if (character >= 'a' && character <= 'z') return character - 'a' + 26;
        if (character >= '0' && character <= '9') return character - '0' + 52;
        if (character == '+') return 62;
        if (character == '/') return 63;
        throw new IllegalArgumentException("invalid base64 character");
    }
}