/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.RomManager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RomInstallationTest {
    @Test
    public void bundledRomReplacementPreservesGuestData() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path root = RomManager.getRootfsDir(context).toPath();
        assertFalse("Use a fresh dedicated test installation", Files.exists(root));
        Files.createDirectory(root);
        Path init = root.resolve("init");
        Path system = root.resolve("system");
        try {
            Files.createFile(init);
            assertFalse("An init without a system directory is not a complete ROM",
                    RomManager.romExist(context));
            Files.createDirectory(system);
            assertTrue("A regular init and system directory are recognized",
                    RomManager.romExist(context));
            Files.delete(init);
            Files.createDirectory(init);
            assertFalse("An init directory cannot be launched", RomManager.romExist(context));
            Files.delete(init);
            Path otherInit = system.resolve("other-init");
            Files.createFile(otherInit);
            try {
                Files.createSymbolicLink(init, root.relativize(otherInit));
                assertFalse("An init symlink must be rejected before native launch", RomManager.romExist(context));
            } finally {
                Files.deleteIfExists(otherInit);
            }
        } finally {
            Files.deleteIfExists(init);
            Files.deleteIfExists(system);
            Files.delete(root);
        }

        Path invalidJournal = context.getDataDir().toPath().resolve(".rom-install-v1");
        Files.createFile(invalidJournal);
        CompletableFuture<Void> failed;
        try {
            failed = RomPreparation.ensureReady(context);
            try {
                failed.get(10, TimeUnit.SECONDS);
                org.junit.Assert.fail("Invalid ROM journal was accepted");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof java.io.IOException);
            }
        } finally {
            Files.deleteIfExists(invalidJournal);
        }
        org.junit.Assert.assertSame("Reopening the UI must not retry a failed preparation",
                failed, RomPreparation.ensureReady(context));
        assertTrue("Could not persist a bundled ROM request", AppKV.requestRomInstall(context, false));
        assertTrue(AppKV.getBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false));
        assertFalse(AppKV.getBooleanConfig(context, AppKV.SHOULD_USE_THIRD_PARTY_ROM, true));
        RomPreparation.retry(context).get(180, TimeUnit.SECONDS);
        assertFalse("Successful preparation must clear the reinstall request",
                AppKV.getBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, true));
        assertFalse("Retried installation must be finalized", RomManager.recoverInstall(context));
        assertBootFiles(root);

        String markerName = ".rom-install-test-" + UUID.randomUUID();
        Path dataMarker = Files.createDirectories(root.resolve("data")).resolve(markerName);
        Path sdcardMarker = Files.createDirectories(root.resolve("sdcard")).resolve(markerName);
        byte[] data = {1, 2, 3};
        byte[] sdcard = {4, 5, 6};
        Files.write(dataMarker, data);
        Files.write(sdcardMarker, sdcard);
        try {
            RomManager.installRootfs(context, false);
            assertTrue("Replacement switch must be recoverable before finalization", RomManager.recoverInstall(context));
            RomManager.finishInstall(context);
            assertBootFiles(root);
            assertArrayEquals(data, Files.readAllBytes(dataMarker));
            assertArrayEquals(sdcard, Files.readAllBytes(sdcardMarker));
            assertFalse("Finalized transaction must no longer be pending", RomManager.recoverInstall(context));
        } finally {
            Files.deleteIfExists(dataMarker);
            Files.deleteIfExists(sdcardMarker);
        }
    }

    private static void assertBootFiles(Path root) {
        assertTrue(Files.isRegularFile(root.resolve("init")));
        assertTrue(Files.isDirectory(root.resolve("system")));
        assertTrue(Files.isDirectory(root.resolve("dev/input")));
    }
}
