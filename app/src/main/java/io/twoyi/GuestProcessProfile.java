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
        int resolved = GuestSlotSelection.resolve(argument);
        GuestEndpoints derived = GuestSlotLayout.endpoints(argument, dataDir);
        slotArgument = argument;
        slot = resolved;
        endpoints = derived;
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

    private static String readSlotArgument(Context context) {
        try {
            PackageManager packages = context.getPackageManager();
            if (packages == null) return null;
            ApplicationInfo info = packages.getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = info == null ? null : info.metaData;
            Object value = metaData == null ? null : metaData.get(META_DATA_SLOT);
            return value == null ? null : value.toString();
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
