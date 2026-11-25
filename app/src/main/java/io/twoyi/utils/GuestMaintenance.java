/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

/** Serializes ROM preparation, APK import and shutdown within one host process. */
public final class GuestMaintenance {
    static final GuestMaintenance SESSION = new GuestMaintenance();

    private enum State { IDLE, PREPARING_ROM, INSTALLING, STOPPING, STOP_FAILED }

    private State state = State.IDLE;

    GuestMaintenance() {}

    public static boolean beginSessionRomPreparation() {
        return SESSION.beginRomPreparation();
    }

    public static void endSessionRomPreparation() {
        SESSION.endRomPreparation();
    }

    synchronized boolean beginRomPreparation() {
        if (state != State.IDLE) return false;
        state = State.PREPARING_ROM;
        return true;
    }

    synchronized void endRomPreparation() {
        if (state == State.PREPARING_ROM) state = State.IDLE;
    }

    synchronized boolean beginInstall() {
        if (state != State.IDLE) return false;
        state = State.INSTALLING;
        return true;
    }

    synchronized void endInstall() {
        if (state == State.INSTALLING) state = State.IDLE;
    }

    synchronized boolean beginShutdown() {
        if (state != State.IDLE && state != State.STOP_FAILED) return false;
        state = State.STOPPING;
        return true;
    }

    synchronized void shutdownFailed() {
        if (state == State.STOPPING) state = State.STOP_FAILED;
    }
}
