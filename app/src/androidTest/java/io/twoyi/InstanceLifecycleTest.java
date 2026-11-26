/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs the instance lifecycle against the real app data directory. The host
 * regression proves the logic on a temp dir; this proves the same flow works with
 * the real permissions, layout and slot 0 legacy path on a device.
 */
@RunWith(AndroidJUnit4.class)
public class InstanceLifecycleTest {
    @Test
    public void createGateAndReleaseUseRealSlotDirectories() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path dataDir = context.getDataDir().toPath();
        Path stateFile = GuestInstanceRegistry.defaultPath(dataDir);
        String id = "twin-test-" + UUID.randomUUID().toString().substring(0, 8);
        int createdSlot = -1;
        boolean created = false;
        try {
            GuestInstanceRegistry registry = GuestInstanceRegistry.load(stateFile);
            createdSlot = GuestInstanceManager.create(dataDir, registry, id);
            created = true;
            assertTrue("Slot index out of range: " + createdSlot,
                    createdSlot >= 0 && createdSlot < GuestSlot.SLOT_COUNT);

            // A fresh load must see the reservation.
            assertTrue(GuestInstanceRegistry.load(stateFile).instances().contains(id));

            File slotBase = GuestSlotLayout.baseDirectory(dataDir.toFile(), createdSlot);
            File rootfs = new File(slotBase, "rootfs");
            boolean romWasThere = rootfs.isDirectory();

            GuestInstanceRegistry reloaded = GuestInstanceRegistry.load(stateFile);
            assertEquals(romWasThere ? GuestInstanceGate.Decision.STARTABLE
                            : GuestInstanceGate.Decision.NO_ROM,
                    GuestInstanceGate.evaluate(dataDir.toFile(), reloaded, id));

            List<String> removed = GuestInstanceManager.release(dataDir, reloaded, id, true);
            created = false;
            assertFalse(GuestInstanceRegistry.load(stateFile).instances().contains(id));
            if (romWasThere) {
                assertTrue("An existing ROM tree was not removed: " + removed, removed.contains("rootfs"));
                assertFalse("Slot ROM tree survived a wiping release", rootfs.exists());
            } else {
                assertTrue("Release wiped something it should not have: " + removed, removed.isEmpty());
            }
            assertEquals("Slot 0 must keep the legacy data directory",
                    dataDir.toFile(), GuestSlotLayout.baseDirectory(dataDir.toFile(), 0));
        } finally {
            if (created) {
                try {
                    GuestInstanceRegistry current = GuestInstanceRegistry.load(stateFile);
                    GuestInstanceManager.release(dataDir, current, id, false);
                } catch (Exception ignored) {
                    // Best-effort cleanup; the assertions above already ran.
                }
            }
        }
    }
}