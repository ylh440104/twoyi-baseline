package io.twoyi;

public final class GuestServiceSlotTest {
    public static void main(String[] args) {
        for (int slot = 0; slot < GuestSlot.SLOT_COUNT; slot++) {
            String name = "io.twoyi.GuestInstanceService$Slot" + slot;
            if (GuestSlotSelection.fromServiceClass(name) != slot) {
                throw new AssertionError("service class not mapped: " + name);
            }
        }
        rejects(null);
        rejects("");
        rejects("io.twoyi.GuestInstanceService");
        rejects("io.twoyi.GuestInstanceService$Slot");
        rejects("io.twoyi.GuestInstanceService$Slot4");
        rejects("io.twoyi.GuestInstanceService$Slot-1");
        rejects("io.twoyi.GuestInstanceService$Slot00");
        rejects("io.twoyi.GuestInstanceService$Slota");
        System.out.println("PASS: per-slot service classes map to slots and reject bad names");
    }

    private static void rejects(String className) {
        try {
            GuestSlotSelection.fromServiceClass(className);
            throw new AssertionError("invalid service class accepted: " + className);
        } catch (IllegalArgumentException expected) {
        }
    }
}
