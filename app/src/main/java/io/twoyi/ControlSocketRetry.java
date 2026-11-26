/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

/**
 * Bounded retry policy for binding the control socket.
 *
 * The listener name is shared by every guest process (it is compiled into the
 * shipped ROM), so a second guest cannot bind it. Retrying forever would spin a
 * thread and flood the log while the guest can never connect; instead the policy
 * gives up and lets the caller report that another guest owns the socket.
 */
public final class ControlSocketRetry {
    public static final int MAX_ATTEMPTS = 3;
    private static final long FIRST_DELAY_MS = 250;

    private int attempts;

    /** Delay before the next bind attempt, or -1 when the attempt budget is spent. */
    public long nextDelayMillis() {
        if (attempts >= MAX_ATTEMPTS) return -1;
        long delay = FIRST_DELAY_MS << attempts;
        attempts++;
        return delay;
    }

    public int attempts() {
        return attempts;
    }

    public boolean exhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    static boolean looksLikeAddressInUse(String message) {
        if (message == null) return false;
        String text = message.toLowerCase(java.util.Locale.US);
        return text.contains("address already in use") || text.contains("eaddrinuse");
    }
}