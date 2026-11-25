/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import java.nio.file.Paths;

public final class BundledRomPreflight {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected the bundled rootfs.7z path");
        // The Android installer checks free space separately on the actual device.
        RomArchivePreflight.inspect(Paths.get(args[0])).checkAvailable(Long.MAX_VALUE);
        System.out.println("Bundled ROM passed the installer's archive preflight");
    }
}
