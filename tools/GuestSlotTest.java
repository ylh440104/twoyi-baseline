package io.twoyi;

import java.io.File;

public final class GuestSlotTest {
    public static void main(String[] args) {
        File dataDir = new File("/data/data/io.twoyi");
        GuestSlot first = GuestSlot.of(0, "twin-a", dataDir);
        GuestSlot second = GuestSlot.of(3, "twin-b", new File(dataDir, "slots/3"));
        if (first.getProcessName().equals(second.getProcessName())) throw new AssertionError("shared process");
        if (first.getEndpoints().getRootfsDir().equals(second.getEndpoints().getRootfsDir())) {
            throw new AssertionError("shared rootfs");
        }
        if (first.getEndpoints().getTouchSocketPath().equals(second.getEndpoints().getTouchSocketPath())) {
            throw new AssertionError("shared touch socket");
        }
        GuestSlot.requireDistinct(first, second);

        rejects(() -> GuestSlot.of(4, "x", new File("/d")));
        rejects(() -> GuestSlot.of(-1, "x", new File("/d")));
        rejects(() -> GuestSlot.of(0, "", new File("/d")));
        rejects(() -> GuestSlot.of(0, "x", new File("relative")));
        // Slot >= 1 must live in <data>/slots/<n>; anything else is a layout bug.
        rejects(() -> GuestSlot.of(2, "x", new File(dataDir, "slots/shared")));
        rejects(() -> GuestSlot.of(2, "x", new File(dataDir, "slots/3")));
        rejects(() -> GuestSlot.of(2, "x", dataDir));
        // Same index twice is always a conflict, even before directory checks.
        rejects(() -> GuestSlot.requireDistinct(first, GuestSlot.of(0, "other", dataDir)));
        // Two slots on the same guest directory are rejected by the layout guard.
        rejects(() -> GuestSlot.requireDistinct(
                first, GuestSlot.of(2, "b", new File(dataDir, "slots/shared"))));
        // Valid neighbours stay allowed.
        GuestSlot.requireDistinct(first, GuestSlot.of(2, "b", new File(dataDir, "slots/2")));
        System.out.println("PASS: slot paths, process names and endpoint isolation");
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid slot accepted");
        } catch (IllegalArgumentException expected) {
        }
    }
}
