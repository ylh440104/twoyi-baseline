/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, human readable record of what the guest image check concluded.
 *
 * The verdict already exists ({@link GuestRomPreflight}), but it only lives as long
 * as the UI does. Writing it next to the diagnostics means "send logs" carries the
 * reason the guest would not start, which is the difference between a useful and a
 * useless bug report.
 *
 * No Android types, so the formatting and the size bound are covered by host tests.
 */
public final class GuestPreflightNote {
    private GuestPreflightNote() {}

    /** Keep the file small: the newest lines are the interesting ones. */
    public static final int MAX_LINES = 12;

    /**
     * One line, stable enough to grep: `time verdict path detail`.
     *
     * @param timestamp caller-supplied so the formatter stays deterministic in tests
     */
    public static String format(String timestamp, String verdict, String path, String detail) {
        StringBuilder line = new StringBuilder();
        line.append(sanitize(timestamp)).append(' ');
        line.append(sanitize(verdict)).append(' ');
        line.append(sanitize(path));
        if (detail != null && !detail.isEmpty()) {
            line.append(' ').append(sanitize(detail));
        }
        return line.toString();
    }

    /** Newlines and control characters would break the one-line-per-record shape. */
    static String sanitize(String value) {
        if (value == null) return "-";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "-";
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            out.append(character < 0x20 || character == 0x7f ? '_' : character);
        }
        return out.toString();
    }

    /**
     * Append one record, keeping at most {@link #MAX_LINES} lines.
     *
     * The file is rewritten rather than rotated: it is tiny and this keeps the
     * operation atomic enough to survive a crash mid-write.
     */
    public static void record(File target, String line) throws IOException {
        if (target == null) throw new IOException("no preflight record target");
        if (line == null || line.trim().isEmpty()) throw new IOException("empty preflight record");
        List<String> lines = new ArrayList<>();
        if (target.isFile()) {
            lines.addAll(Files.readAllLines(target.toPath(), StandardCharsets.UTF_8));
            while (lines.size() >= MAX_LINES) {
                lines.remove(0);
            }
        }
        lines.add(sanitize(line));
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File temporary = new File(target.getPath() + ".tmp");
        try (PrintWriter writer = new PrintWriter(new FileWriter(temporary, false))) {
            for (String entry : lines) {
                writer.println(entry);
            }
        }
        if (!temporary.renameTo(target)) {
            Files.deleteIfExists(temporary.toPath());
            throw new IOException("cannot replace " + target);
        }
    }

    /** The lines currently stored, newest last. Empty when nothing was recorded. */
    public static List<String> read(File target) throws IOException {
        if (target == null || !target.isFile()) return new ArrayList<>();
        return new ArrayList<>(Files.readAllLines(target.toPath(), StandardCharsets.UTF_8));
    }
}