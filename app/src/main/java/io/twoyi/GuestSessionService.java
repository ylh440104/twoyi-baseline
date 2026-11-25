/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.twoyi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import io.twoyi.utils.AppKV;

/** Same-process foreground lifetime for the legacy guest; no automatic restart. */
public final class GuestSessionService extends Service {
    private static final String CHANNEL = "guest_session";
    private static final int NOTIFICATION_ID = 1001;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private final Runnable exitListener = this::stopSessionOnMain;
    private final SharedPreferences.OnSharedPreferenceChangeListener nameListener = (preferences, key) -> {
        TwoyiStatusManager status = TwoyiStatusManager.getInstance();
        if (AppKV.GUEST_NAME.equals(key) && !destroyed && !status.isFailed() && !status.isStoppedByRequest()) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    };
    private boolean destroyed;

    private void stopSessionOnMain() {
        main.post(() -> {
            if (!destroyed) {
                stopForeground(true);
                stopSelf();
            }
        });
    }

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, GuestSessionService.class));
    }

    private Notification notification() {
        Intent open = new Intent(this, Render2Activity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_guest_session)
                .setContentTitle(getString(R.string.guest_named_session_title, AppKV.getGuestName(this)))
                .setContentText(getString(R.string.guest_session_body))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.guest_session_channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification());
        AppKV.registerChangeListener(this, nameListener);
        TwoyiStatusManager status = TwoyiStatusManager.getInstance();
        status.addExitListener(exitListener);
        if (status.isFailed() || status.isStoppedByRequest()) {
            stopSessionOnMain();
            return;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Recovery of guest native state after process death is not implemented.
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroyed = true;
        AppKV.unregisterChangeListener(this, nameListener);
        TwoyiStatusManager.getInstance().removeExitListener(exitListener);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
