/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.io.File;

/**
 * Process-wide identity of the served guest slot. Reading it once keeps every
 * consumer (control listener, renderer, ROM paths) on the same slot instead of
 * each deriving its own paths from the app data directory.
 */
public final class GuestProcessProfile {
    /** Manifest metadata carrying the slot payload; absent means slot 0. */
    public static final String META_DATA_SLOT = "io.twoyi.GUEST_SLOT";

    private static volatile String slotArgument;
    private static volatile GuestEndpoints endpoints;
    private static volatile int slot;

    private GuestProcessProfile() {}

    public static synchronized void initialize(Context context) {
        if (endpoints != null) return;
        File dataDir = context.getApplicationContext().getDataDir();
        String argument = readSlotArgument(context);
        int resolved = resolveSlot(argument, dataDir);
        GuestEndpoints derived = GuestSlotLayout.endpoints(Integer.toString(resolved), dataDir);
        slotArgument = Integer.toString(resolved);
        slot = resolved;
        endpoints = derived;
    }

    /**
     * An explicit payload wins; otherwise the registry's active instance picks
     * the slot, which is how switching instances works for a process-wide guest.
     */
    private static int resolveSlot(String argument, File dataDir) {
        GuestInstanceRegistry registry = null;
        try {
            registry = GuestInstanceRegistry.load(
                    GuestInstanceRegistry.defaultPath(dataDir.toPath()));
        } catch (java.io.IOException unreadable) {
            // A damaged registry must not keep the app from starting slot 0.
            android.util.Log.w("GuestProcessProfile", "guest instance registry unreadable", unreadable);
        }
        return GuestStartupSlot.resolve(argument, registry);
    }

    /**
     * Bind the process to an explicit slot. A per-slot service knows its slot
     * from its own class name, which a manifest value cannot express.
     */
    public static synchronized void initializeForSlot(Context context, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= GuestSlot.SLOT_COUNT) {
            throw new IllegalArgumentException("Guest slot out of range: " + slotIndex);
        }
        File dataDir = context.getApplicationContext().getDataDir();
        String argument = Integer.toString(slotIndex);
        endpoints = GuestSlotLayout.endpoints(argument, dataDir);
        slotArgument = argument;
        slot = slotIndex;
    }

    /** Slot resolved for this process; 0 when nothing was configured. */
    public static int slot() {
        return slot;
    }

    public static String slotArgument() {
        return slotArgument;
    }

    /** Endpoints for the process, deriving slot 0 when initialization was skipped. */
    public static GuestEndpoints endpoints(File dataDir) {
        GuestEndpoints current = endpoints;
        return current != null ? current : GuestSlotLayout.endpoints(null, dataDir);
    }

    /** Directory holding the served guest tree, log and sockets. */
    public static File slotDirectory(Context context) {
        return GuestSlotLayout.baseDirectory(context.getApplicationContext().getDataDir(), slot());
    }

    /**
     * Explicit slot payload, or null when the manifest only declares the default.
     *
     * The manifest ships {@code GUEST_SLOT=0}. Treating that "0" as an explicit
     * payload would pin every process to slot 0 and make the active instance
     * unreachable, so the default value is reported as "no payload" and the
     * registry decides. Per-slot services do not use this path at all.
     */
    private static String readSlotArgument(Context context) {
        try {
            PackageManager packages = context.getPackageManager();
            if (packages == null) return null;
            ApplicationInfo info = packages.getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = info == null ? null : info.metaData;
            Object value = metaData == null ? null : metaData.get(META_DATA_SLOT);
            if (value == null) return null;
            String text = value.toString().trim();
            return text.isEmpty() || text.equals("0") ? null : text;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Test seam: reset between cases. */
    static synchronized void reset() {
        slotArgument = null;
        endpoints = null;
        slot = 0;
    }
}
