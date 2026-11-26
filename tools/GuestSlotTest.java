package io.twoyi;

import java.io.File;

public final class GuestSlotTest {
    public static void main(String[] args) {
        GuestSlot first = GuestSlot.of(0, "twin-a", new File("/data/data/io.twoyi/slots/0"));
        GuestSlot second = GuestSlot.of(3, "twin-b", new File("/data/data/io.twoyi/slots/3"));
        if (first.getProcessName().equals(second.getProcessName())) throw new AssertionError("shared process");
        if (first.getEndpoints().getRootfsDir().equals(second.getEndpoints().getRootfsDir())) throw new AssertionError("shared rootfs");
        if (first.getEndpoints().getTouchSocketPath().equals(second.getEndpoints().getTouchSocketPath())) throw new AssertionError("shared touch socket");
        GuestSlot.requireDistinct(first, second);
        rejects(() -> GuestSlot.of(4, "x", new File("/d")));
        rejects(() -> GuestSlot.of(-1, "x", new File("/d")));
        rejects(() -> GuestSlot.of(0, "", new File("/d")));
        rejects(() -> GuestSlot.of(0, "x", new File("relative")));
        rejects(() -> GuestSlot.requireDistinct(first, GuestSlot.of(0, "other", new File("/data/data/io.twoyi/slots/9"))));
        rejects(() -> GuestSlot.requireDistinct(first, GuestSlot.of(1, "other", new File("/data/data/io.twoyi/slots/0"))));
        rejects(() -> GuestSlot.requireDistinct(first, GuestSlot.of(0, "twin-a", new File("/data/data/io.twoyi/slots/0"))));
        // Distinct slots sharing one guest directory must be rejected even though
        // their abstract socket names differ by slot index.
        rejects(() -> GuestSlot.requireDistinct(
                GuestSlot.of(0, "a", new File("/data/data/io.twoyi/slots/shared")),
                GuestSlot.of(2, "b", new File("/data/data/io.twoyi/slots/shared"))));
        GuestSlot.requireDistinct(
                GuestSlot.of(0, "a", new File("/data/data/io.twoyi/slots/a")),
                GuestSlot.of(2, "b", new File("/data/data/io.twoyi/slots/b")));
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
