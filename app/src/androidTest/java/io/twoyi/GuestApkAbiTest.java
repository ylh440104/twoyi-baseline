/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.twoyi.utils.UIHelper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class GuestApkAbiTest {
    @Test
    public void nativeLibrariesInSplitsDetermineCompatibility() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File base = File.createTempFile("abi-base-", ".apk", context.getCacheDir());
        File split = null;
        try {
            split = File.createTempFile("abi-split-", ".apk", context.getCacheDir());
            writeFixture(base, "classes.dex");
            assertTrue(UIHelper.supportsGuestAbi(Collections.singletonList(base)));
            writeFixture(split, "lib/armeabi-v7a/libfixture.so");
            assertFalse(UIHelper.supportsGuestAbi(Arrays.asList(base, split)));
            writeFixture(split, "lib/x86_64/libfixture.so");
            assertFalse(UIHelper.supportsGuestAbi(Arrays.asList(base, split)));
            writeFixture(split, "lib/arm64-v8a/libfixture.so");
            assertTrue(UIHelper.supportsGuestAbi(Arrays.asList(base, split)));
        } finally {
            if (split != null) Files.deleteIfExists(split.toPath());
            Files.deleteIfExists(base.toPath());
        }
    }

    private static void writeFixture(File file, String entry) throws IOException {
        try (ZipOutputStream archive = new ZipOutputStream(new FileOutputStream(file))) {
            archive.putNextEntry(new ZipEntry(entry));
            archive.write(0);
            archive.closeEntry();
        }
    }
}
