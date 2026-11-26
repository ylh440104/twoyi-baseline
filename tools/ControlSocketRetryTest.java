package io.twoyi;

public final class ControlSocketRetryTest {
    public static void main(String[] args) {
        ControlSocketRetry retry = new ControlSocketRetry();
        long total = 0;
        int attempts = 0;
        long delay;
        while ((delay = retry.nextDelayMillis()) >= 0) {
            attempts++;
            total += delay;
            if (attempts > ControlSocketRetry.MAX_ATTEMPTS) throw new AssertionError("budget exceeded");
        }
        if (attempts != ControlSocketRetry.MAX_ATTEMPTS) {
            throw new AssertionError("expected " + ControlSocketRetry.MAX_ATTEMPTS + " attempts, got " + attempts);
        }
        if (retry.attempts() != attempts) throw new AssertionError("attempt counter wrong");
        if (!retry.exhausted()) throw new AssertionError("exhausted not reported");
        if (retry.nextDelayMillis() != -1) throw new AssertionError("retry continued past the budget");
        if (total <= 0) throw new AssertionError("no backoff applied");

        if (!ControlSocketRetry.looksLikeAddressInUse("bind failed: EADDRINUSE (Address already in use)")) {
            throw new AssertionError("EADDRINUSE not recognised");
        }
        if (!ControlSocketRetry.looksLikeAddressInUse("Address already in use")) {
            throw new AssertionError("plain message not recognised");
        }
        if (ControlSocketRetry.looksLikeAddressInUse("Permission denied")) {
            throw new AssertionError("unrelated error treated as a collision");
        }
        if (ControlSocketRetry.looksLikeAddressInUse(null)) {
            throw new AssertionError("null message treated as a collision");
        }
        System.out.println("PASS: control socket retries are bounded and collisions recognised");
    }
}
