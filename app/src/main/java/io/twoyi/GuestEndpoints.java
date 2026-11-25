/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.twoyi;

import java.io.File;
import java.util.Objects;

/**
 * Immutable host/guest endpoint configuration for a single container instance.
 * Defaults preserve exact legacy 0.5.4 paths so existing ROMs continue working
 * while removing hardcoded paths from native and Java startup code.
 */
public final class GuestEndpoints {
    public static final String DEFAULT_CONTROL_SOCKET = "TWOYI_SOCK";
    public static final String DEFAULT_HAL_SOCKET = "twoyi_hal";

    private final String rootfsDir;
    private final String logFile;
    private final String touchSocketPath;
    private final String keySocketPath;
    private final String controlSocketName;
    private final String halSocketName;

    public GuestEndpoints(
            String rootfsDir,
            String logFile,
            String touchSocketPath,
            String keySocketPath,
            String controlSocketName,
            String halSocketName) {
        this.rootfsDir = requirePath(rootfsDir, "rootfsDir");
        this.logFile = requirePath(logFile, "logFile");
        this.touchSocketPath = requireInputPath(touchSocketPath, "touchSocketPath");
        this.keySocketPath = requireInputPath(keySocketPath, "keySocketPath");
        if (samePath(this.touchSocketPath, this.keySocketPath)
                || samePath(this.logFile, this.touchSocketPath)
                || samePath(this.logFile, this.keySocketPath)) {
            throw new IllegalArgumentException("Guest log and input sockets need distinct paths");
        }
        this.controlSocketName = requireSocketName(controlSocketName, "controlSocketName");
        this.halSocketName = requireSocketName(halSocketName, "halSocketName");
        if (this.controlSocketName.equals(this.halSocketName)) {
            throw new IllegalArgumentException("Control and HAL sockets need distinct names");
        }
    }

    public static GuestEndpoints forDataDir(File dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        File rootfs = new File(dataDir, "rootfs");
        File inputDir = new File(new File(rootfs, "dev"), "input");
        return new GuestEndpoints(
                rootfs.getAbsolutePath(),
                new File(dataDir, "log.txt").getAbsolutePath(),
                new File(inputDir, "touch").getAbsolutePath(),
                new File(inputDir, "key0").getAbsolutePath(),
                DEFAULT_CONTROL_SOCKET,
                DEFAULT_HAL_SOCKET
        );
    }

    public String getRootfsDir() { return rootfsDir; }
    public String getLogFile() { return logFile; }
    public String getTouchSocketPath() { return touchSocketPath; }
    public String getKeySocketPath() { return keySocketPath; }
    public String getControlSocketName() { return controlSocketName; }
    public String getHalSocketName() { return halSocketName; }

    private static boolean samePath(String a, String b) {
        return new File(a).toPath().normalize().equals(new File(b).toPath().normalize());
    }

    private static String requireSocketName(String value, String name) {
        requireNonEmpty(value, name);
        // sun_path is 108 bytes, including the leading abstract-namespace NUL.
        if (value.indexOf('\0') >= 0
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 107) {
            throw new IllegalArgumentException(name + " exceeds abstract socket name limits");
        }
        return value;
    }

    private static String requirePath(String value, String name) {
        requireNonEmpty(value, name);
        if (!new File(value).isAbsolute() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " requires an absolute filesystem path");
        }
        return value;
    }

    private static String requireInputPath(String value, String name) {
        requirePath(value, name);
        // The legacy device_info.physical_location field has 80 bytes including NUL.
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 80) {
            throw new IllegalArgumentException(name + " exceeds the legacy device descriptor");
        }
        return value;
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GuestEndpoints)) return false;
        GuestEndpoints that = (GuestEndpoints) o;
        return rootfsDir.equals(that.rootfsDir)
                && logFile.equals(that.logFile)
                && touchSocketPath.equals(that.touchSocketPath)
                && keySocketPath.equals(that.keySocketPath)
                && controlSocketName.equals(that.controlSocketName)
                && halSocketName.equals(that.halSocketName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootfsDir, logFile, touchSocketPath, keySocketPath, controlSocketName, halSocketName);
    }

    @Override
    public String toString() {
        return "GuestEndpoints{rootfs=" + rootfsDir
                + ", log=" + logFile
                + ", touch=" + touchSocketPath
                + ", key=" + keySocketPath
                + ", control=@" + controlSocketName
                + ", hal=@" + halSocketName + "}";
    }
}
