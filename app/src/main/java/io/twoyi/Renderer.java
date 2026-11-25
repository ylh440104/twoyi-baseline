/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Renderer {

    static {
        System.loadLibrary("twoyi");
    }

    public static native void init(Surface surface, String loader, String root, String log,
                                   String touch, String key, float xdpi, float ydpi, int fps);

    public static native void resetWindow(Surface surface, int top, int left, int width, int height);

    public static native void removeWindow(Surface surface);

    public static native void handleTouch(MotionEvent event, int width, int height);

    static native void cancelTouch();

    public static native void sendKeycode(int keycode);

    /** Nonzero after spawn failure or observed init exit. */
    public static native int guestFailed();

    /** Stops the current init process group. Call from a worker thread. */
    public static int stopGuest() {
        int result = stopGuestNative();
        if (result != 0) TwoyiStatusManager.getInstance().markStopped();
        return result;
    }

    private static native int stopGuestNative();

    /** PID of the tracked guest init, or zero if no child is tracked. */
    static native int guestInitPid();

    /** Called from native when guest init exits or fails to start. */
    @SuppressWarnings("unused")
    public static void onGuestExited(int exitCode) {
        TwoyiStatusManager.getInstance().markFailed();
    }
}
