package io.twoyi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GuestInstanceGateTest {
    public static void main(String[] args) throws IOException {
        Path base = Files.createTempDirectory("instance-gate-");
        try {
            Path data = base.resolve("data");
            Files.createDirectories(data);
            GuestInstanceRegistry registry = GuestInstanceRegistry.open(
                    GuestInstanceRegistry.defaultPath(data));

            // Unknown instance must never be treated as startable.
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "ghost")
                    != GuestInstanceGate.Decision.UNKNOWN_INSTANCE) {
                throw new AssertionError("unknown instance not detected");
            }

            registry.allocate("twin-a");
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "twin-a")
                    != GuestInstanceGate.Decision.NO_ROM) {
                throw new AssertionError("slot without ROM reported startable");
            }
            rejects(data, registry, "twin-a");

            // Slot 0 lives on the data dir; install a minimal tree there.
            Path rootfs = data.resolve("rootfs");
            Files.createDirectories(rootfs.resolve("system"));
            Files.writeString(rootfs.resolve("init"), "x");
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "twin-a")
                    != GuestInstanceGate.Decision.STARTABLE) {
                throw new AssertionError("complete slot not startable");
            }
            GuestInstanceGate.requireStartable(data.toFile(), registry, "twin-a");

            // A second instance gets slot 1 under data/slots/1 and stays blocked.
            registry.allocate("twin-b");
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "twin-b")
                    != GuestInstanceGate.Decision.NO_ROM) {
                throw new AssertionError("nested slot without ROM reported startable");
            }
            Path slotRootfs = data.resolve("slots").resolve("1").resolve("rootfs");
            Files.createDirectories(slotRootfs.resolve("system"));
            Files.writeString(slotRootfs.resolve("init"), "x");
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "twin-b")
                    != GuestInstanceGate.Decision.STARTABLE) {
                throw new AssertionError("nested slot with ROM not startable");
            }

            // An init directory is not executable, so the slot stays blocked.
            Files.delete(slotRootfs.resolve("init"));
            Files.createDirectories(slotRootfs.resolve("init"));
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "twin-b")
                    != GuestInstanceGate.Decision.NO_ROM) {
                throw new AssertionError("init directory accepted");
            }

            // Releasing the instance makes its slot unknown again.
            registry.release("twin-b");
            if (GuestInstanceGate.evaluate(data.toFile(), registry, "twin-b")
                    != GuestInstanceGate.Decision.UNKNOWN_INSTANCE) {
                throw new AssertionError("released instance still startable");
            }

            rejects(data, registry, "ghost");
            rejectsArgs(null, registry, "twin-a");
            rejectsArgs(data.toFile(), null, "twin-a");
            rejectsArgs(data.toFile(), registry, " ");
            System.out.println("PASS: start gate requires a registered instance with a bootable slot");
        } finally {
            deleteTree(base);
        }
    }

    private static void rejects(Path data, GuestInstanceRegistry registry, String id) {
        try {
            GuestInstanceGate.requireStartable(data.toFile(), registry, id);
            throw new AssertionError("unstartable instance accepted: " + id);
        } catch (IllegalStateException expected) {
        }
    }

    private static void rejectsArgs(java.io.File data, GuestInstanceRegistry registry, String id) {
        try {
            GuestInstanceGate.evaluate(data, registry, id);
            throw new AssertionError("invalid gate arguments accepted");
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
