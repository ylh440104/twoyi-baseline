package io.twoyi;

public final class GuestSlotSelectionTest {
    public static void main(String[] args) {
        if (GuestSlotSelection.resolve(null) != 0) throw new AssertionError("absent argument must mean slot 0");
        if (GuestSlotSelection.resolve(" 0 ") != 0) throw new AssertionError("padded zero rejected");
        for (int slot = 1; slot < GuestSlot.SLOT_COUNT; slot++) {
            if (GuestSlotSelection.resolve(Integer.toString(slot)) != slot) {
                throw new AssertionError("slot " + slot + " not parsed");
            }
        }
        rejects("-1");
        rejects("4");
        rejects("abc");
        rejects("0.5");
        rejects("");
        System.out.println("PASS: guest slot selection rejects malformed and out-of-range values");
    }

    private static void rejects(String value) {
        try {
            GuestSlotSelection.resolve(value);
            throw new AssertionError("accepted invalid slot argument: " + value);
        } catch (IllegalArgumentException expected) {
        }
    }
}
