/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import java.io.IOException;
import java.nio.file.Path;

import io.twoyi.utils.GuestMaintenance;
import io.twoyi.utils.RomManager;

/**
 * Installs the bundled ROM into one registered slot. Extraction is the same
 * archive transaction the main path uses, only pointed at that slot's directory,
 * so preparing a second instance never touches the running slot.
 *
 * Runs off the UI thread and refuses to start while a guest is running or another
 * ROM operation is in flight.
 */
public final class GuestInstanceInstaller {
    private GuestInstanceInstaller() {}

    /**
     * @return true when the slot now holds a bootable ROM
     */
    public static boolean installBundledRom(Context context, String instanceId) throws IOException {
        Context app = context.getApplicationContext();
        if (TwoyiStatusManager.getInstance().isStarted()) {
            throw new IllegalStateException(app.getString(R.string.guest_maintenance_busy));
        }
        Path dataDir = app.getDataDir().toPath();
        GuestInstanceRegistry registry = GuestInstanceRegistry.load(
                GuestInstanceRegistry.defaultPath(dataDir));
        int slot = registry.slotOf(instanceId);
        if (GuestSlotLayout.isReady(dataDir.toFile(), slot)) {
            registry.setState(instanceId, GuestInstanceRegistry.State.READY);
            registry.save();
            return true;
        }
        if (!GuestMaintenance.beginSessionRomPreparation()) {
            throw new IllegalStateException(app.getString(R.string.guest_maintenance_busy));
        }
        try {
            RomManager.installRootfsForSlot(app, slot, false);
        } finally {
            GuestMaintenance.endSessionRomPreparation();
        }
        boolean ready = GuestSlotLayout.isReady(dataDir.toFile(), slot);
        // Reload: a concurrent release must not be resurrected by a stale snapshot.
        GuestInstanceRegistry current = GuestInstanceRegistry.load(
                GuestInstanceRegistry.defaultPath(dataDir));
        try {
            current.setState(instanceId, ready
                    ? GuestInstanceRegistry.State.READY
                    : GuestInstanceRegistry.State.RESERVED);
            current.save();
        } catch (IllegalArgumentException released) {
            // The instance was released while extracting; its slot is already gone.
        }
        return ready;
    }
}