/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;

import com.hzy.libp7zip.P7ZipApi;
import io.twoyi.Renderer;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * @author weishu
 * @date 2021/10/22.
 */

public final class RomManager {

    private static final String TAG = "RomManager";

    private static final String ROOTFS_NAME = "rootfs.7z";

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.7z";

    private RomManager() {
    }

    private static void initStagedRootfs(Path root) throws IOException {
        requireRealDirectory(root);
        requireRealDirectory(root.resolve("system"));
        Path vendor = root.resolve("vendor");
        if (Files.isSymbolicLink(vendor)) {
            Path target = Files.readSymbolicLink(vendor);
            if (!target.equals(Paths.get("system/vendor")) && !target.equals(Paths.get("/system/vendor"))) {
                throw new IOException("Unsupported guest vendor link: " + target);
            }
            // Resolve Android's legacy vendor alias inside the staged system.
            vendor = root.resolve("system/vendor");
        }
        requireRealDirectory(vendor);
        if (!Files.isRegularFile(root.resolve("init"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("ROM has no regular init");
        Path propFile = vendor.resolve("default.prop");
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        // Android property files also support import directives and literal values.
        // Preserve those lines without passing them through java.util.Properties.
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("persist.sys.language", language);
        overrides.put("persist.sys.country", country);

        TimeZone timeZone = TimeZone.getDefault();
        String timeZoneID = timeZone.getID();
        Log.i(TAG, "timezone: " + timeZoneID);
        overrides.put("persist.sys.timezone", timeZoneID);
        overrides.put("ro.sf.lcd_density", String.valueOf(DisplayMetrics.DENSITY_DEVICE_STABLE));

        Path tmp = Files.createTempFile(propFile.getParent(), ".default-prop-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
                 BufferedReader reader = Files.isRegularFile(propFile, LinkOption.NOFOLLOW_LINKS)
                         ? new BufferedReader(new InputStreamReader(
                                 Files.newInputStream(propFile, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))
                         : null) {
                AndroidPropertyFile.merge(reader, writer, overrides);
            }
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            Files.move(tmp, propFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void requireRealDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a ROM directory: " + path);
    }

    public static void ensureGuestDevices(Context context) throws IOException {
        ensureGuestDevices(getRootfsDir(context).toPath());
    }

    private static void ensureGuestDevices(Path root) throws IOException {
        requireRealDirectory(root);
        for (String name : new String[]{"dev", "dev/input", "dev/socket", "dev/maps"}) {
            Path path = root.resolve(name);
            if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
            requireRealDirectory(path);
        }
    }

    public static void ensureBootFiles(Context context) {
        // attachBaseContext runs before recovery. Do not create anything in rootfs here.
        File base = getSlotBaseDir(context);
        ensureDir(new File(base, "socket"));

        createLoaderSymlink(context);

        saveLastKmsg(context);
    }

    /** Directory owning this slot's rootfs, log and sockets; slot 0 is the data dir. */
    public static File getSlotBaseDir(Context context) {
        return io.twoyi.GuestProcessProfile.slotDirectory(context);
    }

    /** Same directory for an explicitly chosen slot, used when preparing another slot. */
    public static File getSlotBaseDir(Context context, int slot) {
        return io.twoyi.GuestSlotLayout.baseDirectory(context.getApplicationContext().getDataDir(), slot);
    }

    private static void createLoaderSymlink(Context context) {
        Path loaderSymlink = new File(getSlotBaseDir(context), "loader64").toPath();
        String loaderPath = getLoaderPath(context);
        try {
            Files.deleteIfExists(loaderSymlink);
            Files.createSymbolicLink(loaderSymlink, Paths.get(loaderPath));
        } catch (IOException e) {
            throw new RuntimeException("symlink loader failed.", e);
        }
    }

    private static void saveLastKmsg(Context context) {
        File lastKmsgFile = LogEvents.getLastKmsgFile(context);
        File kmsgFile = LogEvents.getKmsgFile(context);
        try {
            Files.move(kmsgFile.toPath(), lastKmsgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static class RomInfo {
        public String author = DEFAULT_INFO;
        public String version = DEFAULT_INFO;
        public String desc = DEFAULT_INFO;
        public String md5 = "";
        public long code = 0;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "author='" + author + '\'' +
                    ", version='" + version + '\'' +
                    ", md5='" + md5 + '\'' +
                    ", code=" + code +
                    '}';
        }

        public boolean isValid() {
            return this != DEFAULT_ROM_INFO;
        }
    }

    public static final RomInfo DEFAULT_ROM_INFO = new RomInfo();

    public static boolean romExist(Context context) {
        Path root = getRootfsDir(context).toPath();
        // Use the same boot prerequisites as staged installation and native launch.
        return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(root.resolve("system"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(root.resolve("init"), LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean needsUpgrade(Context context) {
        RomInfo currentRomInfo = getCurrentRomInfo(context);
        Log.i(TAG, "current rom: " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    public static String getLoaderPath(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        return new File(applicationInfo.nativeLibraryDir, LOADER_FILE).getAbsolutePath();
    }

    public static RomInfo getRomInfo(File rom) {
        try (SevenZFile zFile = new SevenZFile(rom)) {

            SevenZArchiveEntry entry;

            while ((entry = zFile.getNextEntry()) != null) {
                if ("rootfs/rom.ini".equals(entry.getName())) {
                    long size = entry.getSize();
                    if (entry.isDirectory() || size <= 0 || size > 64 * 1024) {
                        throw new IOException("Invalid ROM metadata size");
                    }
                    byte[] content = new byte[(int) size];
                    int offset = 0;
                    while (offset < content.length) {
                        int count = zFile.read(content, offset, content.length - offset);
                        if (count <= 0) throw new IOException("Incomplete ROM metadata");
                        offset += count;
                    }
                    return getRomInfo(new ByteArrayInputStream(content));
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        }
        return DEFAULT_ROM_INFO;
    }

    public static RomInfo getRomInfoFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream open = assets.open(ROM_INFO_FILE)) {
            return getRomInfo(open);
        } catch (Throwable ignored) {
        }
        return DEFAULT_ROM_INFO;
    }

    public static boolean recoverInstall(Context context) throws IOException {
        RomInstallTransaction t = RomInstallTransaction.current(getSlotBaseDir(context).toPath());
        return t != null && t.recover();
    }

    public static void finishInstall(Context context) throws IOException {
        RomInstallTransaction t = RomInstallTransaction.current(getSlotBaseDir(context).toPath());
        if (t == null) throw new IOException("Missing ROM install journal");
        t.finish();
    }

    public static void installRootfs(Context context, boolean thirdParty) throws IOException {
        installRootfsInto(context, io.twoyi.GuestProcessProfile.slot(), thirdParty);
    }

    /**
     * Install into one explicit slot. Only one transaction may exist per slot, so
     * preparing a second slot must not touch the slot owned by this process.
     */
    public static void installRootfsForSlot(Context context, int slot, boolean thirdParty) throws IOException {
        installRootfsInto(context, slot, thirdParty);
    }

    private static void installRootfsInto(Context context, int slot, boolean thirdParty) throws IOException {
        // The transaction journal needs a real parent directory; a fresh slot has
        // none yet, so create it first instead of failing with ENOENT.
        File base = io.twoyi.GuestSlotLayout.ensureBaseDirectory(
                context.getApplicationContext().getDataDir(), slot);
        RomInstallTransaction t = RomInstallTransaction.begin(base.toPath());
        Path work = t.extractionDirectory();
        Path archive = work.resolve("input.7z");
        if (thirdParty) {
            // Settings publishes the selected archive by rename. Pin that inode
            // so preflight and the native extractor see the very same file.
            Files.createLink(archive, get3rdRootfsFile(context).toPath());
        } else {
            try (InputStream input = context.getAssets().open(ROOTFS_NAME)) {
                Files.copy(input, archive);
            }
        }
        RomArchiveBudget budget = RomArchivePreflight.inspect(archive);
        budget.checkAvailable(base.getUsableSpace());
        int code = P7ZipApi.executeCommand(String.format(Locale.US, "7z x -mmt=%d -aoa '%s' '-o%s'",
                Runtime.getRuntime().availableProcessors(), archive, work));
        if (code != 0) throw new IOException("ROM extraction failed: " + code);
        initStagedRootfs(work.resolve("rootfs"));
        ensureGuestDevices(work.resolve("rootfs"));
        t.prepare();
        t.recover();
    }

    public static void reboot(Context context) {
        stopSession(context, true);
    }

    public static void shutdown(Context context) {
        stopSession(context, false);
    }

    private static void stopSession(Context context, boolean restart) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        GuestMaintenance maintenance = GuestMaintenance.SESSION;
        if (!maintenance.beginShutdown()) {
            showSessionMessage(app, io.twoyi.R.string.guest_maintenance_busy);
            return;
        }
        new Thread(() -> {
            if (Renderer.stopGuest() == 0) {
                maintenance.shutdownFailed();
                showSessionMessage(app, io.twoyi.R.string.guest_shutdown_failed);
                return;
            }
            if (restart) {
                Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                AlarmManager alarms = app.getSystemService(AlarmManager.class);
                if (launch != null && alarms != null) {
                    PendingIntent pending = PendingIntent.getActivity(app, 0, launch,
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    try {
                        alarms.setExact(AlarmManager.ELAPSED_REALTIME,
                                SystemClock.elapsedRealtime() + 1000, pending);
                    } catch (SecurityException e) {
                        Log.w(TAG, "Automatic guest restart unavailable; reopen Twoyi", e);
                    }
                }
            }
            // The legacy renderer lives for the lifetime of the host process.
            Process.killProcess(Process.myPid());
        }, "stop-guest").start();
    }

    private static void showSessionMessage(Context app, int message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }

    public static File getRootfsDir(Context context) {
        return new File(getSlotBaseDir(context), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static RomInfo getRomInfo(InputStream in) {
        Properties prop = new Properties();
        try {
            prop.load(in);

            RomInfo info = new RomInfo();
            info.author = prop.getProperty("author");
            info.code = Long.parseLong(prop.getProperty("code"));
            info.version = prop.getProperty("version");
            info.desc = prop.getProperty("desc", DEFAULT_INFO);
            info.md5 = prop.getProperty("md5");
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "read rom info err", e);
            return DEFAULT_ROM_INFO;
        }
    }

    private static void ensureDir(File file) {
        if (file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
    }
}
