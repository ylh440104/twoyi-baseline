/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.File;

/**
 * Base directory for one guest slot. Slot 0 keeps the legacy app data directory
 * so an existing installation keeps booting unchanged. Additional slots live in
 * {@code <data>/slots/<n>}, each with its own rootfs, log and input sockets.
 *
 * A guest cannot share one rootfs tree: the legacy input devices live inside
 * {@code rootfs/dev/input}, so two live guests would write to the same paths.
 * Each extra slot therefore needs its own installed ROM, which is why slot
 * allocation here only resolves and validates paths; installing the ROM per
 * slot is a separate step.
 */
public final class GuestSlotLayout {
    public static final String SLOT_DIRECTORY = "slots";

    private GuestSlotLayout() {}

    public static File baseDirectory(File dataDir, int slot) {
        if (dataDir == null || !dataDir.isAbsolute()) {
            throw new IllegalArgumentException("Guest data directory requires an absolute path");
        }
        if (slot < 0 || slot >= GuestSlot.SLOT_COUNT) {
            throw new IllegalArgumentException("Guest slot must be between 0 and "
                    + (GuestSlot.SLOT_COUNT - 1));
        }
        if (slot == 0) return dataDir;
        return new File(new File(dataDir, SLOT_DIRECTORY), Integer.toString(slot));
    }

    /**
     * A slot may only serve a guest once its own ROM tree exists. Checking here
     * keeps callers from starting a guest that would fail inside {@code init}.
     */
    public static boolean isReady(File dataDir, int slot) {
        File base = baseDirectory(dataDir, slot);
        File rootfs = new File(base, "rootfs");
        return rootfs.isDirectory()
                && new File(rootfs, "init").isFile()
                && new File(rootfs, "system").isDirectory();
    }

    /** Endpoints for a slot named by the process payload; absent means slot 0. */
    public static GuestEndpoints endpoints(String slotArgument, File dataDir) {
        int slot = GuestSlotSelection.resolve(slotArgument);
        return GuestEndpoints.forSlot(slot, baseDirectory(dataDir, slot));
    }

    /** Slot directory for the payload, validated the same way as endpoints. */
    public static File baseDirectory(String slotArgument, File dataDir) {
        return baseDirectory(dataDir, GuestSlotSelection.resolve(slotArgument));
    }

    public static void requireReady(File dataDir, int slot) {
        if (!isReady(dataDir, slot)) {
            throw new IllegalStateException("Guest slot " + slot
                    + " has no installed ROM in " + baseDirectory(dataDir, slot));
        }
    }
}
