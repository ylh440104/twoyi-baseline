/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes guest-owned state for "wipe all data". Deletion never follows links,
 * refuses any root that is not a direct child of the guest data directory, and
 * reports which roots it removed so the caller can decide whether a reboot is
 * needed. A root that is already absent is skipped, so the action is idempotent.
 */
public final class GuestDataWipe {
    private GuestDataWipe() {}

    public static List<String> wipe(Path dataDir, Path... roots) throws IOException {
        if (dataDir == null || !dataDir.isAbsolute()) {
            throw new IOException("Guest data directory must be absolute");
        }
        Path canonicalData = dataDir.toRealPath();
        List<String> removed = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !root.isAbsolute()) {
                throw new IOException("Refusing a relative guest path: " + root);
            }
            Path parent = root.getParent();
            if (parent == null || !parent.toRealPath().equals(canonicalData)) {
                throw new IOException("Refusing a guest path outside the data directory: " + root);
            }
            if (!exists(root)) continue;
            deleteTree(root);
            if (exists(root)) throw new IOException("Guest path survived deletion: " + root);
            removed.add(root.getFileName().toString());
        }
        return removed;
    }

    private static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("Refusing a symlinked guest directory: " + dir);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                try (DirectoryStream<Path> remaining = Files.newDirectoryStream(dir)) {
                    if (remaining.iterator().hasNext()) {
                        throw new IOException("Guest directory was not emptied: " + dir);
                    }
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
