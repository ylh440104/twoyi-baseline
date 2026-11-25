#!/usr/bin/env python3
"""Run the real boot state class against tiny Android API stubs on CI.

This is a host JVM concurrency test, not an Android instrumentation test.
No APK, Android SDK, ROM or prebuilt libraries are needed.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'android/content/Context.java': '''package android.content;
public class Context { public void startActivity(Intent intent) {} }
''',
    'android/content/Intent.java': '''package android.content;
public class Intent {
 public static final String ACTION_MAIN="main", CATEGORY_HOME="home";
 public static final int FLAG_ACTIVITY_NEW_TASK=1, FLAG_ACTIVITY_CLEAR_TOP=2;
 public final Class<?> target;
 public Intent(String action) { target=null; } public Intent(Context c, Class<?> target) { this.target=target; }
 public void addCategory(String c) {} public void setFlags(int flags) {}
}
''',
    'io/twoyi/Render2Activity.java': 'package io.twoyi; public class Render2Activity {}',
    'io/twoyi/BootStateTest.java': '''package io.twoyi;
import java.util.concurrent.*;
public class BootStateTest {
 static final TwoyiStatusManager state=TwoyiStatusManager.getInstance();
 static void check(boolean value,String message) {
  if (!value) throw new AssertionError(message);
 }
 public static void main(String[] args) throws Exception {
  ExecutorService pool=Executors.newCachedThreadPool();
  try {
   state.reset();
   check(!state.waitBoot(1,TimeUnit.MILLISECONDS),"unbooted state succeeds");
    check(state.phase() == TwoyiStatusManager.Phase.STOPPED, "initial phase is not stopped");
    state.markStartupRequested();
    check(state.phase() == TwoyiStatusManager.Phase.STARTING, "startup request not reflected");
    state.markStartupAttemptFailed();
    check(state.phase() == TwoyiStatusManager.Phase.FAILED && !state.isFailed(), "retryable failure became terminal");
    state.markStartupRequested();
    check(state.phase() == TwoyiStatusManager.Phase.STARTING, "retry does not report starting");
   pool.submit(() -> state.markStarted()).get(1,TimeUnit.SECONDS);
   check(state.waitBoot(1,TimeUnit.SECONDS),"timeout poisoned next wait");
   check(state.waitBoot(1,TimeUnit.SECONDS),"second observer lost completion");
   state.markStarted();
   check(state.isStarted(),"duplicate completion clears state");
   java.util.ArrayList<Class<?>> destinations = new java.util.ArrayList<>();
   android.content.Context context = new android.content.Context() {
    @Override public void startActivity(android.content.Intent intent) { destinations.add(intent.target); }
   };
   Object windowA = new Object();
   Object windowB = new Object();
   state.updateVisibility(windowA, true);
   state.switchOs(context);
   state.switchOs(context);
   state.updateVisibility(windowB, true);
   state.updateVisibility(windowA, false);
   state.switchOs(context);
   state.updateVisibility(windowB, false);
   state.switchOs(context);
   check(destinations.equals(java.util.Arrays.asList(null, null, null, Render2Activity.class)),
         "launch request or stale focus loss changed visible window");
   state.reset();
   check(!state.isStarted(),"reset retains completed state");
   Thread[] observer = new Thread[1];
   CountDownLatch entered = new CountDownLatch(1);
   Future<Boolean> oldWait=pool.submit(() -> {
    observer[0]=Thread.currentThread(); entered.countDown();
    try { return !state.waitBoot(10,TimeUnit.SECONDS); }
    catch (BrokenBarrierException expected) { return true; }
   });
   entered.await();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
   while (observer[0].getState()!=Thread.State.TIMED_WAITING) {
    if (System.nanoTime()>deadline) throw new AssertionError("observer never waited");
    Thread.yield();
   }
   state.reset();
   check(oldWait.get(1,TimeUnit.SECONDS),"old observer reports success after reset");
   state.markStarted();
   check(state.waitBoot(1,TimeUnit.SECONDS),"new boot cannot complete");
   java.util.concurrent.atomic.AtomicInteger exitCount = new java.util.concurrent.atomic.AtomicInteger();
   Runnable exitCb = exitCount::incrementAndGet;
   state.addExitListener(exitCb);
   state.markFailed();
   check(state.isFailed(),"markFailed did not set failed");
   check(!state.isStarted(),"markFailed did not clear started");
   check(!state.waitBoot(1,TimeUnit.SECONDS),"waitBoot returned true after failure");
   check(exitCount.get()==1,"exit listener not called once");
   state.markFailed();
   check(exitCount.get()==1,"duplicate markFailed fired listener twice");
   state.markStarted();
   check(!state.isStarted(),"markStarted resurrected failed session");
   state.reset();
    state.markStarted();
    state.markStopped();
    check(state.phase() == TwoyiStatusManager.Phase.STOPPED && !state.isFailed(), "shutdown reported as failure");
    check(!state.waitBoot(1,TimeUnit.SECONDS), "shutdown left guest ready");
    state.markStopped();
    state.markStarted();
    state.markFailed();
    check(!state.isStarted() && !state.isFailed(), "late callback changed stopped session");
    check(exitCount.get()==2, "shutdown must notify exit listeners exactly once");
    state.removeExitListener(exitCb);
   state.reset();
   check(!state.isFailed(),"reset did not clear failed");
   Future<Boolean> failWait=pool.submit(() -> state.waitBoot(10,TimeUnit.SECONDS));
   Thread.sleep(50);
   state.markFailed();
   check(!failWait.get(1,TimeUnit.SECONDS),"waiter did not unblock with false on failure");
   System.out.println("PASS: timeout recovery, nonblocking completion, repeated observers, reset, failure exit");
  } finally { pool.shutdownNow(); }
 }
}
''',
}
with tempfile.TemporaryDirectory(prefix='twoyi-boot-test-') as temp:
    directory = Path(temp)
    for name, text in SOURCES.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    source = ROOT / 'app/src/main/java/io/twoyi/TwoyiStatusManager.java'
    subprocess.run(['javac', '-d', str(directory), str(source),
                    *[str(directory / name) for name in SOURCES]], check=True)
    subprocess.run(['java', '-cp', str(directory), 'io.twoyi.BootStateTest'],
                   check=True, timeout=20)
