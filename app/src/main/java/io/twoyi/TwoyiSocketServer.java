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

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.ui.SettingsActivity;
import io.twoyi.utils.IOUtils;
import io.twoyi.utils.UIHelper;

/**
 * @author weishu
 * @date 2021/10/27.
 */

public class TwoyiSocketServer {

    private static final String TAG = "TwoyiSocketServer";

    private static TwoyiSocketServer INSTANCE;

    private static final String SWITCH_HOST = "SWITCH_HOST";
    private static final String BOOT_COMPLETED = "BOOT_COMPLETED";

    private static final String JUMP_HOST_SETTINGS= "SETTINGS";

    private static final ExecutorService LISTENER = Executors.newSingleThreadExecutor();
    private static final ExecutorService CLIENTS = new ThreadPoolExecutor(
            0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<>());

    private final AtomicBoolean mStarted = new AtomicBoolean(false);
    private final AtomicBoolean mProbeScheduled = new AtomicBoolean(false);
    private final Context mContext;
    private final Object readiness = new Object();
    private boolean listening;
    private volatile String controlSocketName = GuestEndpoints.DEFAULT_CONTROL_SOCKET;
    private boolean configured;

    private TwoyiSocketServer(Context context) {
        mContext = context;
    }

    /**
     * A guest process serves exactly one slot, so the listener name is fixed for
     * the process lifetime. Changing it after the bind starts would leave the
     * guest talking to a name nobody listens on.
     */
    void configureControlSocketName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Control socket name is required");
        }
        synchronized (readiness) {
            if (mStarted.get() || listening) {
                throw new IllegalStateException("Control listener already started");
            }
            if (configured && !name.equals(controlSocketName)) {
                throw new IllegalStateException("Control socket name already configured");
            }
            controlSocketName = name;
            configured = true;
        }
    }

    String getControlSocketName() {
        return controlSocketName;
    }

    public static synchronized TwoyiSocketServer getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new TwoyiSocketServer(context);
        }

        return INSTANCE;
    }

    public void start() {
        if (mStarted.compareAndSet(false, true)) {
            LISTENER.execute(this::start0);
        }
    }

    boolean awaitListening(long timeoutMillis) throws InterruptedException {
        start();
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        synchronized (readiness) {
            while (!listening) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) return false;
                readiness.wait(remaining);
            }
            return true;
        }
    }

    private void scheduleProbe() {
        if (mProbeScheduled.compareAndSet(false, true)) {
            new Thread(() -> {
                // Some devices prompt for permission on the first local connection.
                SystemClock.sleep(3000);
                TwoyiMessenger.getInstance().probe();
            }, "control-socket-probe").start();
        }
    }

    private void start0() {
        LocalSocket socket = null;
        try {
            socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
            socket.bind(new LocalSocketAddress(controlSocketName, LocalSocketAddress.Namespace.ABSTRACT));
            // The LocalSocket owns this descriptor. Close it once in the outer finally.
            LocalServerSocket server = new LocalServerSocket(socket.getFileDescriptor());
            synchronized (readiness) {
                listening = true;
                readiness.notifyAll();
            }
            scheduleProbe();
            while (!Thread.currentThread().isInterrupted()) {
                handleSocket(server.accept());
            }
        } catch (IOException e) {
            Log.e(TAG, "start socket failed", e);

        } finally {
            synchronized (readiness) {
                listening = false;
            }
            // Release the abstract socket name before scheduling another bind.
            IOUtils.closeSilently(socket);
            mStarted.set(false);
        }
        if (!Thread.currentThread().isInterrupted()) {
            SystemClock.sleep(1000);
            start();
        }
    }

    private void handleSocket(LocalSocket socket) {
        try {
            // The legacy guest runs under the host app's Linux UID.
            if (socket.getPeerCredentials().getUid() == Process.myUid()) {
                CLIENTS.execute(() -> handleSocket0(socket));
                return;
            }
        } catch (IOException | RejectedExecutionException e) {
            // A disconnected peer or a full pool must not leave an accepted FD open.
        }
        IOUtils.closeSilently(socket);
    }

    private void handleSocket0(LocalSocket socket) {
        try {
            InputStream inputStream = socket.getInputStream();
            Thread currentThread = Thread.currentThread();

            byte[] data = new byte[1024];
            while (!currentThread.isInterrupted()) {
                int read = inputStream.read(data);
                if (read <= 0) {
                    break;
                }
                handleData(new String(data, 0, read, StandardCharsets.US_ASCII));
            }

        } catch (IOException ignored) {
            // A peer may disconnect while the guest is shutting down.
        } finally {
            IOUtils.closeSilently(socket);
        }
    }

    private void handleData(String packet) {
        // Accept legacy C string terminators and line endings without treating
        // arbitrary suffixes as part of a valid command.
        int end = packet.length();
        while (end > 0) {
            char terminator = packet.charAt(end - 1);
            if (terminator != '\0' && terminator != '\r' && terminator != '\n') break;
            end--;
        }
        switch (packet.substring(0, end)) {
            case SWITCH_HOST:
                TwoyiStatusManager.getInstance().switchOs(mContext);
                break;
            case BOOT_COMPLETED:
                TwoyiStatusManager.getInstance().markStarted();
                break;
            case JUMP_HOST_SETTINGS:
                UIHelper.startActivity(mContext, SettingsActivity.class);
                break;
            default:
                break;
        }
    }
}
