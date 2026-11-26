package io.twoyi.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GuestDataWipeTest {
    public static void main(String[] args) throws IOException {
        Path base = Files.createTempDirectory("wipe-test-");
        try {
            Path data = base.resolve("data");
            Files.createDirectories(data);
            Path rootfs = data.resolve("rootfs");
            Files.createDirectories(rootfs.resolve("data/sub"));
            Files.createDirectories(rootfs.resolve("sdcard"));
            Files.writeString(rootfs.resolve("data/sub/a.txt"), "x");
            Files.createDirectories(data.resolve("cache"));
            Files.writeString(data.resolve("cache/b.txt"), "y");
            Path outside = base.resolve("outside");
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("keep.txt"), "keep");

            List<String> removed = GuestDataWipe.wipe(data, rootfs, data.resolve("cache"));
            if (!removed.equals(List.of("rootfs", "cache"))) {
                throw new AssertionError("unexpected removals: " + removed);
            }
            if (Files.exists(rootfs) || Files.exists(data.resolve("cache"))) {
                throw new AssertionError("paths survived");
            }
            if (!Files.exists(outside.resolve("keep.txt"))) {
                throw new AssertionError("outside file was deleted");
            }
            if (!Files.exists(data)) throw new AssertionError("data dir itself was removed");

            rejects(() -> GuestDataWipe.wipe(data, outside));
            rejects(() -> GuestDataWipe.wipe(data, data));
            rejects(() -> GuestDataWipe.wipe(null, rootfs));
            // An absent root means nothing to remove: the action is idempotent.
            if (!GuestDataWipe.wipe(data, base.resolve("ghost")).isEmpty()) {
                throw new AssertionError("absent root reported as removed");
            }

            // A slot tree lives one level deeper and must still be removable.
            Path slot = data.resolve("slots").resolve("1");
            Files.createDirectories(slot.resolve("rootfs/system"));
            Files.writeString(slot.resolve("rootfs/init"), "x");
            List<String> slotRemoved = GuestDataWipe.wipe(data, slot.resolve("rootfs"));
            if (!slotRemoved.equals(List.of("rootfs")) || Files.exists(slot.resolve("rootfs"))) {
                throw new AssertionError("nested slot rootfs was not removed: " + slotRemoved);
            }
            if (!Files.exists(slot)) throw new AssertionError("slot directory itself was removed");
            // The slot container is inside the data dir, so it is a legal target.
            List<String> container = GuestDataWipe.wipe(data, data.resolve("slots"));
            if (!container.equals(List.of("slots"))) throw new AssertionError("slot container not removed: " + container);
            System.out.println("PASS: wipe stays inside the data directory and removes only guest roots");
        } finally {
            delete(base);
        }
    }

    private interface Action {
        void run() throws IOException;
    }

    private static void rejects(Action action) throws IOException {
        try {
            action.run();
            throw new AssertionError("dangerous wipe accepted");
        } catch (IOException expected) {
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
