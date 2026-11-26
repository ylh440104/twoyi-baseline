/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records why the app died. A debug build that crashes before any UI is shown
 * otherwise leaves no trace on the device: logcat needs adb, and an OEM crash
 * dialog shows nothing useful.
 *
 * The handler is installed as the first statement of attachBaseContext so even a
 * failure inside application initialisation is captured, and the report is
 * written to two places: the app files dir and the external files dir, whichever
 * a file manager can reach.
 */
public final class CrashDiary {
    private static final String TAG = "CrashDiary";
    private static final String FILE_NAME = "last-crash.txt";
    private static final int MAX_CHARS = 16 * 1024;

    private CrashDiary() {}

    /** Installs the handler once; safe to call from every process. */
    public static void install(Context context) {
        final Context app = context;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                record(app, thread, failure);
            } catch (Throwable ignored) {
                // Never mask the original crash with a logging failure.
            }
            if (previous != null) {
                previous.uncaughtException(thread, failure);
            }
        });
    }

    static void record(Context context, Thread thread, Throwable failure) {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        writer.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        writer.println("thread: " + (thread == null ? "?" : thread.getName()));
        writer.println("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " sdk " + android.os.Build.VERSION.SDK_INT);
        failure.printStackTrace(writer);
        writer.flush();
        String report = text.toString();
        if (report.length() > MAX_CHARS) {
            report = report.substring(0, MAX_CHARS) + "\n[truncated]";
        }
        Log.e(TAG, report);
        write(new File(context.getFilesDir(), FILE_NAME), report);
        File external = context.getExternalFilesDir(null);
        if (external != null) write(new File(external, FILE_NAME), report);
    }

    private static void write(File target, String report) {
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            try (PrintWriter out = new PrintWriter(new FileWriter(target, false))) {
                out.print(report);
            }
        } catch (IOException ignored) {
            // Best effort only.
        }
    }

    /** Last recorded crash, or null when nothing has been written. */
    public static String lastReport(Context context) {
        File internal = new File(context.getFilesDir(), FILE_NAME);
        if (internal.isFile()) return read(internal);
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File file = new File(external, FILE_NAME);
            if (file.isFile()) return read(file);
        }
        return null;
    }

    public static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        new File(context.getFilesDir(), FILE_NAME).delete();
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(external, FILE_NAME).delete();
        }
    }

    private static String read(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }
}