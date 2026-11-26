/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. See https://mozilla.org/MPL/2.0/. */
package io.twoyi.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Pure Java regression cases for interrupted same-filesystem commits, on Linux CI. */
public final class RomInstallTransactionTest {
    public static void main(String[] args) throws IOException {
        Path suite = Files.createTempDirectory("rom-transaction-test-");
        try {
            for (int step = 0; step <= 4; step++) checkRecovery(Files.createDirectory(suite.resolve("step-" + step)), step);
            Path interrupted = Files.createDirectory(suite.resolve("before-plan"));
            Path old = Files.createDirectories(interrupted.resolve("rootfs/data"));
            Files.createFile(old.resolve("user"));
            RomInstallTransaction partial = RomInstallTransaction.begin(interrupted);
            Path candidate = Files.createDirectory(partial.extractionDirectory().resolve("rootfs"));
            Files.createSymbolicLink(candidate.resolve("do-not-follow"), old);
            String id = new String(Files.readAllBytes(interrupted.resolve(".rom-install-v1")), StandardCharsets.US_ASCII);
            Files.move(candidate, interrupted.resolve(".rom-next-" + id), StandardCopyOption.ATOMIC_MOVE);
            check(!partial.recover() && Files.exists(old.resolve("user")));
            check(RomInstallTransaction.current(interrupted) == null);

            Path p = Files.createDirectory(suite.resolve("unsafe"));
            Path live = Files.createDirectory(p.resolve("rootfs"));
            Files.createSymbolicLink(live.resolve("data"), suite);
            RomInstallTransaction t = RomInstallTransaction.begin(p);
            Files.createDirectory(t.extractionDirectory().resolve("rootfs"));
            try { t.prepare(); throw new AssertionError("Live data symlink accepted"); }
            catch (IOException expected) { /* old tree remains untouched */ }
            check(!t.recover() && Files.isSymbolicLink(live.resolve("data")));

            p = Files.createDirectory(suite.resolve("first"));
            t = RomInstallTransaction.begin(p);
            Files.createDirectories(t.extractionDirectory().resolve("rootfs/data"));
            t.prepare();
            try {
                t.finish();
                throw new AssertionError("Uncommitted ROM was finalized");
            } catch (IOException expected) {
                check(RomInstallTransaction.current(p) != null);
            }
            check(t.recover() && t.recover() && Files.isDirectory(p.resolve("rootfs/data")));
            t.finish();
            check(RomInstallTransaction.current(p) == null);

            checkTransferPreflight(Files.createDirectory(suite.resolve("transfer-conflict")));

            Path cleanup = Files.createDirectory(suite.resolve("inert-plan"));
            RomInstallTransaction complete = RomInstallTransaction.begin(cleanup);
            Files.createDirectory(complete.extractionDirectory().resolve("rootfs"));
            complete.prepare();
            check(complete.recover());
            Path journal = cleanup.resolve(".rom-install-v1");
            String installId = new String(Files.readAllBytes(journal), StandardCharsets.US_ASCII);
            Path plan = cleanup.resolve(".rom-ready-" + installId);
            Files.delete(plan);
            Files.createDirectory(plan);
            Files.createFile(plan.resolve("unexpected-entry"));
            // An invalid plan cannot be used to finalize even a published root.
            try {
                complete.finish();
                throw new AssertionError("Invalid commit plan was accepted");
            } catch (IOException expected) {
                check(RomInstallTransaction.current(cleanup) != null);
            }
            // Simulate process exit after finalization removed the journal but
            // before it removed the commit plan.
            Files.delete(plan.resolve("unexpected-entry"));
            Files.delete(plan);
            Files.write(plan, new byte[]{0}, StandardOpenOption.CREATE_NEW);
            Files.delete(journal);
            check(RomInstallTransaction.current(cleanup) == null);
            check(Files.isDirectory(cleanup.resolve("rootfs")));
            checkAbandonment(suite);
        } finally {
            Files.walkFileTree(suite, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                    Files.delete(p);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path p, IOException e) throws IOException {
                    if (e != null) throw e;
                    Files.delete(p);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void checkTransferPreflight(Path parent) throws IOException {
        Path live = Files.createDirectory(parent.resolve("rootfs"));
        Path dataMarker = Files.createFile(Files.createDirectory(live.resolve("data")).resolve("user"));
        Path sdcardMarker = Files.createFile(Files.createDirectory(live.resolve("sdcard")).resolve("user"));
        RomInstallTransaction transaction = RomInstallTransaction.begin(parent);
        Files.createDirectory(transaction.extractionDirectory().resolve("rootfs"));
        transaction.prepare();

        Path journal = parent.resolve(".rom-install-v1");
        String id = new String(Files.readAllBytes(journal), StandardCharsets.US_ASCII);
        Path conflict = Files.createFile(parent.resolve(".rom-next-" + id).resolve("sdcard"));
        try {
            transaction.recover();
            throw new AssertionError("Conflicting transfer destination was accepted");
        } catch (IOException expected) {
            check(Files.isRegularFile(dataMarker));
            check(Files.isRegularFile(sdcardMarker));
            check(Files.notExists(parent.resolve(".rom-previous-" + id)));
            check(RomInstallTransaction.current(parent) != null);
        }

        Files.delete(conflict);
        check(transaction.recover());
        transaction.finish();
        check(Files.isRegularFile(dataMarker));
        check(Files.isRegularFile(sdcardMarker));
    }

    private static void checkRecovery(Path p, int step) throws IOException {
        Path live = Files.createDirectory(p.resolve("rootfs"));
        Files.createFile(live.resolve("unknown-old-file"));
        for (String name : new String[]{"data", "sdcard"}) {
            Files.createFile(Files.createDirectory(live.resolve(name)).resolve("user"));
        }
        RomInstallTransaction t = RomInstallTransaction.begin(p);
        Path staged = Files.createDirectory(t.extractionDirectory().resolve("rootfs"));
        Files.createFile(staged.resolve("new-init"));
        for (String name : new String[]{"data", "sdcard"}) {
            Files.createFile(Files.createDirectory(staged.resolve(name)).resolve("archive-default"));
        }
        t.prepare();
        String id = new String(Files.readAllBytes(p.resolve(".rom-install-v1")), StandardCharsets.US_ASCII);
        Path backup = p.resolve(".rom-previous-" + id);
        Path next = p.resolve(".rom-next-" + id);
        if (step >= 1) Files.move(live, backup, StandardCopyOption.ATOMIC_MOVE);
        if (step >= 2) Files.move(backup.resolve("data"), next.resolve("data"), StandardCopyOption.ATOMIC_MOVE);
        if (step >= 3) Files.move(backup.resolve("sdcard"), next.resolve("sdcard"), StandardCopyOption.ATOMIC_MOVE);
        if (step >= 4) Files.move(next, live, StandardCopyOption.ATOMIC_MOVE);
        t = RomInstallTransaction.current(p);
        check(t.recover() && t.recover());
        t.finish();
        check(RomInstallTransaction.current(p) == null && Files.exists(backup.resolve("unknown-old-file")));
        check(Files.exists(live.resolve("new-init")) && Files.exists(live.resolve("data/user"))
                && Files.exists(live.resolve("sdcard/user")) && Files.notExists(live.resolve("data/archive-default")));
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    /** A cancelled install must not block the next one. */
    private static void checkAbandonment(Path suite) throws IOException {
        Path slot = Files.createDirectory(suite.resolve("abandon"));
        RomInstallTransaction first = RomInstallTransaction.begin(slot);
        Files.createDirectory(first.extractionDirectory().resolve("rootfs"));
        if (RomInstallTransaction.current(slot) == null) throw new AssertionError("journal missing");
        if (!RomInstallTransaction.abandon(slot)) throw new AssertionError("nothing abandoned");
        if (RomInstallTransaction.current(slot) != null) throw new AssertionError("journal survived abandon");
        // The slot is usable again.
        RomInstallTransaction again = RomInstallTransaction.begin(slot);
        if (again == null) throw new AssertionError("slot unusable after abandon");
        RomInstallTransaction.abandon(slot);
        if (RomInstallTransaction.abandon(slot)) throw new AssertionError("abandon reported work when idle");

        // A published plan must not be silently thrown away.
        Path committed = Files.createDirectory(suite.resolve("abandon-published"));
        RomInstallTransaction planned = RomInstallTransaction.begin(committed);
        Path staged = Files.createDirectory(planned.extractionDirectory().resolve("rootfs"));
        Files.createDirectory(staged.resolve("system"));
        Files.createFile(staged.resolve("init"));
        planned.prepare();
        try {
            RomInstallTransaction.abandon(committed);
            throw new AssertionError("published transaction was abandoned");
        } catch (IOException expected) {
        }
        planned.recover();
        planned.finish();
    }
}
