package io.twoyi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GuestRomPreflightTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("preflight-test");

        verdictMissing(work);
        verdictNotElf(work);
        verdictWrongClass(work);
        verdictWrongMachine(work);
        verdictEntryNotExecutable(work);
        verdictDynamicallyLinked(work);
        verdictOk(work);

        if (failures != 0) {
            throw new AssertionError(failures + " preflight case(s) failed");
        }
        System.out.println("PASS: guest init preflight names the structural reason it cannot load");
    }

    private static void verdictMissing(Path work) {
        File missing = work.resolve("absent").toFile();
        expect(GuestRomPreflight.check(missing, true) == GuestRomPreflight.Verdict.MISSING,
                "absent init must be MISSING");
        expect(GuestRomPreflight.check(null, true) == GuestRomPreflight.Verdict.MISSING,
                "null init must be MISSING");
    }

    private static void verdictNotElf(Path work) throws IOException {
        File text = work.resolve("init-text").toFile();
        Files.write(text.toPath(), "#!/system/bin/sh\n".getBytes("US-ASCII"));
        expect(GuestRomPreflight.check(text, true) == GuestRomPreflight.Verdict.NOT_ELF,
                "a script is not an ELF");
    }

    private static void verdictWrongClass(Path work) throws IOException {
        // 32-bit ARM image on an arm64 build.
        File elf = work.resolve("init-32").toFile();
        Files.write(elf.toPath(), elf32(0x28, 0x1000, 0x1000, 0x400, false));
        expect(GuestRomPreflight.check(elf, true) == GuestRomPreflight.Verdict.WRONG_CLASS,
                "a 32-bit image on arm64 must be WRONG_CLASS");
    }

    private static void verdictWrongMachine(Path work) throws IOException {
        // 64-bit ELF but for x86-64.
        File elf = work.resolve("init-x86").toFile();
        Files.write(elf.toPath(), elf64(0x3e, 0x1000, 0x400000, 0x1000, 0x400, false, false));
        expect(GuestRomPreflight.check(elf, true) == GuestRomPreflight.Verdict.WRONG_MACHINE,
                "an x86-64 image on arm64 must be WRONG_MACHINE");
    }

    private static void verdictEntryNotExecutable(Path work) throws IOException {
        // The single PT_LOAD is not executable, so the entry cannot run.
        File elf = work.resolve("init-noexec").toFile();
        Files.write(elf.toPath(), elf64(0xb7, 0x400100, 0x400000, 0x1000, 0x404, false, false));
        expect(GuestRomPreflight.check(elf, true)
                        == GuestRomPreflight.Verdict.ENTRY_NOT_EXECUTABLE,
                "an entry outside an executable segment must be reported");
    }

    private static void verdictDynamicallyLinked(Path work) throws IOException {
        File elf = work.resolve("init-dynamic").toFile();
        Files.write(elf.toPath(), elf64(0xb7, 0x400100, 0x400000, 0x1000, 0x405, false, true));
        expect(GuestRomPreflight.check(elf, true)
                        == GuestRomPreflight.Verdict.DYNAMICALLY_LINKED,
                "a PT_DYNAMIC image must be reported as dynamically linked");
    }

    private static void verdictOk(Path work) throws IOException {
        File elf = work.resolve("init-ok").toFile();
        Files.write(elf.toPath(), elf64(0xb7, 0x400100, 0x400000, 0x1000, 0x405, false, false));
        expect(GuestRomPreflight.check(elf, true) == GuestRomPreflight.Verdict.OK,
                "a static aarch64 image with an executable entry is fine");
        // The class stays free of Android types so this test can run on the host.
        expect(!hasAndroidReference(), "GuestRomPreflight must not reference android.*");
    }

    /** Flags: PF_X | PF_R = 5, or PF_R only = 4. */
    private static byte[] elf64(int machine, long entry, long vaddr, long filesz, int flags,
                                boolean p32, boolean dynamic) {
        int phnum = dynamic ? 2 : 1;
        int total = 64 + 56 * phnum;
        byte[] out = new byte[Math.max(total, (int) Math.max(filesz, total))];
        out[0] = 0x7f;
        out[1] = 'E';
        out[2] = 'L';
        out[3] = 'F';
        out[4] = 2; // ELFCLASS64
        out[5] = 1; // little endian
        out[6] = 1;
        put16(out, 0x12, machine);
        put32(out, 0x14, 1);
        put64(out, 0x18, entry);
        put64(out, 0x20, 64); // phoff
        put16(out, 0x36, 56);
        put16(out, 0x38, phnum);
        // PT_LOAD
        put32(out, 64, 1);
        put32(out, 64 + 4, flags);
        put64(out, 64 + 8, 0);
        put64(out, 64 + 0x10, vaddr);
        put64(out, 64 + 0x20, filesz);
        put64(out, 64 + 0x28, filesz);
        put64(out, 64 + 0x30, 0x1000);
        if (dynamic) {
            int at = 64 + 56;
            put32(out, at, 2); // PT_DYNAMIC
            put64(out, at + 0x10, vaddr);
            put64(out, at + 0x20, 16);
            put64(out, at + 0x28, 16);
        }
        return out;
    }

    private static byte[] elf32(int machine, long entry, long vaddr, long filesz, boolean dynamic) {
        int phnum = dynamic ? 2 : 1;
        int total = 52 + 32 * phnum;
        byte[] out = new byte[Math.max(total, (int) Math.max(filesz, total))];
        out[0] = 0x7f;
        out[1] = 'E';
        out[2] = 'L';
        out[3] = 'F';
        out[4] = 1; // ELFCLASS32
        out[5] = 1;
        out[6] = 1;
        put16(out, 0x12, machine);
        put32(out, 0x18, (int) entry);
        put32(out, 0x1c, 52);
        put16(out, 0x2a, 32);
        put16(out, 0x2c, phnum);
        put32(out, 52, 1);
        put32(out, 52 + 8, (int) vaddr);
        put32(out, 52 + 0x10, (int) filesz);
        put32(out, 52 + 0x14, (int) filesz);
        put32(out, 52 + 0x18, 5); // PF_X | PF_R
        return out;
    }

    private static void put16(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xff);
        out[at + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static void put32(byte[] out, int at, int value) {
        for (int index = 0; index < 4; index++) {
            out[at + index] = (byte) ((value >> (8 * index)) & 0xff);
        }
    }

    private static void put64(byte[] out, int at, long value) {
        for (int index = 0; index < 8; index++) {
            out[at + index] = (byte) ((value >> (8 * index)) & 0xff);
        }
    }

    /** The class must compile on the host, which means no android.* or R references. */
    private static boolean hasAndroidReference() {
        try {
            String source = new String(
                    Files.readAllBytes(Path.of("app/src/main/java/io/twoyi/GuestRomPreflight.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            return source.contains("android.") || source.contains("R.string");
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static void expect(boolean condition, String what) {
        if (condition) return;
        failures++;
        System.err.println("FAIL " + what);
    }
}