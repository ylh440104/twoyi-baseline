/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.twoyi.utils.Installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class InstallerReadinessTest {
    @Test
    public void rejectsInstallationBeforeGuestBoot() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse(TwoyiStatusManager.getInstance().isStarted());
        List<File> files = Collections.singletonList(new File(context.getCacheDir(), "unused.apk"));
        AtomicReference<String> failure = new AtomicReference<>();
        Installer.install(context, files, new Installer.InstallResult() {
            @Override public void onSuccess(List<File> installed) {
                fail("Installation accepted before guest boot");
            }

            @Override public void onFail(List<File> rejected, String message) {
                assertEquals(files, rejected);
                failure.set(message);
            }
        });
        assertEquals(context.getString(R.string.guest_install_requires_running), failure.get());
    }
}
