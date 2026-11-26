/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

/**
 * Decides which slot a freshly started host process should serve.
 *
 * The renderer, the input servers and the guest init are process-wide
 * singletons, so an instance can only be "switched" by changing which slot the
 * next process binds. Order of precedence:
 *
 * 1. An explicit slot payload (per-slot service process) always wins.
 * 2. Otherwise the registry's active instance decides, if it still exists.
 * 3. Otherwise slot 0, the legacy single-instance layout.
 */
public final class GuestStartupSlot {
    private GuestStartupSlot() {}

    public static int resolve(String slotArgument, GuestInstanceRegistry registry) {
        if (slotArgument != null) {
            // A present payload is authoritative, including a malformed one:
            // GuestSlotSelection rejects it instead of silently using slot 0.
            return GuestSlotSelection.resolve(slotArgument);
        }
        String active = registry == null ? null : registry.active();
        if (active == null) return 0;
        try {
            return registry.slotOf(active);
        } catch (IllegalArgumentException missing) {
            // A stale active id must not point the process at an unknown slot.
            return 0;
        }
    }

    /** Convenience for callers that already know the payload is textual slot input. */
    public static int resolveActiveOrZero(GuestInstanceRegistry registry) {
        return resolve(null, registry);
    }
}