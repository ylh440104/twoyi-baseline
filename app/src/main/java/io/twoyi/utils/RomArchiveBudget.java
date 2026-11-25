/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.twoyi.utils;

import java.io.IOException;

/** Uncompressed space and boot file checks for a single ROM archive. */
final class RomArchiveBudget {
    private static final long RESERVE_BYTES = 32L * 1024 * 1024;
    private long required = RESERVE_BYTES;
    private boolean hasInit;
    private boolean hasSystem;

    void addEntry(String name, boolean directory, long size) throws IOException {
        if (directory) return;
        if (size < 0 || required > Long.MAX_VALUE - size) {
            throw new IOException("Invalid ROM entry size");
        }
        required += size;
        if (size > 0 && "rootfs/init".equals(name)) hasInit = true;
        if (size > 0 && name != null && name.startsWith("rootfs/system/")) hasSystem = true;
    }

    void checkAvailable(long available) {
        if (!hasInit || !hasSystem) throw new IllegalStateException("Missing ROM boot files");
        if (available < required) {
            throw new IllegalStateException("ROM needs " + required + " free bytes; available " + available);
        }
    }
}
