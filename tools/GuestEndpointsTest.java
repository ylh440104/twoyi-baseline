package io.twoyi;

import java.io.File;

public final class GuestEndpointsTest {
    public static void main(String[] args) {
        GuestEndpoints legacy = GuestEndpoints.forDataDir(new File("/data/data/io.twoyi"));
        if (!legacy.getTouchSocketPath().equals("/data/data/io.twoyi/rootfs/dev/input/touch")
                || !legacy.getKeySocketPath().equals("/data/data/io.twoyi/rootfs/dev/input/key0")
                || !legacy.getLogFile().equals("/data/data/io.twoyi/log.txt")) {
            throw new AssertionError("Legacy paths changed");
        }
        // 79 encoded bytes plus the terminator exactly fits physical_location.
        String prefix = "/" + "界".repeat(26);
        new GuestEndpoints("/root", "/log", prefix, "/keys", "ctl", "hal");
        rejects(() -> new GuestEndpoints("/root", "/log", prefix + "x", "/keys", "ctl", "hal"));
        rejects(() -> new GuestEndpoints("/root", "/log", "/input/touch", "/input/./touch", "ctl", "hal"));
        rejects(() -> new GuestEndpoints("/root", "/input/../log", "/log", "/keys", "ctl", "hal"));
        rejects(() -> new GuestEndpoints("/root", "/log", "/touch", "/keys", "ctl", "ctl"));
        rejects(() -> new GuestEndpoints("/root", "/log", "/touch", "/keys", "ctl\0ignored", "hal"));

        // Abstract names have 107 bytes available after the namespace prefix.
        String fullName = "界".repeat(35) + "ab";
        new GuestEndpoints("/root", "/log", "/touch", "/keys", fullName, "hal");
        rejects(() -> new GuestEndpoints("/root", "/log", "/touch", "/keys", fullName + "c", "hal"));

        // Slot 0 must stay byte-identical to the legacy names the shipped ROM expects.
        GuestEndpoints slot0 = GuestEndpoints.forSlot(0, new File("/data/data/io.twoyi"));
        if (!slot0.getControlSocketName().equals("TWOYI_SOCK")
                || !slot0.getHalSocketName().equals("twoyi_hal")) {
            throw new AssertionError("slot 0 socket names changed");
        }
        for (int slot = 1; slot < 4; slot++) {
            GuestEndpoints guarded = GuestEndpoints.forSlot(slot, new File("/data/data/io.twoyi"));
            if (guarded.getControlSocketName().equals("TWOYI_SOCK")
                    || guarded.getHalSocketName().equals("twoyi_hal")) {
                throw new AssertionError("slot " + slot + " reuses the legacy socket name");
            }
            if (!guarded.getControlSocketName().equals("TWOYI_SOCK" + slot)
                    || !guarded.getHalSocketName().equals("twoyi_hal" + slot)) {
                throw new AssertionError("slot " + slot + " socket name unexpected");
            }
        }
        rejects(() -> GuestEndpoints.forSlot(-1, new File("/data/data/io.twoyi")));
        rejects(() -> GuestEndpoints.forSlot(4, new File("/data/data/io.twoyi")));
    }

    private static void rejects(Runnable construct) {
        try {
            construct.run();
            throw new AssertionError("Invalid endpoints accepted");
        } catch (IllegalArgumentException expected) {
            // Rejected before starting either listener.
        }
    }
}
