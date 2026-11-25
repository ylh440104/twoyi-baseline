/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ApkCopyBudgetTest {
    public static void main(String[] args) throws IOException {
        ApkCopyBudget budget = new ApkCopyBudget(10, 4);
        ByteArrayOutputStream base = new ByteArrayOutputStream();
        budget.copy(new ByteArrayInputStream(new byte[4]), base);
        ByteArrayOutputStream split = new ByteArrayOutputStream();
        budget.copy(new ByteArrayInputStream(new byte[2]), split);
        if (base.size() != 4 || split.size() != 2) {
            throw new AssertionError("Exact-fit import did not copy all bytes");
        }
        assertRejected(budget);
        assertRejected(new ApkCopyBudget(3, 4));
    }

    private static void assertRejected(ApkCopyBudget budget) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            budget.copy(new ByteArrayInputStream(new byte[1]), output);
            throw new AssertionError("Copy exceeded the allowance");
        } catch (IOException expected) {
            if (output.size() != 0) {
                throw new AssertionError("Over-budget data was written", expected);
            }
        }
    }
}
