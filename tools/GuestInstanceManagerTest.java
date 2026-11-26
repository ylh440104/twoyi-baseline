package io.twoyi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GuestInstanceManagerTest {
    public static void main(String[] args) throws IOException {
        Path base = Files.createTempDirectory("instance-manager-");
        try {
            Path data = base.resolve("data");
            Files.createDirectories(data);
            Path stateFile = GuestInstanceRegistry.defaultPath(data);

            GuestInstanceRegistry registry = GuestInstanceRegistry.load(stateFile);
            int slot = GuestInstanceManager.create(data, registry, "twin-a");
            if (slot != 0) throw new AssertionError("first instance did not get slot 0");
            if (!Files.isRegularFile(stateFile)) throw new AssertionError("create did not persist");

            // A fresh process must see the reservation.
            GuestInstanceRegistry reloaded = GuestInstanceRegistry.load(stateFile);
            if (reloaded.slotOf("twin-a") != 0) throw new AssertionError("reservation lost");

            // Slot 0 ROM tree on the data dir.
            Path rootfs = data.resolve("rootfs");
            Files.createDirectories(rootfs.resolve("system"));
            Files.writeString(rootfs.resolve("init"), "x");

            // Releasing without wiping keeps the ROM but frees the slot.
            List<String> kept = GuestInstanceManager.release(data, reloaded, "twin-a", false);
            if (!kept.isEmpty()) throw new AssertionError("release without wipe removed data");
            if (!Files.exists(rootfs.resolve("init"))) throw new AssertionError("ROM removed without wipe");
            if (reloaded.size() != 0) throw new AssertionError("slot not freed");

            // Recreate, then release with wipe: the ROM tree must disappear.
            int again = GuestInstanceManager.create(data, reloaded, "twin-b");
            if (again != 0) throw new AssertionError("released slot was not reused");
            List<String> removed = GuestInstanceManager.release(data, reloaded, "twin-b", true);
            if (!removed.equals(List.of("rootfs"))) throw new AssertionError("wipe result: " + removed);
            if (Files.exists(rootfs)) throw new AssertionError("ROOTFS survived a wiping release");

            // Keep slot 0 occupied so the next instance really lands in slots/1.
            if (GuestInstanceManager.create(data, reloaded, "twin-keep") != 0) {
                throw new AssertionError("slot 0 was not reused after a wiping release");
            }
            // A nested slot only loses its own subtree, not the shared slots dir.
            int nestedSlot = GuestInstanceManager.create(data, reloaded, "twin-c");
            if (nestedSlot != 1) throw new AssertionError("nested slot not allocated");
            Path nestedRootfs = data.resolve("slots").resolve("1").resolve("rootfs");
            Files.createDirectories(nestedRootfs.resolve("system"));
            Files.writeString(nestedRootfs.resolve("init"), "x");
            List<String> nestedRemoved = GuestInstanceManager.release(data, reloaded, "twin-c", true);
            if (!nestedRemoved.equals(List.of("rootfs"))) throw new AssertionError("nested wipe: " + nestedRemoved);
            if (Files.exists(nestedRootfs)) throw new AssertionError("nested ROOTFS survived");
            if (!Files.isDirectory(data.resolve("slots"))) throw new AssertionError("slots container removed");

            if (reloaded.slotOf("twin-keep") != 0) throw new AssertionError("kept instance moved");

            // The Android-side cleaner drops a stale transaction before wiping.
            int cleanedSlot = GuestInstanceManager.create(data, reloaded, "twin-clean");
            File slotBase = GuestSlotLayout.baseDirectory(data.toFile(), cleanedSlot);
            Files.createDirectories(new File(slotBase, "rootfs/system").toPath());
            Files.write(new File(slotBase, "rootfs/init").toPath(), new byte[]{1});
            boolean[] cleaned = {false};
            GuestInstanceManager.release(data, reloaded, "twin-clean", true,
                    base -> cleaned[0] = base.equals(slotBase));
            if (!cleaned[0]) throw new AssertionError("slot cleaner was not invoked");
            if (Files.exists(new File(slotBase, "rootfs").toPath())) {
                throw new AssertionError("slot rootfs survived a wiping release");
            }

            // Unknown instances are a no-op and never wipe a guessed slot.
            if (!GuestInstanceManager.release(data, reloaded, "ghost", true).isEmpty()) {
                throw new AssertionError("unknown release wiped something");
            }

            // A running instance cannot be released.
            GuestInstanceManager.create(data, reloaded, "twin-d");
            reloaded.setState("twin-d", GuestInstanceRegistry.State.RUNNING);
            rejects(data, reloaded, "twin-d");

            rejectsArgs(null, reloaded, "twin-d");
            rejectsArgs(data, null, "twin-d");
            System.out.println("PASS: instance manager allocates, frees and wipes exactly one slot");
        } finally {
            deleteTree(base);
        }
    }

    private static void rejects(Path data, GuestInstanceRegistry registry, String id) {
        try {
            GuestInstanceManager.release(data, registry, id, true);
            throw new AssertionError("running instance released: " + id);
        } catch (IllegalStateException expected) {
        } catch (IOException failure) {
            throw new AssertionError("unexpected IO failure", failure);
        }
    }

    private static void rejectsArgs(Path data, GuestInstanceRegistry registry, String id) {
        try {
            GuestInstanceManager.create(data, registry, id);
            throw new AssertionError("invalid create arguments accepted");
        } catch (IllegalArgumentException expected) {
        } catch (IOException failure) {
            throw new AssertionError("unexpected IO failure", failure);
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
