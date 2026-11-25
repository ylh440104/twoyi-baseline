/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class DiagnosticSharingTest {
    @Test
    public void sharesOnlyFilesInDiagnosticsDirectory() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "diagnostics");
        Files.createDirectories(directory.toPath());
        File report = File.createTempFile("report-test-", ".zip", directory);
        File privateFile = null;
        try {
            privateFile = File.createTempFile("private-test-", ".txt", context.getCacheDir());
            Files.write(report.toPath(), new byte[]{1, 2, 3});
            String authority = context.getPackageName() + ".fileprovider";
            Uri uri = FileProvider.getUriForFile(context, authority, report);
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
                assertNotNull(descriptor);
                assertEquals(3L, descriptor.getStatSize());
            }
            try {
                FileProvider.getUriForFile(context, authority, privateFile);
                fail("A cache file outside diagnostics was shared");
            } catch (IllegalArgumentException expected) {
                // The provider exposes only the diagnostics directory.
            }
        } finally {
            if (privateFile != null) Files.deleteIfExists(privateFile.toPath());
            Files.deleteIfExists(report.toPath());
        }
    }
}
