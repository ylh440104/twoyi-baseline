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

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.util.concurrent.TimeUnit;
import java.lang.ref.WeakReference;

import io.twoyi.utils.LogEvents;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends Activity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";
    private static final WindowOwner WINDOW_OWNER = new WindowOwner();

    private SurfaceView mSurfaceView;

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;
    private View mRetryRomPreparation;
    private Thread mBootObserver;
    private long guestGestureDownTime = -1;
    private final Object focusToken = new Object();
    private final Runnable mExitListener = () -> runOnUiThread(() -> {
        if (TwoyiStatusManager.getInstance().isStoppedByRequest()) {
            finish();
        } else {
            showGuestFailedUi();
        }
    });


    /**
     * A crash before any UI existed used to leave the user with nothing to report.
     * The next launch shows the recorded trace instead of guessing, and clears it
     * once the user has seen it.
     */
    private void showLastCrashIfAny() {
        String report;
        try {
            report = CrashDiary.lastReport(this);
        } catch (RuntimeException unreadable) {
            return;
        }
        String trace = StartupTrace.read(this);
        if (report == null && trace == null) return;
        if (report == null) {
            report = trace;
        } else if (trace != null) {
            report = report + "\n\n--- previous start-up ---\n" + trace;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.guest_crash_report)
                .setMessage(report)
                .setPositiveButton(R.string.guest_crash_continue, (dialog, which) -> {
                    CrashDiary.clear(this);
                    StartupTrace.clear(this);
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void showGuestFailedUi() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (mBootObserver != null) {
            mBootObserver.interrupt();
        }
        if (mLoadingView != null) {
            mLoadingView.stopAnimation();
        }
        if (mLoadingLayout != null) {
            mLoadingLayout.setVisibility(View.VISIBLE);
        }
        if (mLoadingText != null) {
            mLoadingText.setVisibility(View.VISIBLE);
            mLoadingText.setText(R.string.boot_failed);
        }
    }


    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            Surface surface = holder.getSurface();
            WindowManager windowManager = getWindowManager();
            Display defaultDisplay = windowManager.getDefaultDisplay();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            defaultDisplay.getRealMetrics(displayMetrics);

            float xdpi = displayMetrics.xdpi;
            float ydpi = displayMetrics.ydpi;

            try {
                GuestEndpoints paths = GuestProcessProfile.endpoints(getDataDir());
                Renderer.init(surface, RomManager.getLoaderPath(getApplicationContext()),
                        paths.getRootfsDir(), paths.getLogFile(), paths.getTouchSocketPath(),
                        paths.getKeySocketPath(), xdpi, ydpi, (int) getBestFps());
                guestGestureDownTime = -1;
                WINDOW_OWNER.attach(holder);
                TwoyiStatusManager.getInstance().markStartupRequested();
            } catch (IllegalStateException | IllegalArgumentException e) {
                TwoyiStatusManager.getInstance().markStartupAttemptFailed();
                Log.e(TAG, "Guest launch failed", e);
                showGuestFailedUi();
                stopService(new android.content.Intent(Render2Activity.this, GuestSessionService.class));
                return;
            }

            GuestSessionService.start(Render2Activity.this);
            showBootingProcedure();
            Log.i(TAG, "surfaceCreated");
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            if (!WINDOW_OWNER.isCurrent(holder)) {
                return;
            }
            Surface surface = holder.getSurface();
            Renderer.resetWindow(surface, 0, 0, width, height);
            Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            guestGestureDownTime = -1;
            if (WINDOW_OWNER.detach(holder)) {
                Renderer.removeWindow(holder.getSurface());
            }
            Log.i(TAG, "surfaceDestroyed!");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean started = TwoyiStatusManager.getInstance().isStarted();
        Log.i(TAG, "onCreate: " + savedInstanceState + " isStarted: " + started);

        super.onCreate(savedInstanceState);

        // A completed guest session belongs to the process, not this Activity.
        // Keep its completion signal so a replacement window can observe it.
        // The process starts with an incomplete latch. Activity recreation
        // must not reset it or race a BOOT_COMPLETED notification.

        StartupTrace.step(this, "activity create");
        NavUtils.hideNavigation(getWindow());

        setContentView(R.layout.ac_render);
        StartupTrace.step(this, "layout inflated");
        mRootView = findViewById(R.id.root);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);
        mBootLogView = findViewById(R.id.bootlog);
        mRetryRomPreparation = findViewById(R.id.retryRomPreparation);
        mRetryRomPreparation.setOnClickListener(view -> prepareRom(true));

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        StartupTrace.step(this, "views bound");
        showLastCrashIfAny();

        TwoyiStatusManager status = TwoyiStatusManager.getInstance();
        status.addExitListener(mExitListener);
        if (status.isStoppedByRequest()) {
            finish();
        } else if (status.isFailed()) {
            showGuestFailedUi();
        } else if (started) {
            GuestSessionService.start(this);
            // Do not reinstall or reinitialize a ROM that is already running.
            mRootView.addView(mSurfaceView, 0);
        } else {
            StartupTrace.step(this, "rom preparation requested");
            UITips.checkForAndroid12(this, this::bootSystem);
        }

        mSurfaceView.setOnTouchListener(this);

    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState: " + savedInstanceState);

        // Surface callbacks reconnect the new view to the process-owned renderer.
    }

    private void bootSystem() {
        prepareRom(false);
    }

    private void prepareRom(boolean retry) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        TwoyiStatusManager.getInstance().markStartupRequested();
        mRetryRomPreparation.setVisibility(View.GONE);
        mLoadingView.startAnimation();
        showTipsForFirstBoot();
        WeakReference<Render2Activity> observer = new WeakReference<>(this);
        Context app = getApplicationContext();
        (retry ? RomPreparation.retry(app) : RomPreparation.ensureReady(app)).whenComplete((ignored, failure) -> {
            Render2Activity activity = observer.get();
            if (activity != null) {
                activity.runOnUiThread(() -> activity.onRomPrepared(failure));
            }
        });
    }

    private void onRomPrepared(Throwable failure) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (failure != null) {
            TwoyiStatusManager.getInstance().markStartupAttemptFailed();
            Log.e(TAG, "ROM preparation failed", failure);
            mLoadingView.stopAnimation();
            mLoadingText.setVisibility(View.VISIBLE);
            mLoadingText.setText(R.string.boot_failed);
            mRetryRomPreparation.setVisibility(View.VISIBLE);
            return;
        }
        if (mSurfaceView.getParent() == null) {
            mRootView.addView(mSurfaceView, 0);
        }
    }

    private void showTipsForFirstBoot() {
        mLoadingText.setText(R.string.extracting_tips);
        mRootView.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && RomPreparation.isExtracting()) {
                mLoadingText.setText(R.string.first_boot_tips);
            }
        }, 5000);

        mRootView.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && RomPreparation.isExtracting()) {
                mLoadingText.setText(R.string.first_boot_tips2);
            }
        }, 10 * 1000);

        mRootView.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && RomPreparation.isExtracting()) {
                mLoadingText.setText(R.string.first_boot_tips3);
            }
        }, 15 * 1000);
    }

    private void showBootingProcedure() {
        // mLoadingText.setText(R.string.booting_tips);
        mLoadingText.setVisibility(View.GONE);
        mBootLogView.setVisibility(View.VISIBLE);
        if (mBootObserver != null && mBootObserver.isAlive()) {
            return;
        }
        mBootObserver = new Thread(() -> {

            if (true) {
                boolean success = false;
                try {
                    success = TwoyiStatusManager.getInstance().waitBoot(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.BrokenBarrierException e) {
                    return;
                }

                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (TwoyiStatusManager.getInstance().isFailed()) {
                    runOnUiThread(this::showGuestFailedUi);
                    return;
                }
                if (!success) {
                    runOnUiThread(() -> {
                        TwoyiStatusManager state = TwoyiStatusManager.getInstance();
                        if (!isFinishing() && !isDestroyed() && !state.isStarted() && !state.isFailed()) {
                            mLoadingText.setText(R.string.guest_boot_pending);
                            mLoadingText.setVisibility(View.VISIBLE);
                        }
                    });

                    // Continue observing a slow boot; a timeout is not proof
                    // that the guest has exited. Destroying this view cancels
                    // its observer without touching the process-owned guest.
                    TwoyiStatusManager status = TwoyiStatusManager.getInstance();
                    while (!isFinishing() && !isDestroyed() && !status.isFailed() && !status.isStoppedByRequest()) {
                        try {
                            if (TwoyiStatusManager.getInstance().waitBoot(1, TimeUnit.SECONDS)) {
                                success = true;
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (java.util.concurrent.BrokenBarrierException e) {
                            return;
                        }
                    }
                    if (TwoyiStatusManager.getInstance().isFailed()) {
                        runOnUiThread(this::showGuestFailedUi);
                        return;
                    }
                    if (!success) {
                        return;
                    }
                }
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || TwoyiStatusManager.getInstance().isFailed()) {
                    return;
                }
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
            });
        }, "waiting-boot");
        mBootObserver.start();
    }

    @Override
    protected void onDestroy() {
        TwoyiStatusManager.getInstance().updateVisibility(focusToken, false);
        TwoyiStatusManager.getInstance().removeExitListener(mExitListener);
        if (mBootObserver != null) {
            mBootObserver.interrupt();
        }
        if (mLoadingView != null) {
            mLoadingView.stopAnimation();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        StartupTrace.step(this, "focus " + hasFocus);
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
        } else {
            guestGestureDownTime = -1;
            if (mSurfaceView != null && WINDOW_OWNER.isCurrent(mSurfaceView.getHolder())) {
                // A dialog or task switch can interrupt a gesture without destroying the surface.
                Renderer.cancelTouch();
            }
        }

        // Update global visibility.
        TwoyiStatusManager.getInstance().updateVisibility(focusToken, hasFocus);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!hasWindowFocus() || !WINDOW_OWNER.isCurrent(mSurfaceView.getHolder())) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            guestGestureDownTime = event.getDownTime();
        }
        if (guestGestureDownTime >= 0 && guestGestureDownTime == event.getDownTime()) {
            Renderer.handleTouch(event, v.getWidth(), v.getHeight());
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                guestGestureDownTime = -1;
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // TODO: 2021/10/26 Add Volume control
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        Renderer.sendKeycode(KeyEvent.KEYCODE_BACK);
    }

    private float getBestFps() {
        WindowManager windowManager = getWindowManager();
        Display defaultDisplay = windowManager.getDefaultDisplay();
        Display.Mode[] supportedModes = defaultDisplay.getSupportedModes();
        float fps = 45;
        for (Display.Mode supportedMode : supportedModes) {
            float refreshRate = supportedMode.getRefreshRate();
            if (refreshRate > fps) {
                // fps = refreshRate;
            }
        }

        Log.w(TAG, "current fps: " + fps);
        return fps;
    }
}
