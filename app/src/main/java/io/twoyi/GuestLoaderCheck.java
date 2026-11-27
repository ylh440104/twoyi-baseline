/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.File;

/**
 * The guest image check answered by the loader core itself.
 *
 * {@link GuestRomPreflight} reads the ELF header in Java, which is enough to name a few
 * structural faults but cannot know the things only the loader decides: which imports
 * this device can serve, how many relocations an image has, whether the relocation format
 * is one the loader understands. That answer lives in the native loader, so the app asks
 * it directly instead of keeping a second, drifting copy of the rules.
 *
 * The native library is optional: on a build that does not carry it the class reports
 * {@code null} and the caller keeps its Java-only verdict.
 */
public final class GuestLoaderCheck {
    private static final boolean LIBRARY_LOADED;

    static {
        // The same native library the renderer uses; loading it twice is a no-op.
        // A build without the library must degrade to "no answer", not crash the app:
        // the callers already have a Java-only check to fall back to.
        boolean loaded;
        try {
            System.loadLibrary("twoyi");
            loaded = true;
        } catch (UnsatisfiedLinkError missing) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private GuestLoaderCheck() {}

    private static Boolean available;

    /** True when the native loader answers, which is decided by trying once. */
    public static synchronized boolean isAvailable() {
        if (!LIBRARY_LOADED) return false;
        if (available == null) {
            // Probing a small always-present binary says whether the native side answers
            // without depending on a guest being installed.
            String probe = ask("/system/bin/sh");
            available = probe != null && !probe.isEmpty();
        }
        return available;
    }

    /**
     * The loader's own verdict for {@code initFile}, or {@code null} when the native side
     * is not there.
     *
     * The text is intentionally compact and stable:
     * {@code LOADABLE|BLOCKED segments=N imports=N relocations=N [libraries=o/n unresolved=k]}.
     */
    public static String ask(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            return nativePreflight(path);
        } catch (UnsatisfiedLinkError missing) {
            return null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * The verdict for a slot's guest init, or {@code null} when nothing is installed.
     *
     * Kept separate from the native call so the decision is testable: whether an image is
     * there at all is a file question, not a loader question.
     */
    public static String askForInit(File rootfsDirectory) {
        if (rootfsDirectory == null) return null;
        File init = new File(rootfsDirectory, "init");
        if (!init.isFile()) return null;
        return ask(init.getAbsolutePath());
    }

    /** True when the verdict starts with {@code LOADABLE}. */
    public static boolean isLoadable(String verdict) {
        return verdict != null && verdict.startsWith("LOADABLE");
    }

    private static native String nativePreflight(String path);
}