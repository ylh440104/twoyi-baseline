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
