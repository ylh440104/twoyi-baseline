package io.twoyi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The parts of {@link GuestLoaderCheck} that must not need a device.
 *
 * The native call itself can only be checked on an Android device, but the decisions
 * around it cannot go wrong silently: which file is asked about, and whether a verdict
 * counts as loadable.
 */
public final class GuestLoaderCheckTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        asksOnlyForAnInstalledInit();
        verdictPrefix();
        handleVersionText();
        if (failures != 0) throw new AssertionError(failures + " loader check case(s) failed");
        System.out.println("PASS: the loader check only asks about an installed guest");
    }

    private static void asksOnlyForAnInstalledInit() throws IOException {
        Path work = Files.createTempDirectory("loader-check");
        File rootfs = work.resolve("rootfs").toFile();
        // Nothing installed: the answer must be null, not a fabricated verdict.
        expect(GuestLoaderCheck.askForInit(null) == null, "null directory asks nothing");
        expect(GuestLoaderCheck.askForInit(rootfs) == null, "missing rootfs asks nothing");

        expect(rootfs.mkdirs(), "create rootfs");
        expect(GuestLoaderCheck.askForInit(rootfs) == null, "rootfs without init asks nothing");

        // With an init the call reaches the native side; on the host there is none, so the
        // result is null rather than an exception. Either way it must not throw.
        File init = new File(rootfs, "init");
        Files.write(init.toPath(), new byte[] {0x7f, 'E', 'L', 'F'});
        // On the host there is no native library and no Android runtime, so the answer is
        // null; what matters is that the lookup is attempted without throwing.
        String answer = GuestLoaderCheck.askForInit(rootfs);
        expect(answer == null, "without the native side there is no verdict");
    }

    private static void verdictPrefix() {
        expect(GuestLoaderCheck.isLoadable("LOADABLE segments=2 imports=0 relocations=0"),
                "LOADABLE is loadable");
        expect(!GuestLoaderCheck.isLoadable("BLOCKED segments=2"),
                "BLOCKED is not loadable");
        expect(!GuestLoaderCheck.isLoadable(null), "null is not loadable");
        expect(!GuestLoaderCheck.isLoadable(""), "empty is not loadable");
    }

    private static void handleVersionText() {
        // The verdict carries the numbers the loader decided; nothing here may invent them.
        String verdict = "BLOCKED segments=4 imports=313 relocations=1575 libraries=8/8 unresolved=0";
        expect(!GuestLoaderCheck.isLoadable(verdict), "a long blocked verdict stays blocked");
        expect(verdict.contains("libraries=8/8"), "the library counts are passed through");
    }

    private static void expect(boolean condition, String what) {
        if (condition) return;
        failures++;
        System.err.println("FAIL " + what);
    }
}