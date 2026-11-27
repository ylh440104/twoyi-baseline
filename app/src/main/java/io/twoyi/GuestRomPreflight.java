/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Checks the guest {@code init} before anything tries to run it.
 *
 * "The guest failed to start" is the least useful message a container app can show.
 * Almost every early failure is one of a handful of structural problems in the
 * image, and each is cheap to detect: the file is not an ELF, it is the wrong
 * machine (32-bit image on an arm64 build), the entry point is not inside an
 * executable segment, or it is dynamically linked while the loader expects a
 * static binary.
 *
 * No Android types are used (not even `Build`): the caller passes whether this build
 * is 64-bit only, and it maps the verdict to its own resource strings. That keeps the
 * rules testable on the host, where a wrong rule costs a CI run instead of a boot.
 */
public final class GuestRomPreflight {
    private GuestRomPreflight() {}

    /** What the check concluded. */
    public enum Verdict {
        OK,
        MISSING,
        NOT_ELF,
        WRONG_CLASS,
        WRONG_MACHINE,
        ENTRY_NOT_EXECUTABLE,
        DYNAMICALLY_LINKED
    }

    /** ELF header fields the check needs. */
    public static final class ElfFacts {
        public final boolean elf;
        public final int elfClass;
        public final boolean littleEndian;
        public final int machine;
        public final long entry;
        public final boolean entryExecutable;
        public final boolean hasDynamic;

        ElfFacts(boolean elf, int elfClass, boolean littleEndian, int machine, long entry,
                 boolean entryExecutable, boolean hasDynamic) {
            this.elf = elf;
            this.elfClass = elfClass;
            this.littleEndian = littleEndian;
            this.machine = machine;
            this.entry = entry;
            this.entryExecutable = entryExecutable;
            this.hasDynamic = hasDynamic;
        }
    }

    private static final int EM_AARCH64 = 0xb7;
    private static final int EM_ARM = 0x28;
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final int PF_X = 1;
    private static final int ELFCLASS32 = 1;
    private static final int ELFCLASS64 = 2;
    private static final int SHT_DYNSYM = 11;

    /** Read the parts of the ELF header that decide loadability. */
    public static ElfFacts inspect(File init) throws IOException {
        if (init == null || !init.isFile()) {
            return new ElfFacts(false, 0, false, 0, 0, false, false);
        }
        try (RandomAccessFile file = new RandomAccessFile(init, "r")) {
            if (file.length() < 64) {
                return new ElfFacts(false, 0, false, 0, 0, false, false);
            }
            byte[] header = new byte[64];
            file.readFully(header);
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                return new ElfFacts(false, 0, false, 0, 0, false, false);
            }
            int elfClass = header[4] & 0xff;
            boolean littleEndian = header[5] == 1;
            int machine = u16(header, 0x12, littleEndian);
            long entry;
            long phoff;
            int phentsize;
            int phnum;
            if (elfClass == ELFCLASS64) {
                entry = u64(header, 0x18, littleEndian);
                phoff = u64(header, 0x20, littleEndian);
                phentsize = u16(header, 0x36, littleEndian);
                phnum = u16(header, 0x38, littleEndian);
            } else {
                entry = u32(header, 0x18, littleEndian);
                phoff = u32(header, 0x1c, littleEndian);
                phentsize = u16(header, 0x2a, littleEndian);
                phnum = u16(header, 0x2c, littleEndian);
            }

            boolean entryExecutable = false;
            boolean hasDynamic = false;
            long available = file.length();
            for (int index = 0; index < phnum; index++) {
                long at = phoff + (long) index * phentsize;
                if (at < 0 || at + phentsize > available) break;
                file.seek(at);
                byte[] ph = new byte[phentsize];
                file.readFully(ph);
                int type = u32(ph, 0, littleEndian);
                if (type == PT_DYNAMIC) {
                    hasDynamic = true;
                }
                if (type != PT_LOAD) continue;
                long vaddr;
                long filesz;
                long memsz;
                int flags;
                if (elfClass == ELFCLASS64) {
                    flags = u32(ph, 4, littleEndian);
                    vaddr = u64(ph, 0x10, littleEndian);
                    filesz = u64(ph, 0x20, littleEndian);
                    memsz = u64(ph, 0x28, littleEndian);
                } else {
                    flags = u32(ph, 0x18, littleEndian);
                    vaddr = u32(ph, 0x08, littleEndian);
                    filesz = u32(ph, 0x10, littleEndian);
                    memsz = u32(ph, 0x14, littleEndian);
                }
                long end = vaddr + Math.max(filesz, memsz);
                if ((flags & PF_X) != 0 && entry >= vaddr && entry < end) {
                    entryExecutable = true;
                }
            }
            return new ElfFacts(true, elfClass, littleEndian, machine, entry, entryExecutable,
                    hasDynamic);
        }
    }

    /** Judge the guest init: the machine must match this build and the entry must run. */
    public static Verdict check(File init, boolean buildIsArm64) {
        if (init == null || !init.isFile()) return Verdict.MISSING;
        ElfFacts facts;
        try {
            facts = inspect(init);
        } catch (IOException unreadable) {
            return Verdict.NOT_ELF;
        }
        if (!facts.elf) return Verdict.NOT_ELF;
        if (buildIsArm64) {
            if (facts.elfClass != ELFCLASS64) return Verdict.WRONG_CLASS;
            if (facts.machine != EM_AARCH64) return Verdict.WRONG_MACHINE;
        } else {
            if (facts.machine != EM_ARM && facts.machine != EM_AARCH64) {
                return Verdict.WRONG_MACHINE;
            }
        }
        if (!facts.entryExecutable) return Verdict.ENTRY_NOT_EXECUTABLE;
        if (facts.hasDynamic) return Verdict.DYNAMICALLY_LINKED;
        return Verdict.OK;
    }

    private static int u16(byte[] data, int offset, boolean little) {
        int a = data[offset] & 0xff;
        int b = data[offset + 1] & 0xff;
        return little ? (a | (b << 8)) : ((a << 8) | b);
    }

    private static int u32(byte[] data, int offset, boolean little) {
        int a = data[offset] & 0xff;
        int b = data[offset + 1] & 0xff;
        int c = data[offset + 2] & 0xff;
        int d = data[offset + 3] & 0xff;
        return little
                ? (a | (b << 8) | (c << 16) | (d << 24))
                : ((a << 24) | (b << 16) | (c << 8) | d);
    }

    private static long u64(byte[] data, int offset, boolean little) {
        long low = u32(data, little ? offset : offset + 4, little) & 0xffffffffL;
        long high = u32(data, little ? offset + 4 : offset, little) & 0xffffffffL;
        return (high << 32) | low;
    }
}