/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.twoyi.utils.GuestDataWipe;

/**
 * On-device self check for the container base. The pure-Java regressions run in
 * CI; this runs the same invariants against the real app sandbox, so a device
 * without a CI runner can still produce evidence.
 *
 * Everything it touches lives under a temporary directory (except the read-only
 * view of the real slot layout), so running it never disturbs an installed guest.
 */
public final class GuestSelfCheck {
    private static final String TAG = "GuestSelfCheck";

    private GuestSelfCheck() {}

    public static String run(Context context, String deviceLabel) {
        Context app = context.getApplicationContext();
        List<String> lines = new ArrayList<>();
        lines.add("twoyi base self check");
        lines.add("device: " + deviceLabel);
        lines.add("sdk: " + android.os.Build.VERSION.SDK_INT);
        lines.add("abi: " + android.os.Build.SUPPORTED_ABIS[0]);
        Path work = null;
        int failures = 0;
        try {
            work = Files.createTempDirectory(app.getCacheDir().toPath(), "selfcheck-");
            failures += checkSlotLayout(app.getDataDir(), work, lines);
            failures += checkRegistry(work, lines);
            failures += checkGate(work, lines);
            failures += checkWipe(work, lines);
        } catch (Exception failure) {
            failures++;
            lines.add("FAIL setup: " + failure);
        } finally {
            if (work != null) {
                try {
                    deleteTree(work);
                } catch (IOException ignored) {
                    // Temporary data under the app cache; the system may clear it.
                }
            }
        }
        lines.add(failures == 0 ? "RESULT: all checks passed" : "RESULT: " + failures + " check(s) failed");
        return String.join("\n", lines);
    }

    private static int checkSlotLayout(File dataDir, Path work, List<String> lines) throws IOException {
        int failures = 0;
        File slotZero = GuestSlotLayout.baseDirectory(dataDir, 0);
        failures += expect(slotZero.equals(dataDir), lines,
                "slot 0 is the app data directory", "slot 0 points elsewhere: " + slotZero);
        File slotTwo = GuestSlotLayout.baseDirectory(dataDir, 2);
        failures += expect(slotTwo.getPath().startsWith(dataDir.getPath() + File.separator),
                lines, "slot 2 stays inside the data directory", "slot 2 escapes: " + slotTwo);
        Path fresh = work.resolve("layout");
        Files.createDirectories(fresh.resolve(GuestSlotLayout.SLOT_DIRECTORY));
        File created = GuestSlotLayout.ensureBaseDirectory(fresh.toFile(), 1);
        failures += expect(Files.isDirectory(created.toPath()), lines,
                "slot base can be created before a ROM transaction", "slot base not created: " + created);
        return failures;
    }

    private static int checkRegistry(Path work, List<String> lines) throws IOException {
        int failures = 0;
        Path stateFile = GuestInstanceRegistry.defaultPath(work.resolve("registry"));
        GuestInstanceRegistry registry = GuestInstanceRegistry.load(stateFile);
        int slot = GuestInstanceManager.create(work.resolve("registry"), registry, "selfcheck");
        failures += expect(slot == 0, lines, "first instance takes slot 0", "unexpected slot: " + slot);
        GuestInstanceRegistry reloaded = GuestInstanceRegistry.load(stateFile);
        failures += expect(reloaded.instances().contains("selfcheck"), lines,
                "reservation survives a reload", "reservation lost");
        try {
            GuestInstanceManager.create(work.resolve("registry"), reloaded, "selfcheck");
            failures += expect(false, lines, "duplicate instance refused", "duplicate accepted");
        } catch (IllegalArgumentException expected) {
            failures += expect(true, lines, "duplicate instance refused", "");
        }
        List<String> removed = GuestInstanceManager.release(
                work.resolve("registry"), reloaded, "selfcheck", true);
        failures += expect(removed.isEmpty(), lines,
                "release of a slot without a ROM removes nothing", "unexpected removals: " + removed);
        return failures;
    }

    private static int checkGate(Path work, List<String> lines) throws IOException {
        int failures = 0;
        Path data = work.resolve("gate");
        Files.createDirectories(data);
        GuestInstanceRegistry registry = GuestInstanceRegistry.open(
                GuestInstanceRegistry.defaultPath(data));
        registry.allocate("gate-a");
        failures += expect(GuestInstanceGate.evaluate(data.toFile(), registry, "ghost")
                        == GuestInstanceGate.Decision.UNKNOWN_INSTANCE,
                lines, "unknown instance is not startable", "unknown instance looked startable");
        failures += expect(GuestInstanceGate.evaluate(data.toFile(), registry, "gate-a")
                        == GuestInstanceGate.Decision.NO_ROM,
                lines, "empty slot is reported as NO_ROM", "empty slot looked startable");
        Path rootfs = data.resolve("rootfs");
        Files.createDirectories(rootfs.resolve("system"));
        Files.write(rootfs.resolve("init"), new byte[]{1});
        failures += expect(GuestInstanceGate.evaluate(data.toFile(), registry, "gate-a")
                        == GuestInstanceGate.Decision.STARTABLE,
                lines, "complete slot is startable", "complete slot refused");
        return failures;
    }

    private static int checkWipe(Path work, List<String> lines) throws IOException {
        int failures = 0;
        Path data = work.resolve("wipe");
        Path slotRootfs = data.resolve(GuestSlotLayout.SLOT_DIRECTORY).resolve("1").resolve("rootfs");
        Files.createDirectories(slotRootfs.resolve("system"));
        Files.write(slotRootfs.resolve("init"), new byte[]{1});
        List<String> removed = GuestDataWipe.wipe(data, slotRootfs);
        failures += expect(removed.contains("rootfs") && !Files.exists(slotRootfs), lines,
                "nested slot rootfs can be wiped", "nested rootfs survived: " + removed);
        failures += expect(Files.isDirectory(data.resolve(GuestSlotLayout.SLOT_DIRECTORY)), lines,
                "the slots container is kept", "slots container was removed");
        try {
            GuestDataWipe.wipe(data, data);
            failures += expect(false, lines, "the data directory itself is refused", "data dir accepted");
        } catch (IOException expected) {
            failures += expect(true, lines, "the data directory itself is refused", "");
        }
        return failures;
    }

    private static int expect(boolean condition, List<String> lines, String ok, String failure) {
        if (condition) {
            lines.add("ok   " + ok);
            return 0;
        }
        lines.add("FAIL " + failure);
        android.util.Log.w(TAG, failure);
        return 1;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on cache data.
                }
            });
        }
    }
}