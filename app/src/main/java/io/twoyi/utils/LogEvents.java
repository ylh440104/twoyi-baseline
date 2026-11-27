/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.microsoft.appcenter.crashes.Crashes;
import com.microsoft.appcenter.crashes.ingestion.models.ErrorAttachmentLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author weishu
 * @date 2022/2/16.
 */

public class LogEvents {

    private static final RuntimeException BOOT_FAILURE = new RuntimeException("BootFailureException");

    public static void trackError(Throwable e) {
        Crashes.trackError(e);
    }
    public static void trackError(Throwable e, Map<String, String> properties, Iterable<ErrorAttachmentLog> attachments) {
        Crashes.trackError(e, properties, attachments);
    }

    public static void trackBootFailure(Context context) {

        Map<String, String> properties = new HashMap<>();
        RomManager.RomInfo info = RomManager.getCurrentRomInfo(context);

        properties.put("rom_ver", String.valueOf(info.code));
        properties.put("rom_author", info.author);
        properties.put("rom_md5", info.md5);

        List<ErrorAttachmentLog> errors = new ArrayList<>();

        errors.add(ErrorAttachmentLog.attachmentWithBinary(getBugreport(context), "bugreport.zip", "application/zip"));

        trackError(BOOT_FAILURE, properties, errors);
    }

    public static File getLogcatFile(Context context) {
        return new File(context.getCacheDir(), "logcat.txt");
    }

    public static File getKmsgFile(Context context) {
        return new File(RomManager.getSlotBaseDir(context), "log.txt");
    }

    public static File getLastKmsgFile(Context context) {
        return new File(RomManager.getSlotBaseDir(context), "last_kmsg.txt");
    }

    private static class ReportItem {
        File file;
        String entry;

        public static ReportItem create(File file, String entry) {
            ReportItem item = new ReportItem();
            item.file = file;
            item.entry = entry;
            return item;
        }

        public static ReportItem create(File file) {
            return create(file, file.getName());
        }
    }

    private static void addGuestReports(List<ReportItem> reports, Path directory, String prefix) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            int inspected = 0;
            for (Path entry : entries) {
                if (++inspected > 64) break;
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    reports.add(ReportItem.create(entry.toFile(), prefix + entry.getFileName()));
                }
            }
        } catch (IOException | DirectoryIteratorException ignored) {
            // Diagnostics may disappear while the guest is shutting down.
        }
    }

    private static boolean captureReport(File output, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(output).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException ignored) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    public static Uri createShareableReport(Context context) throws IOException {
        byte[] report = getBugreport(context);
        Path directory = context.getCacheDir().toPath().resolve("diagnostics");
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostics directory is unavailable");
        }
        Path file = Files.createTempFile(directory, "bugreport-", ".zip");
        try {
            Files.write(file, report);
            return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file.toFile());
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    // Collection uses shared cache paths for command output.
    public static synchronized byte[] getBugreport(Context context) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zout = new ZipOutputStream(baos);

        // file, entry
        List<ReportItem> reportItems = new ArrayList<>();

        // current kmsg log
        File initLogFile = getKmsgFile(context);
        reportItems.add(ReportItem.create(initLogFile, "kmsg.txt"));

        // last kmsg log
        File lastKmsgFile = getLastKmsgFile(context);
        reportItems.add(ReportItem.create(lastKmsgFile));

        // what the guest image check concluded before the boot attempt
        File preflightFile = new File(context.getCacheDir(), "preflight.txt");
        if (preflightFile.isFile()) {
            reportItems.add(ReportItem.create(preflightFile));
        }

        // logcat
        File logcatFile = getLogcatFile(context);
        if (captureReport(logcatFile, "logcat", "-d", "-t", "2000")) {
            reportItems.add(ReportItem.create(logcatFile));
        }

        // tombstones
        File rootfsDir = RomManager.getRootfsDir(context);
        File romDataDir = new File(rootfsDir, "data");
        boolean guestDataAvailable = Files.isDirectory(rootfsDir.toPath(), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(romDataDir.toPath(), LinkOption.NOFOLLOW_LINKS);
        if (guestDataAvailable) {
            Path data = romDataDir.toPath();
            addGuestReports(reportItems, data.resolve("tombstones"), "tombstones/");
            Path system = data.resolve("system");
            if (Files.isDirectory(system, LinkOption.NOFOLLOW_LINKS)) {
                addGuestReports(reportItems, system.resolve("dropbox"), "dropbox/");
            }
        }

        // proc info
        File procInfo = new File(context.getCacheDir(), "proc.txt");
        if (captureReport(procInfo, "ps", "-ef")) {
            reportItems.add(ReportItem.create(procInfo));
        }

        // build.prop
        File buildInfo = new File(context.getCacheDir(), "basic.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(buildInfo))) {
            pw.println("BRAND: " + Build.BRAND);
            pw.println("MODEL: " + Build.MODEL);
            pw.println("PRODUCT: " + Build.PRODUCT);
            pw.println("MANUFACTURER: " + Build.MANUFACTURER);
            pw.println("SDK: " + Build.VERSION.SDK_INT);
            pw.println("PREVIEW_SDK: " + Build.VERSION.PREVIEW_SDK_INT);
            pw.println("FINGERPRINT: " + Build.FINGERPRINT);
            pw.println("DEVICE: " + Build.DEVICE);

            RomManager.RomInfo romInfo = RomManager.getCurrentRomInfo(context);
            pw.println("ROM: " + romInfo.version);
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            pw.println("PACKAGE: " + packageInfo.packageName);
            pw.println("VERSION: " + packageInfo.versionName);
        } catch (Throwable ignored) {}

        reportItems.add(ReportItem.create(buildInfo));

        // Reserve room for one local ZIP header and central-directory record
        // per possible report item, keeping both compressed and stored ZIPs bounded.
        DiagnosticArchiveBudget budget = new DiagnosticArchiveBudget(reportItems.size());
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        for (ReportItem item : reportItems) {
            if (budget.remaining() == 0) break;
            Path path = item.file.toPath();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
            try (SeekableByteChannel input = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ZipEntry archiveEntry = new ZipEntry(item.entry);
                archiveEntry.setExtra(new byte[0]);
                zout.putNextEntry(archiveEntry);
                try {
                    long limit = budget.nextEntryLimit();
                    // Keep the most recent guest and host log lines when truncating.
                    if (item.file.equals(initLogFile) || item.file.equals(lastKmsgFile)
                            || item.file.equals(logcatFile)) {
                        input.position(Math.max(0, input.size() - limit));
                    }
                    while (limit > 0) {
                        buffer.clear();
                        buffer.limit((int) Math.min(limit, buffer.capacity()));
                        int count = input.read(buffer);
                        if (count <= 0) break;
                        zout.write(buffer.array(), 0, count);
                        limit -= count;
                        budget.recordEntryBytes(count);
                    }
                } finally {
                    zout.closeEntry();
                }
            } catch (IOException ignored) {
                // Missing or unreadable diagnostics should not prevent exporting the rest.
            }
        }
        IOUtils.closeSilently(zout);

        return baos.toByteArray();
    }
}
