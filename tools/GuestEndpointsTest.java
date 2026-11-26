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
        // Every slot keeps the ROM's compiled-in socket names; only files differ.
        for (int slot = 1; slot < 4; slot++) {
            File slotBase = new File("/data/data/io.twoyi/slots/" + slot);
            GuestEndpoints guarded = GuestEndpoints.forSlot(slot, slotBase);
            if (!guarded.getControlSocketName().equals(slot0.getControlSocketName())
                    || !guarded.getHalSocketName().equals(slot0.getHalSocketName())) {
                throw new AssertionError("slot " + slot + " renamed the ROM's socket");
            }
            if (!guarded.getRootfsDir().equals(new File(slotBase, "rootfs").getAbsolutePath())) {
                throw new AssertionError("slot " + slot + " rootfs not isolated");
            }
            if (guarded.equals(slot0)) {
                throw new AssertionError("slot " + slot + " shares files with slot 0");
            }
            // The app data directory is not a slot base for slot >= 1.
            int index = slot;
            rejects(() -> GuestEndpoints.forSlot(index, new File("/data/data/io.twoyi")));
            rejects(() -> GuestEndpoints.forSlot(index, new File("/data/data/io.twoyi/slots/9")));
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
