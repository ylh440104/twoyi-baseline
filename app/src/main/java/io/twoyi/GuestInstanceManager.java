/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.IOException;
import java.nio.file.Path;

import io.twoyi.utils.GuestDataWipe;

/**
 * Create and release guest instances. Creating reserves a slot in the registry;
 * releasing frees it and can drop that slot's ROM tree so a later install starts
 * clean. Nothing here launches a guest: {@link GuestInstanceGate} decides
 * whether a registered instance may start.
 */
public final class GuestInstanceManager {
    /** Android-only hook: dropping an incomplete ROM transaction before a wipe. */
    public interface SlotCleaner {
        void abandonTransaction(java.io.File slotBase) throws IOException;
    }

    private GuestInstanceManager() {}

    /** Reserve a free slot for the instance and persist the registry. */
    public static int create(Path dataDir, GuestInstanceRegistry registry, String instanceId)
            throws IOException {
        requireDataDir(dataDir);
        if (registry == null) throw new IllegalArgumentException("Instance registry is required");
        int slot = registry.allocate(instanceId);
        try {
            registry.save();
        } catch (IOException | RuntimeException failure) {
            // Keep memory and disk in agreement: a failed save must not leave a
            // reservation that the next process start would not see.
            registry.release(instanceId);
            throw failure;
        }
        return slot;
    }

    /**
     * Release an instance. With {@code wipeData} the slot's ROM tree is deleted,
     * so the slot returns to "no ROM installed" instead of looking bootable.
     *
     * @return the removed slot subtree names, empty when nothing was wiped
     */
    public static java.util.List<String> release(
            Path dataDir, GuestInstanceRegistry registry, String instanceId, boolean wipeData)
            throws IOException {
        return release(dataDir, registry, instanceId, wipeData, null);
    }

    public static java.util.List<String> release(
            Path dataDir, GuestInstanceRegistry registry, String instanceId, boolean wipeData,
            SlotCleaner cleaner) throws IOException {
        requireDataDir(dataDir);
        if (registry == null) throw new IllegalArgumentException("Instance registry is required");
        int slot;
        try {
            slot = registry.slotOf(instanceId);
        } catch (IllegalArgumentException unknown) {
            // Releasing an unknown instance changes nothing and wipes nothing.
            return java.util.Collections.emptyList();
        }
        if (registry.stateOf(instanceId) == GuestInstanceRegistry.State.RUNNING) {
            throw new IllegalStateException("Instance is running: " + instanceId);
        }
        java.util.List<String> removed = java.util.Collections.emptyList();
        if (wipeData) {
            java.io.File base = GuestSlotLayout.baseDirectory(dataDir.toFile(), slot);
            // A cancelled install leaves a journal that would block the next one.
            if (cleaner != null) cleaner.abandonTransaction(base);
            removed = GuestDataWipe.wipe(dataDir, base.toPath().resolve("rootfs"));
        }
        registry.release(instanceId);
        registry.save();
        return removed;
    }

    private static void requireDataDir(Path dataDir) {
        if (dataDir == null || !dataDir.isAbsolute()) {
            throw new IllegalArgumentException("Guest data directory must be absolute");
        }
    }
}