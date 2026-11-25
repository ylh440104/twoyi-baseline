/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * @author weishu
 * @date 2019/2/28.
 */
public class AppKV {


    private static final String PREF_NAME = "app_kv";

    public static final String ADD_APP_NOT_SHOW_SYSTEM= "add_app_not_show_system";
    public static final String ADD_APP_NOT_SHOW_ADDED = "add_app_not_show_added";
    public static final String SHOW_ANDROID12_TIPS = "show_android12_tips_v2";
    public static final String ADD_APP_NOT_SHOW_32BIT = "add_app_not_show_32bit";

    // 是否应该重新安装 ROM
    // 1. 恢复出厂设置
    // 2. 替换 ROM
    public static final String FORCE_ROM_BE_RE_INSTALL = "rom_should_be_re_install";

    // 是否应该使用第三方 ROM
    public static final String SHOULD_USE_THIRD_PARTY_ROM = "should_use_third_party_rom";
    public static boolean getBooleanConfig(Context context,  String key, boolean fallback) {
        return getPref(context).getBoolean(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setBooleanConfig(Context context, String key, boolean value) {
        getPref(context).edit().putBoolean(key, value).commit();
    }

    /** Publish the ROM source and reinstall request as one preference change. */
    @SuppressLint("ApplySharedPref")
    public static boolean requestRomInstall(Context context, boolean thirdParty) {
        return getPref(context).edit()
                .putBoolean(SHOULD_USE_THIRD_PARTY_ROM, thirdParty)
                .putBoolean(FORCE_ROM_BE_RE_INSTALL, true)
                .commit();
    }

    @SuppressLint("ApplySharedPref")
    public static boolean completeRomInstallRequest(Context context) {
        return getPref(context).edit().putBoolean(FORCE_ROM_BE_RE_INSTALL, false).commit();
    }

    public static final String GUEST_NAME = "guest_instance_name";
    private static final int MAX_GUEST_NAME_LENGTH = 40;

    public static String getGuestName(Context context) {
        return getPref(context).getString(GUEST_NAME, context.getString(io.twoyi.R.string.app_name));
    }

    @SuppressLint("ApplySharedPref")
    public static boolean setGuestName(Context context, String name) {
        if (name == null) return false;
        String title = name.trim();
        if (title.codePointCount(0, title.length()) > MAX_GUEST_NAME_LENGTH
                || title.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        SharedPreferences.Editor editor = getPref(context).edit();
        if (title.isEmpty()) {
            editor.remove(GUEST_NAME);
        } else {
            editor.putString(GUEST_NAME, title);
        }
        return editor.commit();
    }

    public static void registerChangeListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        getPref(context).registerOnSharedPreferenceChangeListener(listener);
    }

    public static void unregisterChangeListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        getPref(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    private static SharedPreferences getPref(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
