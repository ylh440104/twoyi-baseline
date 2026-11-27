package io.twoyi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GuestPreflightNoteTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        formatting();
        sanitizing();
        bounded();
        atomicReplace();
        purity();
        if (failures != 0) throw new AssertionError(failures + " preflight note case(s) failed");
        System.out.println("PASS: guest preflight record is one line, bounded and crash safe");
    }

    private static void formatting() {
        String line = GuestPreflightNote.format(
                "2026-11-27T04:00:00Z", "DYNAMICALLY_LINKED", "rootfs/init", "entry=0x40010c");
        expect(line.equals("2026-11-27T04:00:00Z DYNAMICALLY_LINKED rootfs/init entry=0x40010c"),
                "line shape: " + line);
        expect(GuestPreflightNote.format("t", "OK", "rootfs/init", null)
                .equals("t OK rootfs/init"), "detail is optional");
        expect(GuestPreflightNote.format("t", "OK", "rootfs/init", "")
                .equals("t OK rootfs/init"), "blank detail is omitted");
    }

    private static void sanitizing() {
        String line = GuestPreflightNote.format("t", "OK", "a\nb", "c\td");
        expect(!line.contains("\n"), "newlines must not survive");
        expect(!line.contains("\t"), "tabs must not survive");
        expect(GuestPreflightNote.format(null, null, "p", null).equals("- - p"),
                "nulls become a placeholder");
        expect(GuestPreflightNote.format("  ", "OK", "p", null).equals("- OK p"),
                "blank time becomes a placeholder");
    }

    private static void bounded() throws IOException {
        Path work = Files.createTempDirectory("preflight-note");
        File target = work.resolve("preflight.txt").toFile();
        for (int index = 0; index < GuestPreflightNote.MAX_LINES * 2; index++) {
            GuestPreflightNote.record(target, GuestPreflightNote.format(
                    "t" + index, "MISSING", "rootfs/init", null));
        }
        List<String> lines = GuestPreflightNote.read(target);
        expect(lines.size() == GuestPreflightNote.MAX_LINES,
                "kept " + lines.size() + " lines, expected " + GuestPreflightNote.MAX_LINES);
        expect(lines.get(lines.size() - 1).startsWith("t23 "), "newest line is last");
        expect(!lines.get(0).startsWith("t0 "), "oldest lines were dropped");
    }

    private static void atomicReplace() throws IOException {
        Path work = Files.createTempDirectory("preflight-note-atomic");
        File target = work.resolve("preflight.txt").toFile();
        GuestPreflightNote.record(target, "first");
        expect(!new File(target.getPath() + ".tmp").exists(), "no temporary left behind");
        expect(Files.readString(target.toPath(), StandardCharsets.UTF_8).trim().equals("first"),
                "content replaced");

        // A missing parent directory is created rather than throwing.
        File nested = work.resolve("a/b/preflight.txt").toFile();
        GuestPreflightNote.record(nested, "nested");
        expect(nested.isFile(), "nested target created");

        // Bad input is refused instead of writing garbage.
        expectThrows(() -> GuestPreflightNote.record(null, "x"), "null target");
        expectThrows(() -> GuestPreflightNote.record(target, "  "), "blank line");
    }

    private static void purity() throws IOException {
        String source = fileText("app/src/main/java/io/twoyi/GuestPreflightNote.java");
        expect(!source.contains("android."), "the record writer must not use android.*");
        expect(!source.contains("R.string"), "the record writer must not use resources");
    }

    private static String fileText(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void expect(boolean condition, String what) {
        if (condition) return;
        failures++;
        System.err.println("FAIL " + what);
    }

    /** A body that may throw, so the refusal cases can be expressed directly. */
    private interface IOAction {
        void run() throws IOException;
    }

    private static void expectThrows(IOAction action, String what) {
        try {
            action.run();
            failures++;
            System.err.println("FAIL accepted invalid input: " + what);
        } catch (IOException expected) {
            // fine
        }
    }
}