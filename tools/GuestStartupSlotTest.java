package io.twoyi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GuestStartupSlotTest {
    public static void main(String[] args) throws IOException {
        Path base = Files.createTempDirectory("startup-slot-");
        try {
            Path data = base.resolve("data");
            Files.createDirectories(data);
            GuestInstanceRegistry registry = GuestInstanceRegistry.open(
                    GuestInstanceRegistry.defaultPath(data));

            // Nothing registered: the legacy slot 0 wins.
            if (GuestStartupSlot.resolve(null, registry) != 0) {
                throw new AssertionError("empty registry did not default to slot 0");
            }
            if (GuestStartupSlot.resolveActiveOrZero(null) != 0) {
                throw new AssertionError("null registry did not default to slot 0");
            }

            // An explicit payload always wins, even when an instance is active.
            registry.allocate("twin-a");
            registry.allocate("twin-b");
            registry.activate("twin-b");
            if (GuestStartupSlot.resolve(null, registry) != 1) {
                throw new AssertionError("active instance did not pick its slot");
            }
            if (GuestStartupSlot.resolve("0", registry) != 0) {
                throw new AssertionError("explicit payload did not override the active instance");
            }
            if (GuestStartupSlot.resolve("1", registry) != 1) {
                throw new AssertionError("explicit payload ignored");
            }
            rejects("4", registry);
            rejects("abc", registry);
            rejects("", registry);

            // Releasing the active instance falls back to slot 0, not a stale slot.
            registry.release("twin-b");
            if (GuestStartupSlot.resolve(null, registry) != 0) {
                throw new AssertionError("released active instance kept its slot");
            }

            // A stale active id on disk must not point at an unknown slot.
            Path staleFile = base.resolve("stale.tsv");
            Files.writeString(staleFile, "twoyi-instances-v1\nactive=ghost\n");
            try {
                GuestInstanceRegistry.load(staleFile);
                throw new AssertionError("registry accepted an orphan active instance");
            } catch (IOException expected) {
                // Load refuses it; resolve() also guards by ignoring a missing id.
            }
            GuestInstanceRegistry loaded = GuestInstanceRegistry.open(base.resolve("fresh.tsv"));
            loaded.allocate("twin-c");
            loaded.activate("twin-c");
            if (GuestStartupSlot.resolve(null, loaded) != 0) {
                throw new AssertionError("slot 0 instance resolved to another slot");
            }
            System.out.println("PASS: startup slot honours the payload, then the active instance");
        } finally {
            deleteTree(base);
        }
    }

    private static void rejects(String argument, GuestInstanceRegistry registry) {
        try {
            GuestStartupSlot.resolve(argument, registry);
            throw new AssertionError("malformed payload accepted: " + argument);
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
