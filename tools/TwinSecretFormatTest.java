package io.twoyi;

import java.util.Arrays;

public final class TwinSecretFormatTest {
    public static void main(String[] args) {
        roundTrip();
        base64();
        rejections();
        System.out.println("PASS: provider secret envelope packs a nonce and ciphertext safely");
    }

    private static void roundTrip() {
        byte[] nonce = new byte[TwinSecretFormat.NONCE_LENGTH];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i + 1);
        byte[] ciphertext = new byte[TwinSecretFormat.TAG_LENGTH + 20];
        for (int i = 0; i < ciphertext.length; i++) ciphertext[i] = (byte) (0xa0 + i);

        String stored = TwinSecretFormat.encode(nonce, ciphertext);
        expect(stored.startsWith(TwinSecretFormat.PREFIX), "prefix present");
        expect(TwinSecretFormat.looksValid(stored), "envelope looks valid");

        byte[][] parts = TwinSecretFormat.decode(stored);
        expect(parts.length == 2, "two parts");
        expect(Arrays.equals(parts[0], nonce), "nonce survives");
        expect(Arrays.equals(parts[1], ciphertext), "ciphertext survives");

        // A second encode of the same inputs is byte-identical (no timestamps).
        expect(stored.equals(TwinSecretFormat.encode(nonce, ciphertext)), "encode is deterministic");
    }

    private static void base64() {
        byte[] sample = new byte[]{0x00, 0x01, 0x02, 0x7f, (byte) 0x80, (byte) 0xff, 0x41};
        String encoded = TwinSecretFormat.androidFreeBase64(sample);
        byte[] decoded = TwinSecretFormat.androidFreeBase64Decode(encoded);
        expect(Arrays.equals(decoded, sample), "base64 round trip");

        // Padding cases: 1 and 2 trailing bytes.
        expect(TwinSecretFormat.androidFreeBase64(new byte[]{1}).endsWith("=="), "one byte pads twice");
        expect(TwinSecretFormat.androidFreeBase64(new byte[]{1, 2}).endsWith("="), "two bytes pad once");
        expect(TwinSecretFormat.androidFreeBase64Decode("TQ==")[0] == 'M', "decode single byte");
    }

    private static void rejections() {
        byte[] goodNonce = new byte[TwinSecretFormat.NONCE_LENGTH];
        byte[] goodCipher = new byte[TwinSecretFormat.TAG_LENGTH];

        expectThrows(() -> TwinSecretFormat.encode(null, goodCipher), "null nonce");
        expectThrows(() -> TwinSecretFormat.encode(new byte[8], goodCipher), "short nonce");
        expectThrows(() -> TwinSecretFormat.encode(goodNonce, null), "null ciphertext");
        expectThrows(() -> TwinSecretFormat.encode(goodNonce, new byte[8]), "ciphertext shorter than tag");

        expectThrows(() -> TwinSecretFormat.decode(null), "null stored");
        expectThrows(() -> TwinSecretFormat.decode("abc"), "missing prefix");
        expectThrows(() -> TwinSecretFormat.decode("v2:AAAA"), "wrong version");
        expectThrows(() -> TwinSecretFormat.decode("v1:not base64!"), "invalid base64");
        expectThrows(() -> TwinSecretFormat.decode("v1:AAAA"), "envelope too short");
        expect(!TwinSecretFormat.looksValid("v1:AAAA"), "looksValid rejects short envelope");
    }

    private static void expect(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static void expectThrows(Runnable action, String what) {
        try {
            action.run();
            throw new AssertionError("accepted invalid input: " + what);
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }
}