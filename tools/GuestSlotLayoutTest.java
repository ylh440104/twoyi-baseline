package io.twoyi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GuestSlotLayoutTest {
    public static void main(String[] args) throws IOException {
        Path base = Files.createTempDirectory("slot-layout-");
        try {
            File data = base.toFile();
            // Slot 0 must stay the legacy data directory so an existing install keeps booting.
            if (!GuestSlotLayout.baseDirectory(data, 0).equals(data)) {
                throw new AssertionError("slot 0 moved");
            }
            for (int slot = 1; slot < GuestSlot.SLOT_COUNT; slot++) {
                File expected = new File(new File(data, "slots"), Integer.toString(slot));
                if (!GuestSlotLayout.baseDirectory(data, slot).equals(expected)) {
                    throw new AssertionError("slot " + slot + " path wrong");
                }
            }
            if (GuestSlotLayout.isReady(data, 0)) throw new AssertionError("empty slot reported ready");

            File rootfs = new File(GuestSlotLayout.baseDirectory(data, 2), "rootfs");
            Files.createDirectories(rootfs.toPath());
            Files.createFile(new File(rootfs, "init").toPath());
            if (GuestSlotLayout.isReady(data, 2)) throw new AssertionError("slot without system reported ready");
            Files.createDirectories(new File(rootfs, "system").toPath());
            if (!GuestSlotLayout.isReady(data, 2)) throw new AssertionError("complete slot not ready");
            GuestSlotLayout.requireReady(data, 2);
            rejects(data, 0);

            // An init that is a directory cannot be executed.
            Files.delete(new File(rootfs, "init").toPath());
            Files.createDirectories(new File(rootfs, "init").toPath());
            if (GuestSlotLayout.isReady(data, 2)) throw new AssertionError("init directory accepted");

            rejectsSlot(data, -1);
            rejectsSlot(data, 4);
            rejectsBase(new File("relative"));
            if (GuestSlotLayout.isReady(new File("/nonexistent-guard"), 3)) {
                throw new AssertionError("missing data dir reported ready");
            }
            GuestSlot legacy = GuestSlot.legacy(data);
            if (legacy.getIndex() != 0 || !legacy.getEndpoints().getRootfsDir()
                    .equals(new File(data, "rootfs").getAbsolutePath())) {
                throw new AssertionError("legacy slot not on the data dir");
            }
            GuestSlot third = GuestSlot.forDataDir(2, "twin-c", data);
            if (!third.getEndpoints().getRootfsDir().equals(new File(new File(data, "slots/2"), "rootfs").getAbsolutePath())) {
                throw new AssertionError("slot 2 rootfs not resolved through the layout");
            }
            if (third.getEndpoints().getControlSocketName().equals(legacy.getEndpoints().getControlSocketName())) {
                throw new AssertionError("slots share a control socket name");
            }
            // The payload-driven overloads must agree with the explicit slot path.
            if (!GuestSlotLayout.baseDirectory(null, data).equals(data)
                    || !GuestSlotLayout.baseDirectory(" 0 ", data).equals(data)) {
                throw new AssertionError("payload slot 0 not on the data dir");
            }
            File slot3 = GuestSlotLayout.baseDirectory("3", data);
            if (!slot3.equals(GuestSlotLayout.baseDirectory(data, 3))) {
                throw new AssertionError("payload slot 3 path mismatch");
            }
            GuestEndpoints fromPayload = GuestSlotLayout.endpoints("3", data);
            GuestEndpoints explicit = GuestEndpoints.forSlot(3, slot3);
            if (!fromPayload.equals(explicit)) throw new AssertionError("payload endpoints mismatch");
            if (!fromPayload.getRootfsDir().equals(new File(slot3, "rootfs").getAbsolutePath())) {
                throw new AssertionError("payload rootfs not under the slot dir");
            }
            rejectsPayload(data, "");
            rejectsPayload(data, "abc");
            // A fresh slot has no directory yet; a ROM transaction cannot create
            // its parent, so the layout must be able to prepare one.
            Path fresh = base.resolve("fresh-data");
            Files.createDirectories(fresh.resolve("slots"));
            File freshBase = GuestSlotLayout.ensureBaseDirectory(fresh.toFile(), 1);
            if (!Files.isDirectory(freshBase.toPath())) {
                throw new AssertionError("ensureBaseDirectory did not create the slot");
            }
            File slotZero = GuestSlotLayout.ensureBaseDirectory(fresh.toFile(), 0);
            if (!slotZero.equals(fresh.toFile())) {
                throw new AssertionError("slot 0 base moved");
            }
            // Idempotent on an existing directory.
            if (!GuestSlotLayout.ensureBaseDirectory(fresh.toFile(), 1).equals(freshBase)) {
                throw new AssertionError("ensureBaseDirectory changed the slot path");
            }
            // A file where the slot should be must be refused, not replaced.
            Path blocked = base.resolve("blocked-data");
            Files.createDirectories(blocked.resolve("slots"));
            Files.writeString(blocked.resolve("slots").resolve("2"), "not a directory");
            try {
                GuestSlotLayout.ensureBaseDirectory(blocked.toFile(), 2);
                throw new AssertionError("a file was accepted as a slot directory");
            } catch (java.io.IOException expected) {
            }
            System.out.println("PASS: slot layout keeps slot 0 legacy and requires a complete ROM per slot");
        } finally {
            deleteTree(base);
        }
    }

    private static void rejectsPayload(File data, String argument) {
        try {
            GuestSlotLayout.baseDirectory(argument, data);
            throw new AssertionError("invalid slot payload accepted: " + argument);
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void rejects(File data, int slot) {
        try {
            GuestSlotLayout.requireReady(data, slot);
            throw new AssertionError("unready slot accepted");
        } catch (IllegalStateException expected) {
        }
    }

    private static void rejectsSlot(File data, int slot) {
        try {
            GuestSlotLayout.baseDirectory(data, slot);
            throw new AssertionError("out-of-range slot accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void rejectsBase(File data) {
        try {
            GuestSlotLayout.baseDirectory(data, 0);
            throw new AssertionError("relative data dir accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
