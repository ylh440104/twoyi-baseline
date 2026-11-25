package io.twoyi.utils;

import java.io.IOException;

public final class RomArchiveBudgetTest {
    private static final long RESERVE = 32L * 1024 * 1024;

    public static void main(String[] args) throws IOException {
        checkSpaceBoundary();
        checkMissingBootFiles();
        checkSizeLimits();
        System.out.println("ROM archive budget tests passed");
    }

    private static RomArchiveBudget bootableArchive() throws IOException {
        RomArchiveBudget budget = new RomArchiveBudget();
        budget.addEntry("rootfs/init", false, 1);
        budget.addEntry("rootfs/system/bin/sh", false, 2);
        return budget;
    }

    private static void checkSpaceBoundary() throws IOException {
        RomArchiveBudget budget = bootableArchive();
        budget.addEntry("rootfs/vendor/config", false, 4);
        budget.addEntry("rootfs/vendor", true, 100);
        budget.checkAvailable(RESERVE + 7);
        expectRejected(budget, RESERVE + 6, "one byte short");
    }

    private static void checkMissingBootFiles() throws IOException {
        RomArchiveBudget missingInit = new RomArchiveBudget();
        missingInit.addEntry("rootfs/system/bin/sh", false, 2);
        missingInit.addEntry("rootfs/init", true, 1);
        missingInit.addEntry("rootfs/init", false, 0);
        expectRejected(missingInit, Long.MAX_VALUE, "missing non-empty init file");

        RomArchiveBudget missingSystem = new RomArchiveBudget();
        missingSystem.addEntry("rootfs/init", false, 1);
        missingSystem.addEntry("rootfs/system/bin", true, 1);
        missingSystem.addEntry("rootfs/system/bin/sh", false, 0);
        missingSystem.addEntry("rootfs/system_ext/bin/sh", false, 2);
        expectRejected(missingSystem, Long.MAX_VALUE, "missing non-empty system file");
    }

    private static void checkSizeLimits() throws IOException {
        RomArchiveBudget budget = bootableArchive();
        budget.addEntry("rootfs/vendor/data", false, Long.MAX_VALUE - RESERVE - 3);
        budget.checkAvailable(Long.MAX_VALUE);
        expectInvalidSize(budget, 1, "cumulative size overflow");
        expectInvalidSize(bootableArchive(), -1, "negative entry size");
    }

    private static void expectRejected(RomArchiveBudget budget, long available, String scenario) {
        try {
            budget.checkAvailable(available);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Accepted " + scenario);
    }

    private static void expectInvalidSize(RomArchiveBudget budget, long size, String scenario) {
        try {
            budget.addEntry("rootfs/vendor/extra", false, size);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Accepted " + scenario);
    }
}
