/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. See https://mozilla.org/MPL/2.0/. */
package io.twoyi.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Single writer, guest stopped. Recover after process death, before accessing rootfs. */
final class RomInstallTransaction {
    private static final String[] PRESERVE = {"data", "sdcard"};
    private final Path journal, work, live, next, backup, ready;

    private RomInstallTransaction(Path parent, String id) {
        journal = parent.resolve(".rom-install-v1");
        live = parent.resolve("rootfs");
        work = parent.resolve(".rom-build-" + id);
        next = parent.resolve(".rom-next-" + id);
        backup = parent.resolve(".rom-previous-" + id);
        ready = parent.resolve(".rom-ready-" + id);
    }

    static RomInstallTransaction begin(Path parent) throws IOException {
        String id = UUID.randomUUID().toString();
        RomInstallTransaction t = new RomInstallTransaction(parent, id);
        for (Path p : new Path[]{t.journal, t.work, t.next, t.backup, t.ready}) requireAbsent(p);
        // A crash during this tiny write leaves only an unreferenced temp file.
        Path tmp = Files.createTempFile(parent, ".rom-journal-", ".tmp");
        Files.write(tmp, id.getBytes(StandardCharsets.US_ASCII));
        move(tmp, t.journal);
        Files.createDirectory(t.work);
        return t;
    }

    static RomInstallTransaction current(Path parent) throws IOException {
        Path journal = parent.resolve(".rom-install-v1");
        if (absent(journal)) return null;
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.size(journal) != 36)
            throw new IOException("Invalid ROM journal: " + journal);
        String id = new String(Files.readAllBytes(journal), StandardCharsets.US_ASCII);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("Invalid ROM transaction ID");
        return new RomInstallTransaction(parent, id);
    }

    Path extractionDirectory() { return work; }

    /** Persist an immutable plan after the entire archive and properties are ready. */
    void prepare() throws IOException {
        Path staged = work.resolve("rootfs");
        requireDirectory(staged);
        int flags = 0;
        if (!absent(live)) {
            requireDirectory(live);
            flags = 1;
            for (int i = 0; i < PRESERVE.length; i++) {
                String name = PRESERVE[i];
                if (absent(live.resolve(name))) continue;
                requireDirectory(live.resolve(name)); // refuse links incl. dangling links
                Path defaults = staged.resolve(name);
                if (!absent(defaults)) move(defaults, work.resolve("archive-" + name));
                flags |= 2 << i;
            }
        }
        for (String name : PRESERVE) {
            Path defaults = staged.resolve(name);
            if (!absent(defaults)) requireDirectory(defaults);
        }
        // Only the extracted tree can enter next before publication of ready.
        move(staged, next);
        Path tmp = work.resolve("plan");
        Files.write(tmp, new byte[]{(byte) flags}, StandardOpenOption.CREATE_NEW);
        move(tmp, ready);
    }

    /** False means an interrupted extraction was discarded; live never changed. */
    boolean recover() throws IOException {
        if (absent(ready)) {
            requireAbsent(backup);
            discardExtracted(next);
            discardExtracted(work);
            Files.delete(journal);
            return false;
        }
        int flags = readCommitFlags();
        if (!absent(next)) {
            requireDirectory(next);
            if ((flags & 1) != 0) {
                if (absent(backup)) {
                    requireDirectory(live);
                    // Check every planned transfer before taking the old ROM offline.
                    for (int i = 0; i < PRESERVE.length; i++) {
                        if ((flags & (2 << i)) != 0) {
                            requireDirectory(live.resolve(PRESERVE[i]));
                            requireAbsent(next.resolve(PRESERVE[i]));
                        }
                    }
                    move(live, backup);
                }
                requireDirectory(backup);
                requireAbsent(live);
                for (int i = 0; i < PRESERVE.length; i++) {
                    if ((flags & (2 << i)) == 0) continue;
                    Path src = backup.resolve(PRESERVE[i]);
                    Path dst = next.resolve(PRESERVE[i]);
                    if (!absent(src)) {
                        requireDirectory(src);
                        move(src, dst);
                    }
                    requireDirectory(dst);
                }
            } else requireAbsent(backup);
            move(next, live);
        }
        verifyPublishedRoot(flags);
        return true;
    }

    private void verifyPublishedRoot(int flags) throws IOException {
        requireDirectory(live);
        if ((flags & 1) != 0) {
            requireDirectory(backup);
        } else {
            requireAbsent(backup);
        }
        for (int i = 0; i < PRESERVE.length; i++) {
            if ((flags & (2 << i)) != 0) requireDirectory(live.resolve(PRESERVE[i]));
        }
    }

    private int readCommitFlags() throws IOException {
        if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS) || Files.size(ready) != 1) {
            throw new IOException("Invalid ROM commit plan");
        }
        byte[] plan = Files.readAllBytes(ready);
        if (plan.length != 1) throw new IOException("Invalid ROM commit plan");
        int flags = plan[0];
        if (flags != 0 && (flags < 1 || flags > 7 || (flags & 1) == 0)) {
            throw new IOException("Invalid ROM commit flags");
        }
        return flags;
    }

    /** Call after installation preferences and the legacy pending flag are settled. */
    void finish() throws IOException {
        requireAbsent(next);
        int flags = readCommitFlags();
        verifyPublishedRoot(flags);
        discardExtracted(work);
        // Deleting the journal completes the transaction. If cleanup stops here,
        // the remaining one-byte plan is inert and the next launch can boot.
        Files.delete(journal);
        try {
            Files.deleteIfExists(ready);
        } catch (IOException ignored) {
            // Failure to remove an inert plan must not fail a completed install.
        }
    }

    private static boolean absent(Path path) throws IOException {
        // Files.exists returns false on access errors. Only ENOENT means absent.
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException e) { return true; }
        return false;
    }

    private static void requireAbsent(Path path) throws IOException {
        if (!absent(path)) throw new IOException("Refusing to overwrite " + path);
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a real directory: " + path);
    }

    private static void move(Path src, Path dst) throws IOException {
        requireAbsent(dst);
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
    }

    // Called only on transaction-owned extraction paths, never live or backup.
    private static void discardExtracted(Path root) throws IOException {
        if (absent(root)) return;
        requireDirectory(root);
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                Files.delete(p);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path p, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(p);
                return FileVisitResult.CONTINUE;
            }
        }); // default: do not follow symbolic links
    }
}
