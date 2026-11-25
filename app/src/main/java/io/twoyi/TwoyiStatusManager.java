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

import android.content.Context;
import android.content.Intent;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author weishu
 * @date 2021/10/27.
 */

public class TwoyiStatusManager {

    private static final TwoyiStatusManager INSTANCE = new TwoyiStatusManager();
    private TwoyiStatusManager() {
    }

    private final AtomicBoolean mStarted = new AtomicBoolean(false);
    private final AtomicBoolean mFailed = new AtomicBoolean(false);
    private boolean stoppedByRequest;
    private final java.util.concurrent.atomic.AtomicReference<Object> focusedWindow =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final CopyOnWriteArrayList<Runnable> mExitListeners = new CopyOnWriteArrayList<>();

    public enum Phase { STOPPED, STARTING, RUNNING, FAILED }

    private Phase pendingPhase = Phase.STOPPED;

    public synchronized Phase phase() {
        if (mFailed.get()) return Phase.FAILED;
        if (mStarted.get()) return Phase.RUNNING;
        return pendingPhase;
    }

    synchronized void markStartupRequested() {
        if (!mStarted.get() && !mFailed.get() && !stoppedByRequest) pendingPhase = Phase.STARTING;
    }

    synchronized void markStartupAttemptFailed() {
        if (!mStarted.get() && !mFailed.get() && !stoppedByRequest) pendingPhase = Phase.FAILED;
    }

    synchronized boolean isStoppedByRequest() {
        return stoppedByRequest;
    }

    void markStopped() {
        synchronized (this) {
            if (stoppedByRequest) return;
            boolean alreadyNotified = mFailed.getAndSet(false);
            stoppedByRequest = true;
            mStarted.set(false);
            pendingPhase = Phase.STOPPED;
            mBootLatch.countDown();
            if (alreadyNotified) return;
        }
        notifyExitListeners();
    }

    private volatile CountDownLatch mBootLatch = new CountDownLatch(1);

    public static TwoyiStatusManager getInstance() {
        return INSTANCE;
    }

    public void updateVisibility(Object window, boolean visible) {
        if (visible) focusedWindow.set(window);
        else focusedWindow.compareAndSet(window, null);
    }

    public synchronized void markStarted() {
        if (mFailed.get() || stoppedByRequest) {
            return;
        }
        if (mStarted.compareAndSet(false, true)) {
            mBootLatch.countDown();
        }
    }

    public void markFailed() {
        boolean changed;
        synchronized (this) {
            if (stoppedByRequest) return;
            changed = mFailed.compareAndSet(false, true);
            mStarted.set(false);
            mBootLatch.countDown();
        }
        if (changed) notifyExitListeners();
    }

    private void notifyExitListeners() {
        for (Runnable listener : mExitListeners) {
            listener.run();
        }
    }

    public boolean isFailed() {
        return mFailed.get();
    }

    public void addExitListener(Runnable listener) {
        if (listener != null) {
            mExitListeners.addIfAbsent(listener);
        }
    }

    public void removeExitListener(Runnable listener) {
        mExitListeners.remove(listener);
    }

    public boolean isStarted() {
        return mStarted.get();
    }

    public synchronized void reset() {
        CountDownLatch previous = mBootLatch;
        mStarted.set(false);
        mFailed.set(false);
        stoppedByRequest = false;
        focusedWindow.set(null);
        pendingPhase = Phase.STOPPED;
        mBootLatch = new CountDownLatch(1);
        // Release observers of the previous boot; they must not report success.
        previous.countDown();
    }

    public boolean waitBoot(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException {
        CountDownLatch boot = mBootLatch;
        if (!boot.await(timeout, unit)) {
            return false;
        }
        synchronized (this) {
            if (boot != mBootLatch) {
                throw new BrokenBarrierException("boot session reset");
            }
            return mStarted.get() && !mFailed.get();
        }
    }

    public void switchOs(Context context) {
        if (!mStarted.get()) {
            return;
        }

        Intent intent;
        if (focusedWindow.get() != null) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
        } else {
            intent = new Intent(context, Render2Activity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Focus callbacks report when the requested transition actually takes effect.
        context.startActivity(intent);
    }
}
