/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

/** Bounded uncompressed payload for the in-memory support ZIP. */
public final class DiagnosticArchiveBudget {
    public static final long MAX_ARCHIVE_BYTES = 16L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;
    private static final long FIXED_ZIP_OVERHEAD = 4096;
    private static final long OVERHEAD_PER_ENTRY = 1024;

    private final long maximumPayload;
    private long remaining;

    public DiagnosticArchiveBudget(int entryCount) {
        if (entryCount < 0) throw new IllegalArgumentException("Negative entry count");
        long overhead = FIXED_ZIP_OVERHEAD + OVERHEAD_PER_ENTRY * entryCount;
        maximumPayload = Math.max(0, MAX_ARCHIVE_BYTES - overhead);
        remaining = maximumPayload;
    }

    public long nextEntryLimit() {
        return Math.min(remaining, MAX_ENTRY_BYTES);
    }

    public void recordEntryBytes(long count) {
        if (count < 0 || count > nextEntryLimit()) {
            throw new IllegalArgumentException("Diagnostic entry exceeds its byte budget");
        }
        remaining -= count;
    }

    public long maximumPayload() { return maximumPayload; }
    public long remaining() { return remaining; }
}
