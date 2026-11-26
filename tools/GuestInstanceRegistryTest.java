package io.twoyi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GuestInstanceRegistryTest {
    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("instance-registry-");
        try {
            Path file = dir.resolve("state/instances.tsv");
            GuestInstanceRegistry registry = GuestInstanceRegistry.load(file);
            if (registry.size() != 0 || registry.active() != null) {
                throw new AssertionError("new registry is not empty");
            }

            int first = registry.allocate("twin-a");
            int second = registry.allocate("twin-b");
            if (first != 0 || second != 1) throw new AssertionError("slots not assigned in order");
            registry.setState("twin-a", GuestInstanceRegistry.State.READY);
            registry.activate("twin-a");
            if (!"twin-a".equals(registry.active())) throw new AssertionError("active instance not stored");

            rejects(() -> registry.allocate("twin-a"));
            rejects(() -> registry.allocate(""));
            rejects(() -> registry.allocate("bad id"));
            rejects(() -> registry.slotOf("missing"));
            rejects(() -> registry.setState("twin-a", null));
            rejects(() -> registry.activate("missing"));

            // Fill the remaining slots, then refuse a fifth instance.
            registry.allocate("twin-c");
            registry.allocate("twin-d");
            rejects(() -> registry.allocate("twin-e"));
            if (registry.size() != GuestSlot.SLOT_COUNT) throw new AssertionError("registry overflowed");

            // Releasing frees the lowest slot again.
            registry.release("twin-b");
            if (registry.allocate("twin-f") != 1) throw new AssertionError("released slot not reused");
            registry.release("twin-f");
            registry.release("twin-f");

            // Releasing the active instance clears the pointer.
            registry.release("twin-a");
            if (registry.active() != null) throw new AssertionError("released instance stayed active");

            registry.save();
            if (!Files.isRegularFile(file)) throw new AssertionError("registry not written");

            GuestInstanceRegistry reloaded = GuestInstanceRegistry.load(file);
            if (reloaded.size() != registry.size()) throw new AssertionError("size changed after reload");
            if (reloaded.slotOf("twin-c") != registry.slotOf("twin-c")) {
                throw new AssertionError("slot changed after reload");
            }
            if (reloaded.stateOf("twin-c") != GuestInstanceRegistry.State.RESERVED) {
                throw new AssertionError("state lost after reload");
            }
            reloaded.activate("twin-c");
            reloaded.save();
            if (!"twin-c".equals(GuestInstanceRegistry.load(file).active())) {
                throw new AssertionError("active instance lost after reload");
            }

            // A registry claiming one slot twice must be rejected.
            Path duplicate = dir.resolve("duplicate.tsv");
            Files.write(duplicate, List.of("twoyi-instances-v1", "a\t0\tREADY", "b\t0\tREADY"),
                    StandardCharsets.UTF_8);
            rejectsLoad(duplicate);
            Path unknownState = dir.resolve("state-unknown.tsv");
            Files.write(unknownState, List.of("twoyi-instances-v1", "a\t0\tPAUSED"), StandardCharsets.UTF_8);
            rejectsLoad(unknownState);
            Path badSlot = dir.resolve("slot-bad.tsv");
            Files.write(badSlot, List.of("twoyi-instances-v1", "a\t9\tREADY"), StandardCharsets.UTF_8);
            rejectsLoad(badSlot);
            Path orphanActive = dir.resolve("orphan.tsv");
            Files.write(orphanActive, List.of("twoyi-instances-v1", "active=ghost"), StandardCharsets.UTF_8);
            rejectsLoad(orphanActive);
            Path wrongHeader = dir.resolve("header.tsv");
            Files.write(wrongHeader, List.of("something-else"), StandardCharsets.UTF_8);
            rejectsLoad(wrongHeader);
            rejects(() -> GuestInstanceRegistry.open(java.nio.file.Paths.get("relative-path")));
            rejects(() -> GuestInstanceRegistry.open(null));

            System.out.println("PASS: instance registry allocates slots, persists atomically and rejects bad state");
        } finally {
            deleteTree(dir);
        }
    }

    private interface Action { void run(); }

    private static void rejects(Action action) {
        try {
            action.run();
            throw new AssertionError("invalid registry operation accepted");
        } catch (IllegalArgumentException | IllegalStateException expected) {
        }
    }

    private static void rejectsLoad(Path file) throws IOException {
        try {
            GuestInstanceRegistry.load(file);
            throw new AssertionError("malformed registry accepted: " + file.getFileName());
        } catch (IOException expected) {
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