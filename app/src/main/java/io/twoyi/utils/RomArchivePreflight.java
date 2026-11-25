/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class RomArchivePreflight {
    private RomArchivePreflight() {}

    static RomArchiveBudget inspect(Path archive) throws IOException {
        RomArchiveBudget budget = new RomArchiveBudget();
        Set<String> leaves = new HashSet<>();
        Set<String> parents = new HashSet<>();
        Set<String> names = new HashSet<>();
        try (SevenZFile file = new SevenZFile(archive.toFile())) {
            for (SevenZArchiveEntry entry : file.getEntries()) {
                String name = entry.getName();
                if (name == null || !(name.equals("rootfs") || name.startsWith("rootfs/"))
                        || name.contains("\\") || name.indexOf(0) >= 0 || !names.add(name))
                    throw new IOException("Invalid or duplicate ROM entry: " + name);
                for (String part : name.split("/", -1)) {
                    if (part.isEmpty() || part.equals(".") || part.equals(".."))
                        throw new IOException("Invalid ROM path: " + name);
                }
                int attrs = entry.getWindowsAttributes();
                int unixType = (attrs >>> 16) & 0170000;
                if (entry.isAntiItem() || (attrs & 0x400) != 0
                        || (entry.isDirectory() && unixType != 0 && unixType != 0040000)
                        || (!entry.isDirectory() && unixType != 0 && unixType != 0100000 && unixType != 0120000))
                    throw new IOException("Unsupported ROM entry: " + name);
                if (!entry.isDirectory()) leaves.add(name);
                for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1))
                    parents.add(name.substring(0, i));
                budget.addEntry(name, entry.isDirectory(), entry.getSize());
            }
        }
        // No archive entry may be written through a file or a symbolic link.
        if (!Collections.disjoint(parents, leaves)) throw new IOException("Non-directory ROM parent");
        return budget;
    }
}
