/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Base for the per-slot guest processes. The newest build declares four
 * services, one per {@code :guestN} process, so each guest owns its own
 * renderer, sockets and ROM tree.
 *
 * These services are declared disabled: the current single-instance host still
 * serves slot 0 from the main process, and enabling a slot service before the
 * guest launch path is slot-aware would start a second renderer against the same
 * surface-less process. The slot is derived from the concrete class name, not
 * from static configuration, because a manifest value cannot differ per process.
 */
public abstract class GuestInstanceService extends Service {
    private static final String TAG = "GuestInstanceService";

    /** Slot served by this service instance, parsed from the class name. */
    protected abstract int slot();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int slot = slot();
        GuestProcessProfile.initializeForSlot(this, slot);
        Log.i(TAG, "guest slot " + slot + " service started");
        // The guest launch path is not slot-aware yet; do not pretend it runs.
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** Slot 0 guest process. */
    public static final class Slot0 extends GuestInstanceService {
        @Override protected int slot() { return 0; }
    }

    /** Slot 1 guest process. */
    public static final class Slot1 extends GuestInstanceService {
        @Override protected int slot() { return 1; }
    }

    /** Slot 2 guest process. */
    public static final class Slot2 extends GuestInstanceService {
        @Override protected int slot() { return 2; }
    }

    /** Slot 3 guest process. */
    public static final class Slot3 extends GuestInstanceService {
        @Override protected int slot() { return 3; }
    }
}
