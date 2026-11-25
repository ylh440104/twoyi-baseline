/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

public final class GuestMaintenanceTest {
    public static void main(String[] args) throws Exception {
        checkStateTransitions();
        checkConcurrentRequests();
    }

    private static void checkStateTransitions() {
        GuestMaintenance state = new GuestMaintenance();
        check(state.beginRomPreparation(), "initial ROM preparation");
        check(!state.beginRomPreparation(), "overlapping ROM preparation");
        check(!state.beginInstall(), "APK installation during ROM preparation");
        check(!state.beginShutdown(), "shutdown during ROM preparation");
        state.endRomPreparation();
        check(state.beginInstall(), "installation after ROM preparation");
        check(!state.beginInstall(), "overlapping installations");
        check(!state.beginShutdown(), "shutdown during installation");
        state.endInstall();
        check(state.beginShutdown(), "shutdown after installation");
        check(!state.beginInstall(), "installation during shutdown");
        check(!state.beginShutdown(), "duplicate shutdown request");
        state.shutdownFailed();
        check(!state.beginInstall(), "installation after partial shutdown");
        check(state.beginShutdown(), "shutdown retry");
        GuestMaintenance preparation = new GuestMaintenance();
        check(preparation.beginRomPreparation(), "initial ROM preparation");
        preparation.endRomPreparation();
        check(preparation.beginInstall(), "failed preparation released maintenance lock");
    }

    private static void checkConcurrentRequests() throws Exception {
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 32; attempt++) {
                GuestMaintenance state = new GuestMaintenance();
                java.util.concurrent.CyclicBarrier ready = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<Boolean> install = workers.submit(() -> {
                    ready.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return state.beginInstall();
                });
                java.util.concurrent.Future<Boolean> shutdown = workers.submit(() -> {
                    ready.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return state.beginShutdown();
                });
                boolean installGranted = install.get(5, java.util.concurrent.TimeUnit.SECONDS);
                boolean shutdownGranted = shutdown.get(5, java.util.concurrent.TimeUnit.SECONDS);
                check(installGranted != shutdownGranted, "concurrent requests must grant exactly one operation");
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static void check(boolean condition, String operation) {
        if (!condition) throw new AssertionError(operation);
    }
}
