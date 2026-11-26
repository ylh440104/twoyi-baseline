/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Breadcrumbs for start-up. A Java crash is recorded by {@link CrashDiary}, but
 * a native crash kills the process without running any handler; the last step
 * written here still tells us where the process died.
 *
 * The file is rewritten at the start of every launch and kept small.
 */
public final class StartupTrace {
    private static final String FILE_NAME = "startup-trace.txt";
    private static final long START = android.os.SystemClock.elapsedRealtime();
    private static boolean started;

    private StartupTrace() {}

    /** Begin a new trace for this launch; subsequent steps append to it. */
    public static synchronized void begin(Context context) {
        started = true;
        write(context, "launch " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " sdk " + android.os.Build.VERSION.SDK_INT);
    }

    /** Record a step; safe to call from any thread, does nothing before begin. */
    public static void step(Context context, String name) {
        if (!started) return;
        append(context, name);
    }

    private static synchronized void append(Context context, String step) {
        long elapsed = android.os.SystemClock.elapsedRealtime() - START;
        try (PrintWriter out = new PrintWriter(new FileWriter(file(context), true))) {
            out.println("[" + elapsed + "ms] " + step);
        } catch (IOException ignored) {
            // Diagnostics only; never affect the app.
        }
    }

    private static synchronized void write(Context context, String line) {
        try (PrintWriter out = new PrintWriter(new FileWriter(file(context), false))) {
            out.println(line);
        } catch (IOException ignored) {
            // Diagnostics only.
        }
    }

    public static String read(Context context) {
        File file = file(context);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    public static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}