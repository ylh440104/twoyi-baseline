/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

/**
 * Resolves which guest slot a host process serves. Today every process is slot
 * 0; the multi-process split from the newest build will pass a slot index
 * through the process start payload. Parsing lives here so a malformed value
 * fails loudly instead of silently reusing slot 0 and colliding on endpoints.
 */
public final class GuestSlotSelection {
    /** Process argument declaring the served slot; absent means slot 0. */
    public static final String ARGUMENT = "io.twoyi.extra.GUEST_SLOT";

    private GuestSlotSelection() {}

    public static int resolve(String slotArgument) {
        // A missing extra means slot 0. An empty extra is a malformed payload and
        // must fail loudly instead of silently colliding with slot 0.
        if (slotArgument == null) return 0;
        String value = slotArgument.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Guest slot argument is blank");
        }
        int slot;
        try {
            slot = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Guest slot is not a number: " + slotArgument, e);
        }
        if (slot < 0 || slot >= GuestSlot.SLOT_COUNT) {
            throw new IllegalArgumentException("Guest slot out of range: " + slot);
        }
        return slot;
    }
}
