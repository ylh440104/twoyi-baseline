/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.twoyi;

import java.io.File;
import java.util.Objects;

/**
 * Reserves one guest instance slot. The newest Twoyi build runs each guest in
 * its own process, so every slot owns its data directory, endpoints and log.
 * This type only describes and validates that separation; it does not start
 * the guest.
 */
public final class GuestSlot {
    public static final int SLOT_COUNT = 4;
    public static final String PROCESS_PREFIX = ":guest";

    private final int index;
    private final String instanceId;
    private final GuestEndpoints endpoints;

    private GuestSlot(int index, String instanceId, GuestEndpoints endpoints) {
        this.index = index;
        this.instanceId = instanceId;
        this.endpoints = endpoints;
    }

    public static GuestSlot of(int index, String instanceId, File slotDataDir) {
        if (index < 0 || index >= SLOT_COUNT) {
            throw new IllegalArgumentException("Guest slot index must be between 0 and " + (SLOT_COUNT - 1));
        }
        if (instanceId == null || instanceId.trim().isEmpty() || instanceId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Guest instance id is required");
        }
        Objects.requireNonNull(slotDataDir, "slotDataDir");
        if (!slotDataDir.isAbsolute()) {
            throw new IllegalArgumentException("Guest slot directory requires an absolute path");
        }
        return new GuestSlot(index, instanceId, GuestEndpoints.forSlot(index, slotDataDir));
    }

    public int getIndex() { return index; }
    public String getInstanceId() { return instanceId; }
    public GuestEndpoints getEndpoints() { return endpoints; }
    public String getProcessName() { return PROCESS_PREFIX + index; }

    /** Distinct slots must never resolve to the same guest directory or sockets. */
    public static void requireDistinct(GuestSlot... slots) {
        for (int i = 0; i < slots.length; i++) {
            for (int j = i + 1; j < slots.length; j++) {
                if (slots[i].getIndex() == slots[j].getIndex()) {
                    throw new IllegalArgumentException("Duplicate guest slot index " + slots[i].getIndex());
                }
                // Endpoint equality is not enough: two slots legitimately differ only
                // in abstract socket names while still sharing one guest directory.
                if (slots[i].getEndpoints().getRootfsDir().equals(slots[j].getEndpoints().getRootfsDir())) {
                    throw new IllegalArgumentException("Guest slots share a rootfs: " + slots[i].getIndex()
                            + " and " + slots[j].getIndex());
                }
                if (slots[i].getEndpoints().getLogFile().equals(slots[j].getEndpoints().getLogFile())
                        || slots[i].getEndpoints().getTouchSocketPath()
                                .equals(slots[j].getEndpoints().getTouchSocketPath())) {
                    throw new IllegalArgumentException("Guest slots share guest files: "
                            + slots[i].getIndex() + " and " + slots[j].getIndex());
                }
            }
        }
    }

    @Override
    public String toString() {
        return "GuestSlot{index=" + index + ", id=" + instanceId + ", " + endpoints + "}";
    }
}
