/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.twoyi.R;
import io.twoyi.TwoyiStatusManager;

public class Installer {

    public interface InstallResult {
        void onSuccess(List<File> files);

        void onFail(List<File> files, String msg);
    }

    private static final String TAG = "Installer";

    public static final int REQUEST_INSTALL_APP = 101;

    public static void installAsync(Context context, String path, InstallResult callback) {
        installAsync(context, Collections.singletonList(new File(path)), callback);
    }

    public static void installAsync(Context context, List<File> files, InstallResult callback) {
        new Thread(() -> install(context, files, callback)).start();
    }

    public static void install(Context context, List<File> files, InstallResult callback) {
        GuestMaintenance maintenance = GuestMaintenance.SESSION;
        if (!maintenance.beginInstall()) {
            if (callback != null) callback.onFail(files, context.getString(R.string.guest_maintenance_busy));
            return;
        }
        String failure;
        try {
            failure = installFiles(context, files);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not install APKs", e);
            failure = e.toString();
        } finally {
            maintenance.endInstall();
        }
        if (callback != null) {
            if (failure == null) callback.onSuccess(files);
            else callback.onFail(files, failure);
        }
    }

    private static String installFiles(Context context, List<File> files) {
        if (files == null || files.isEmpty()) return "No APKs selected";
        if (!TwoyiStatusManager.getInstance().isStarted()) {
            return context.getString(R.string.guest_install_requires_running);
        }

        String adb = new File(context.getApplicationInfo().nativeLibraryDir, "libadb.so").getAbsolutePath();
        File cache = context.getCacheDir();
        String target = "localhost:22122";
        File directory = null;
        Process server = null;
        File socket = null;
        String endpoint = null;
        boolean clientInvoked = false;
        try {
            directory = Files.createTempDirectory(cache.toPath(), "adb-").toFile();
            socket = new File(directory, "server");
            String socketPath = socket.getAbsolutePath();
            // A pathname socket needs room for its terminating NUL in sun_path.
            if (socketPath.getBytes(StandardCharsets.UTF_8).length >= 108) {
                return "Private ADB socket path is too long";
            }
            endpoint = "localfilesystem:" + socketPath;
            server = adbCommand(cache, adb, endpoint, Arrays.asList("nodaemon", "server"))
                    .redirectOutput(new File("/dev/null")).start();
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (!socket.exists() && server.isAlive()
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(25);
            }
            if (!socket.exists() || !server.isAlive()) return "Could not start the private ADB server";

            clientInvoked = true;
            String result = runAdb(cache, adb, endpoint, Arrays.asList("connect", target), 15);
            if (!server.isAlive()) return "The private ADB server exited during connection";
            if (!containsLine(result, "connected to " + target)
                    && !containsLine(result, "already connected to " + target)) {
                return "Could not connect to the guest: " + result;
            }
            List<String> arguments = new ArrayList<>(Arrays.asList("-s", target,
                    files.size() == 1 ? "install" : "install-multiple", "-t", "-r"));
            for (File file : files) arguments.add(file.getAbsolutePath());
            String output;
            try {
                output = runAdb(cache, adb, endpoint, arguments, 180);
            } catch (IOException e) {
                return "Guest APK install failed: " + e.getMessage();
            }
            return containsLine(output, "Success") ? null
                    : "Guest ADB did not confirm installation: " + output;
        } catch (IOException e) {
            return e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Installation interrupted";
        } finally {
            stopProcess(server);
            // A client can auto-start a replacement daemon if the tracked server died.
            // Address only this installation's socket, never the host's default server.
            if (clientInvoked && socket.exists()) {
                boolean interrupted = Thread.interrupted();
                try {
                    runAdb(cache, adb, endpoint, Collections.singletonList("kill-server"), 3);
                } catch (IOException e) {
                    Log.d(TAG, "Private ADB daemon unavailable during cleanup", e);
                } catch (InterruptedException e) {
                    interrupted = true;
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            if (socket != null && socket.exists() && !socket.delete()) {
                Log.w(TAG, "Could not remove private ADB socket");
            }
            if (directory != null && !directory.delete()) {
                Log.w(TAG, "Could not remove temporary ADB directory");
            }
        }
    }

    private static boolean containsLine(String output, String expected) {
        for (String line : output.split("\\r?\\n")) {
            if (expected.equals(line.trim())) return true;
        }
        return false;
    }

    private static ProcessBuilder adbCommand(File cache, String adb, String endpoint, List<String> arguments) {
        List<String> command = new ArrayList<>(Arrays.asList(adb, "-L", endpoint));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", cache.getAbsolutePath());
        builder.environment().put("TMPDIR", cache.getAbsolutePath());
        return builder;
    }

    private static String runAdb(File cache, String adb, String endpoint, List<String> arguments,
                                 long timeoutSeconds) throws IOException, InterruptedException {
        Process process = adbCommand(cache, adb, endpoint, arguments).start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    output.append(buffer, 0, count);
                    if (output.length() > 4096) output.delete(0, output.length() - 4096);
                }
            } catch (IOException e) {
                Log.w(TAG, "Could not read ADB command output", e);
            }
        }, "adb-install-output");
        reader.setDaemon(true);
        try {
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Guest ADB command timed out");
            }
            reader.join(1000);
            if (reader.isAlive()) throw new IOException("Guest ADB output did not finish");
            if (process.exitValue() != 0) {
                throw new IOException("ADB exited with " + process.exitValue() + "\n" + output);
            }
            return output.toString();
        } finally {
            stopProcess(process);
        }
    }

    private static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public static boolean checkFile(Context context, List<File> files) {
        if (files == null || files.isEmpty()) return false;
        if (files.size() == 1) return checkFile(context, files.get(0).getAbsolutePath());

        PackageManager packages = context.getPackageManager();
        if (packages == null) return false;
        String packageName = null;
        for (File file : files) {
            PackageInfo info = packages.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info == null) {
                // The public archive parser expects a base APK. Check the ZIP
                // envelope here and let the guest validate the split manifest.
                if (containsApkManifest(file)) continue;
                showValidationError(context, R.string.check_file_invlid_apk);
                return false;
            }
            if (TextUtils.equals(info.packageName, context.getPackageName())) {
                showValidationError(context, R.string.check_file_create_self_tip);
                return false;
            }
            if (packageName != null && !packageName.equals(info.packageName)) {
                showValidationError(context, R.string.check_file_invlid_apk);
                return false;
            }
            packageName = info.packageName;
        }
        if (packageName != null) return true;
        showValidationError(context, R.string.check_file_invlid_apk);
        return false;
    }

    private static boolean containsApkManifest(File file) {
        try (ZipFile apk = new ZipFile(file)) {
            ZipEntry manifest = apk.getEntry("AndroidManifest.xml");
            return manifest != null && !manifest.isDirectory() && manifest.getSize() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void showValidationError(Context context, int message) {
        Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show());
    }

    public static boolean checkFile(Context context, String path) {
        if (path == null) {
            return false;
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return false;
        }

        PackageInfo packageInfo = pm.getPackageArchiveInfo(path, 0);

        if (packageInfo == null) {
            showValidationError(context, R.string.check_file_invlid_apk);
            return false;
        }

        String packageName = packageInfo.packageName;

        if (TextUtils.equals(packageName, context.getPackageName())) {
            showValidationError(context, R.string.check_file_create_self_tip);
            return false;
        }

        return true;
    }
}
