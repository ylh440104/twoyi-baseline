/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;

import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.analytics.Analytics;
import com.microsoft.appcenter.crashes.Crashes;

import java.lang.reflect.Field;

import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2020/12/24.
 */

public class TwoyiApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // First statements: capture a crash that happens before any UI exists, and
        // leave breadcrumbs a native crash would otherwise erase.
        StartupTrace.begin(base);
        CrashDiary.install(base);
        StartupTrace.step(base, "application attach");

        try {
            RomManager.ensureBootFiles(base);
        } catch (RuntimeException unready) {
            android.util.Log.e("TwoyiApplication", "boot files could not be prepared", unready);
            CrashDiary.record(this, Thread.currentThread(), unready);
        }
        StartupTrace.step(base, "boot files ready");
        startGuestEndpoints(base);
        StartupTrace.step(base, "control listener started");
    }

    /**
     * Slot resolution must never be able to keep the app from starting. A failure
     * here falls back to the legacy slot 0 configuration and is recorded for the
     * self check instead of crashing the process.
     */
    private void startGuestEndpoints(Context base) {
        try {
            // Resolve the served slot once so every consumer agrees on paths and
            // socket names. A malformed payload throws here, before any listener binds.
            GuestProcessProfile.initialize(base);
        } catch (RuntimeException unresolved) {
            android.util.Log.e("TwoyiApplication", "guest slot resolution failed; using slot 0", unresolved);
            CrashDiary.record(this, Thread.currentThread(), unresolved);
        }
        TwoyiSocketServer server = TwoyiSocketServer.getInstance(base);
        try {
            // Slot 0 keeps the legacy name the shipped ROM was built around.
            server.configureControlSocketName(
                    GuestProcessProfile.endpoints(base.getDataDir()).getControlSocketName());
            TwoyiMessenger.getInstance().configureControlSocketName(server.getControlSocketName());
        } catch (RuntimeException configured) {
            android.util.Log.e("TwoyiApplication", "control socket configuration failed", configured);
            CrashDiary.record(this, Thread.currentThread(), configured);
        }
        server.start();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            // Debug builds keep their diagnostics on the device instead of the
            // vendor service, which also removes a launch-time failure source.
            return;
        }
        try {
            AppCenter.start(this, "6223c2b1-30ab-4293-8456-ac575420774e",
                    Analytics.class, Crashes.class);
        } catch (RuntimeException telemetry) {
            CrashDiary.record(this, Thread.currentThread(), telemetry);
        }
    }

    static int statusBarHeight = -1;

    public static int getStatusBarHeight(Context context) {
        if (statusBarHeight != -1) {
            return statusBarHeight;
        }

        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resId);
        }

        if (statusBarHeight < 0) {
            int result = 0;
            try {
                Class<?> clazz = Class.forName("com.android.internal.R$dimen");
                Object obj = clazz.newInstance();
                Field field = clazz.getField("status_bar_height");
                int resourceId = Integer.parseInt(field.get(obj).toString());
                result = context.getResources().getDimensionPixelSize(resourceId);
            } catch (Exception e) {
            } finally {
                statusBarHeight = result;
            }
        }

        //Use 25dp if no status bar height found
        if (statusBarHeight < 0) {
            statusBarHeight = dip2px(context, 25);
        }
        return statusBarHeight;
    }

    private static int dip2px(Context context, float dpValue) {
        float scale = context.getResources().getDisplayMetrics().density;
        int px = (int) (dpValue * scale + 0.5f);
        return px;
    }

    public static float px2dp(float pxValue) {
        return (pxValue / Resources.getSystem().getDisplayMetrics().density);
    }
}
