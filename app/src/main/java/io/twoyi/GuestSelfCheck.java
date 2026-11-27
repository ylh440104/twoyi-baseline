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
            failures += checkEndpoints(app.getDataDir(), lines);
            failures += checkBundledRom(app, lines);
            failures += checkRegistry(work, lines);
            failures += checkGate(work, lines);
            failures += checkWipe(work, lines);
            failures += checkLoader(app, lines);
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
        String trace = StartupTrace.read(app);
        if (trace != null) {
            lines.add("");
            lines.add("--- start-up trace ---");
            lines.add(truncate(trace, 2000));
        }
        String crash = CrashDiary.lastReport(app);
        if (crash != null) {
            lines.add("");
            lines.add("--- last crash ---");
            lines.add(truncate(crash, 4000));
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


    /** The endpoints of slot 0 must stay distinct files inside the data directory. */
    private static int checkEndpoints(File dataDir, List<String> lines) {
        int failures = 0;
        GuestEndpoints endpoints = GuestEndpoints.forSlot(0, dataDir);
        String prefix = dataDir.getPath() + File.separator;
        boolean inside = endpoints.getRootfsDir().startsWith(prefix)
                && endpoints.getLogFile().startsWith(prefix)
                && endpoints.getTouchSocketPath().startsWith(prefix)
                && endpoints.getKeySocketPath().startsWith(prefix);
        failures += expect(inside, lines,
                "slot 0 endpoints live inside the data directory", "an endpoint escaped the data directory");
        boolean distinct = !endpoints.getLogFile().equals(endpoints.getTouchSocketPath())
                && !endpoints.getTouchSocketPath().equals(endpoints.getKeySocketPath());
        failures += expect(distinct, lines,
                "log and input sockets use distinct paths", "endpoint paths collide");
        failures += expect(GuestEndpoints.DEFAULT_CONTROL_SOCKET.equals(endpoints.getControlSocketName()),
                lines, "control socket keeps the ROM's compiled-in name",
                "control socket name changed: " + endpoints.getControlSocketName());
        return failures;
    }

    /**
     * A missing or truncated bundled ROM fails install later with a confusing
     * error; checking the asset envelope here tells the user up front.
     */
    private static int checkBundledRom(Context app, List<String> lines) {
        int failures = 0;
        try (java.io.InputStream archive = app.getAssets().open("rootfs.7z")) {
            byte[] header = new byte[6];
            int read = 0;
            while (read < header.length) {
                int count = archive.read(header, read, header.length - read);
                if (count <= 0) break;
                read += count;
            }
            boolean sevenZip = read == header.length
                    && header[0] == 0x37 && header[1] == 0x7a && header[2] == 0xbc
                    && header[3] == 0xaf && header[4] == 0x27 && header[5] == 0x1c;
            failures += expect(sevenZip, lines, "bundled rootfs.7z has a 7z header",
                    "bundled rootfs.7z header is wrong or the asset is missing");
        } catch (IOException missing) {
            failures += expect(false, lines, "bundled rootfs.7z is readable",
                    "cannot read bundled rootfs.7z: " + missing.getMessage());
        }
        io.twoyi.utils.RomManager.RomInfo info = io.twoyi.utils.RomManager.getRomInfoFromAssets(app);
        boolean parsed = info != null && info.code > 0 && info.version != null;
        failures += expect(parsed, lines, "bundled rom.ini describes the ROM",
                "bundled rom.ini is missing or unreadable");
        if (parsed) lines.add("info rom version " + info.version + " code " + info.code);
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

    /**
     * Ask the native loader what it thinks of the installed guest init.
     *
     * This is the only check that reaches the loader core itself, so its line says both
     * whether the native side answered and what it answered. A missing library is not a
     * failure here: older builds legitimately lack it.
     */
    private static int checkLoader(Context app, List<String> lines) {
        if (!GuestLoaderCheck.isAvailable()) {
            lines.add("loader: native check unavailable (Java header check only)");
            return 0;
        }
        File rootfs = new File(io.twoyi.utils.RomManager.getSlotBaseDir(app), "rootfs");
        String verdict = GuestLoaderCheck.askForInit(rootfs);
        if (verdict == null) {
            lines.add("loader: no guest installed yet");
            return 0;
        }
        lines.add("loader: " + verdict);
        return 0;
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

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n[truncated]";
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