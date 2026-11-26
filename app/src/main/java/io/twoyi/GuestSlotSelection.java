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

    /**
     * Slot served by a {@code GuestInstanceService$SlotN} subclass. A manifest
     * value cannot differ per process, so the concrete class name is the only
     * per-process source of truth.
     */
    public static int fromServiceClass(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Service class name is required");
        }
        String name = className.trim();
        int marker = name.lastIndexOf("$Slot");
        if (marker < 0 || marker + 5 >= name.length()) {
            throw new IllegalArgumentException("Not a per-slot guest service: " + name);
        }
        String suffix = name.substring(marker + 5);
        int slot;
        try {
            slot = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed slot suffix in " + name, e);
        }
        if (slot < 0 || slot >= GuestSlot.SLOT_COUNT) {
            throw new IllegalArgumentException("Guest slot out of range in " + name + ": " + slot);
        }
        // Reject Slot00/Slot+1 style suffixes that parse but do not name the slot.
        if (!Integer.toString(slot).equals(suffix)) {
            throw new IllegalArgumentException("Non-canonical slot suffix in " + name);
        }
        return slot;
    }

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
