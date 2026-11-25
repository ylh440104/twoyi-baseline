/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.twoyi;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.GuestMaintenance;
import io.twoyi.utils.RomManager;

/** One preparation task per host process, independent of Activity lifetime. */
final class RomPreparation {
    private static CompletableFuture<Void> preparation;
    private static volatile boolean extracting;

    static boolean isExtracting() { return extracting; }

    static synchronized CompletableFuture<Void> retry(Context context) {
        if (preparation != null && preparation.isCompletedExceptionally()) {
            preparation = null;
        }
        return ensureReady(context);
    }

    static synchronized CompletableFuture<Void> ensureReady(Context context) {
        if (preparation != null) return preparation;
        Context app = context.getApplicationContext();
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!GuestMaintenance.beginSessionRomPreparation()) {
            result.completeExceptionally(new IllegalStateException(
                    app.getString(R.string.guest_maintenance_busy)));
            return result;
        }
        preparation = result;
        try {
            new Thread(() -> {
                Throwable failure = null;
                try {
                    if (!TwoyiSocketServer.getInstance(app).awaitListening(5000)) {
                        throw new IOException("Guest control socket is not listening");
                    }
                    prepare(app);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = e;
                } catch (Exception | LinkageError e) {
                    // Keep the failed result until the user explicitly retries.
                    failure = e;
                } finally {
                    extracting = false;
                    GuestMaintenance.endSessionRomPreparation();
                }
                if (failure == null) result.complete(null);
                else result.completeExceptionally(failure);
            }, "prepare-rom").start();
        } catch (RuntimeException e) {
            GuestMaintenance.endSessionRomPreparation();
            preparation = null;
            result.completeExceptionally(e);
        }
        return result;
    }

    private static void prepare(Context context) throws IOException {
        if (TwoyiStatusManager.getInstance().isStarted()) {
            return;
        }
        File pending = new File(context.getFilesDir(), "rom-install.pending");
        // Complete any published commit before interpreting an absent rootfs as first install.
        if (RomManager.recoverInstall(context)) {
            completeInstall(context, pending);
            return;
        }
        boolean exists = RomManager.romExist(context);
        boolean upgrade = RomManager.needsUpgrade(context);
        boolean forced = pending.exists() || AppKV.getBooleanConfig(context,
                AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean thirdParty = AppKV.getBooleanConfig(context,
                AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);
        if (!exists || forced || (!thirdParty && upgrade)) {
            extracting = true;
            Files.write(pending.toPath(), new byte[]{1});
            RomManager.installRootfs(context, forced && thirdParty);
            completeInstall(context, pending);
        } else {
            RomManager.ensureGuestDevices(context);
        }
    }

    private static void completeInstall(Context context, File pending) throws IOException {
        RomManager.ensureGuestDevices(context);
        if (!AppKV.completeRomInstallRequest(context)) {
            throw new IOException("Could not persist the completed ROM installation");
        }
        Files.deleteIfExists(pending.toPath());
        RomManager.finishInstall(context);
    }
}
