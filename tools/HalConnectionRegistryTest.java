package io.twoyi;

public final class HalConnectionRegistryTest {
    public static void main(String[] args) {
        HalConnectionRegistry registry = new HalConnectionRegistry();
        if (!registry.opened("audio.primary.goldfish")) throw new AssertionError("first open refused");
        if (registry.opened("audio.primary.goldfish")) throw new AssertionError("duplicate open accepted");
        if (registry.size() != 1) throw new AssertionError("duplicate created a record");
        if (registry.pending().size() != 1) throw new AssertionError("pending list wrong");
        registry.finished("audio.primary.goldfish", HalConnectionRegistry.Outcome.UNSUPPORTED);
        if (registry.outcomeOf("audio.primary.goldfish") != HalConnectionRegistry.Outcome.UNSUPPORTED) {
            throw new AssertionError("outcome not recorded");
        }
        if (!registry.pending().isEmpty()) throw new AssertionError("completed record still pending");
        if (registry.outcomeOf("missing") != null) throw new AssertionError("unknown source reported");

        rejects(() -> registry.opened(null));
        rejects(() -> registry.opened(" "));
        rejects(() -> registry.opened("bad\0name"));
        rejects(() -> registry.finished("audio.primary.goldfish", null));
        rejects(() -> registry.finished("audio.primary.goldfish", HalConnectionRegistry.Outcome.PENDING));

        for (int i = 0; i < 20; i++) registry.opened("module-" + i);
        if (registry.size() > 16) throw new AssertionError("registry grew past its bound");
        if (registry.outcomeOf("module-0") != null) throw new AssertionError("oldest record not evicted");
        registry.clear();
        if (registry.size() != 0) throw new AssertionError("clear failed");
        System.out.println("PASS: HAL connections are tracked once, bounded and finalized");
    }

    private interface Action { void run(); }

    private static void rejects(Action action) {
        try {
            action.run();
            throw new AssertionError("invalid HAL record accepted");
        } catch (IllegalArgumentException expected) {
        }
    }
}
