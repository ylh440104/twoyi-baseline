/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Shared byte budget for all copied APKs in one import. */
public final class ApkCopyBudget {
    private long remaining;
    private final byte[] buffer = new byte[8192];

    public ApkCopyBudget(long availableBytes, long reservedBytes) {
        if (availableBytes < 0 || reservedBytes < 0) {
            throw new IllegalArgumentException("Storage sizes must be non-negative");
        }
        remaining = Math.max(0, availableBytes - reservedBytes);
    }

    public void copy(InputStream input, OutputStream output) throws IOException {
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > remaining) {
                throw new IOException("Not enough storage to copy the selected APKs");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }
}
