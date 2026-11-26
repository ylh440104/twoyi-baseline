/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.File;

/**
 * Decides whether a registered instance may start. Instance management needs
 * both halves: the registry says which slot an instance owns, and the slot
 * layout says whether that slot actually holds a bootable ROM. Checking them
 * together keeps a start request from reaching {@code init} on an empty tree.
 */
public final class GuestInstanceGate {
    public enum Decision { STARTABLE, UNKNOWN_INSTANCE, NO_ROM }

    private GuestInstanceGate() {}

    public static Decision evaluate(File dataDir, GuestInstanceRegistry registry, String instanceId) {
        if (dataDir == null || !dataDir.isAbsolute()) {
            throw new IllegalArgumentException("Guest data directory must be absolute");
        }
        if (registry == null) throw new IllegalArgumentException("Instance registry is required");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance id is required");
        }
        int slot;
        try {
            slot = registry.slotOf(instanceId);
        } catch (IllegalArgumentException unknown) {
            return Decision.UNKNOWN_INSTANCE;
        }
        return GuestSlotLayout.isReady(dataDir, slot) ? Decision.STARTABLE : Decision.NO_ROM;
    }

    /** Throwing form for call sites that must not start a guest on a bad slot. */
    public static void requireStartable(File dataDir, GuestInstanceRegistry registry, String instanceId) {
        Decision decision = evaluate(dataDir, registry, instanceId);
        switch (decision) {
            case STARTABLE:
                return;
            case UNKNOWN_INSTANCE:
                throw new IllegalStateException("Instance is not registered: " + instanceId);
            default:
                throw new IllegalStateException("Instance has no installed ROM: " + instanceId);
        }
    }
}