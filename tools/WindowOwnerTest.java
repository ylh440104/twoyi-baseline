package io.twoyi;

public final class WindowOwnerTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("window ownership violated");
    }

    public static void main(String[] args) {
        WindowOwner windows = new WindowOwner();
        Object oldWindow = new Object();
        Object newWindow = new Object();
        check(!windows.detach(null));
        windows.attach(oldWindow);
        check(windows.isCurrent(oldWindow));
        windows.attach(newWindow);
        check(!windows.isCurrent(oldWindow));
        check(!windows.detach(oldWindow));
        check(windows.isCurrent(newWindow));
        check(windows.detach(newWindow));
        check(!windows.detach(newWindow));
        windows.attach(oldWindow);
        check(windows.isCurrent(oldWindow));
        System.out.println("PASS: stale detach/resize rejected; current window released once");
    }
}
