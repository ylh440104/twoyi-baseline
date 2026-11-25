/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.twoyi.utils.AppKV;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class GuestNameTest {
    @Test
    public void savesNormalizedNameAndRestoresDefault() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String original = AppKV.getGuestName(context);
        String defaultName = context.getString(R.string.app_name);
        try {
            assertTrue(AppKV.setGuestName(context, "  测试系统  "));
            assertEquals("测试系统", AppKV.getGuestName(context));
            assertFalse(AppKV.setGuestName(context, "first\nsecond"));
            assertFalse(AppKV.setGuestName(context, "01234567890123456789012345678901234567890"));
            assertEquals("测试系统", AppKV.getGuestName(context));
            assertTrue(AppKV.setGuestName(context, "  "));
            assertEquals(defaultName, AppKV.getGuestName(context));
        } finally {
            assertTrue(AppKV.setGuestName(context, original.equals(defaultName) ? "" : original));
        }
    }
}
