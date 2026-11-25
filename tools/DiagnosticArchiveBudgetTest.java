/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

public final class DiagnosticArchiveBudgetTest {
    public static void main(String[] args) {
        DiagnosticArchiveBudget budget = new DiagnosticArchiveBudget(100);
        long remaining = budget.maximumPayload();
        while (remaining > 0) {
            long entry = budget.nextEntryLimit();
            if (entry <= 0 || entry > DiagnosticArchiveBudget.MAX_ENTRY_BYTES) {
                throw new AssertionError("Invalid per-entry cap: " + entry);
            }
            budget.recordEntryBytes(entry);
            remaining -= entry;
            if (budget.remaining() != remaining) throw new AssertionError("Budget did not decrease");
        }
        if (budget.nextEntryLimit() != 0) throw new AssertionError("Budget was exceeded");
        try {
            new DiagnosticArchiveBudget(-1);
            throw new AssertionError("Negative item count was accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
