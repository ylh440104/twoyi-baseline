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
        if (slotArgument == null || slotArgument.isEmpty()) return 0;
        int slot;
        try {
            slot = Integer.parseInt(slotArgument.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Guest slot is not a number: " + slotArgument, e);
        }
        if (slot < 0 || slot >= GuestSlot.SLOT_COUNT) {
            throw new IllegalArgumentException("Guest slot out of range: " + slot);
        }
        return slot;
    }
}
