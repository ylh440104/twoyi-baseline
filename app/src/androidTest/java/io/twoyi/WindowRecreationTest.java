/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.twoyi.utils.Installer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WindowRecreationTest {
    private static SurfaceView surfaceOf(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                SurfaceView found = surfaceOf(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static SurfaceView awaitWindow(ActivityScenario<Render2Activity> scenario) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        AtomicReference<SurfaceView> current = new AtomicReference<>();
        do {
            scenario.onActivity(activity -> {
                SurfaceView surface = surfaceOf(activity.findViewById(R.id.root));
                if (surface != null && surface.isShown() && surface.getHolder().getSurface().isValid()
                        && activity.findViewById(R.id.loadingLayout).getVisibility() == View.GONE) {
                    current.set(surface);
                }
            });
            if (current.get() != null) return current.get();
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No visible valid guest window after boot");
    }

    private static Set<String> adbWorkDirectories(Context context) {
        String[] names = context.getCacheDir().list((directory, name) -> name.startsWith("adb-"));
        assertNotNull("Could not inspect the host cache", names);
        return new HashSet<>(Arrays.asList(names));
    }

    private static void installFixtureInGuest() throws Exception {
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> previousDirectories = adbWorkDirectories(host);
        File fixture = File.createTempFile("guest import test ", ".apk", host.getCacheDir());
        try {
            try (InputStream asset = test.getAssets().open("guest-import-fixture.apk")) {
                Files.copy(asset, fixture.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            List<File> files = Collections.singletonList(fixture);
            AtomicBoolean installed = new AtomicBoolean(false);
            AtomicReference<String> failure = new AtomicReference<>();
            Installer.install(host, files, new Installer.InstallResult() {
                @Override public void onSuccess(List<File> result) {
                    assertEquals(files, result);
                    installed.set(true);
                }

                @Override public void onFail(List<File> result, String message) {
                    failure.set(message);
                }
            });
            assertEquals("Temporary ADB work directory was left behind",
                    previousDirectories, adbWorkDirectories(host));
            assertTrue("Guest installation failed: " + failure.get(), installed.get());

            File split = File.createTempFile("guest import split ", ".apk", host.getCacheDir());
            try {
                try (InputStream asset = test.getAssets().open("guest-import-split.apk")) {
                    Files.copy(asset, split.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                List<File> splitSet = Arrays.asList(split, fixture);
                File invalid = File.createTempFile("invalid split ", ".apk", host.getCacheDir());
                try {
                    assertFalse("File-picker validation accepted a non-APK member",
                            Installer.checkFile(host, Arrays.asList(fixture, invalid)));
                } finally {
                    Files.deleteIfExists(invalid.toPath());
                }
                assertTrue("File-picker validation rejected the signed base and split APKs",
                        Installer.checkFile(host, splitSet));
                installed.set(false);
                failure.set(null);
                Installer.install(host, splitSet, new Installer.InstallResult() {
                    @Override public void onSuccess(List<File> result) {
                        assertEquals(splitSet, result);
                        installed.set(true);
                    }

                    @Override public void onFail(List<File> result, String message) {
                        failure.set(message);
                    }
                });
                assertTrue("Guest rejected the signed base and split APKs: " + failure.get(), installed.get());
                assertEquals("Split installation left a private ADB work directory behind",
                        previousDirectories, adbWorkDirectories(host));
            } finally {
                Files.deleteIfExists(split.toPath());
            }

            // A corrupt APK must reach the real installer and exercise its failure cleanup.
            Files.write(fixture.toPath(), new byte[]{1, 2, 3});
            installed.set(false);
            failure.set(null);
            Installer.install(host, files, new Installer.InstallResult() {
                @Override public void onSuccess(List<File> result) {
                    installed.set(true);
                }

                @Override public void onFail(List<File> result, String message) {
                    assertEquals(files, result);
                    failure.set(message);
                }
            });
            assertFalse("Guest accepted a corrupt APK", installed.get());
            String rejection = failure.get();
            assertNotNull("Failed installation did not report an error", rejection);
            assertTrue("Expected an install rejection after connecting to the guest: " + rejection,
                    rejection.startsWith("Guest APK install failed: ADB exited with ")
                            || rejection.startsWith("Guest ADB did not confirm installation: "));
            assertEquals("Failed installation left a private ADB work directory behind",
                    previousDirectories, adbWorkDirectories(host));
        } finally {
            Files.deleteIfExists(fixture.toPath());
        }
    }

    @Test public void runningGuestAcceptsReplacementWindowAndStops() throws Exception {
        TwoyiStatusManager status = TwoyiStatusManager.getInstance();
        AtomicBoolean exited = new AtomicBoolean(false);
        Runnable listener = () -> exited.set(true);
        status.addExitListener(listener);
        // Register cleanup before launch so it also runs if ActivityScenario.launch fails.
        // A cleanup assertion is suppressed if an earlier assertion already failed.
        try (AutoCloseable stopGuest = () -> assertEquals("Guest cleanup failed", 1, Renderer.stopGuest());
             ActivityScenario<Render2Activity> scenario = ActivityScenario.launch(Render2Activity.class)) {
            assertTrue("Guest failed to report boot completion", status.waitBoot(240, TimeUnit.SECONDS));
            SurfaceView first = awaitWindow(scenario);
            int initPid = Renderer.guestInitPid();
            assertTrue("Missing tracked guest init", initPid > 0);
            scenario.onActivity(activity -> {
                GuestEndpoints paths = GuestProcessProfile.endpoints(activity.getDataDir());
                try {
                    Renderer.init(first.getHolder().getSurface(),
                            io.twoyi.utils.RomManager.getLoaderPath(activity),
                            paths.getRootfsDir(), paths.getLogFile() + ".other",
                            paths.getTouchSocketPath(), paths.getKeySocketPath(), 160, 160, 30);
                    fail("Renderer accepted a different guest endpoint configuration");
                } catch (IllegalStateException expected) {
                    assertEquals("The renderer is bound to a different guest in this host process",
                            expected.getMessage());
                }
                assertEquals("Rejected endpoints affected the running guest", initPid, Renderer.guestInitPid());
            });
            scenario.recreate();
            assertNotSame(first, awaitWindow(scenario));
            assertEquals("Window recreation started a different guest", initPid, Renderer.guestInitPid());
            scenario.moveToState(Lifecycle.State.CREATED);
            assertEquals("Backgrounding changed the guest init", initPid, Renderer.guestInitPid());
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitWindow(scenario);
            assertEquals("Resuming the window started a different guest", initPid, Renderer.guestInitPid());
            assertTrue(status.isStarted());
            assertFalse("Guest init exited during window lifecycle changes", exited.get() || status.isFailed());

            installFixtureInGuest();
            assertEquals("Installing an APK changed the running guest", initPid, Renderer.guestInitPid());

            assertEquals("Could not stop guest init", 1, Renderer.stopGuest());
            assertEquals("Guest init is still tracked", 0, Renderer.guestInitPid());
            assertEquals(TwoyiStatusManager.Phase.STOPPED, status.phase());
            assertFalse("A requested shutdown was reported as a failure", status.isFailed());
            assertFalse("Guest remained ready for APK installation", status.isStarted());
            try {
                Os.kill(initPid, 0);
                fail("Guest init PID still exists after shutdown");
            } catch (ErrnoException e) {
                assertEquals("Could not verify guest init exit", OsConstants.ESRCH, e.errno);
            }
            assertEquals("Stopping again must succeed", 1, Renderer.stopGuest());
        } finally {
            status.removeExitListener(listener);
        }
    }
}
