package io.twoyi;

import static io.twoyi.TwinTaskState.Phase;

public final class TwinTaskStateTest {
    public static void main(String[] args) {
        guards();
        transitions();
        lifecycle();
        labels();
        System.out.println("PASS: twin task state guards pause, stop and hand-back");
    }

    private static void guards() {
        // Only RUNNING may pause; only PAUSED may resume.
        expect(!TwinTaskState.canPause(Phase.IDLE), "idle cannot pause");
        expect(!TwinTaskState.canPause(Phase.PREPARING), "preparing cannot pause");
        expect(TwinTaskState.canPause(Phase.RUNNING), "running can pause");
        expect(TwinTaskState.canResume(Phase.PAUSED), "paused can resume");
        expect(!TwinTaskState.canResume(Phase.RUNNING), "running cannot resume");

        // Stopping is offered while working, paused or preparing.
        expect(TwinTaskState.canStop(Phase.RUNNING), "running can stop");
        expect(TwinTaskState.canStop(Phase.PAUSED), "paused can stop");
        expect(TwinTaskState.canStop(Phase.PREPARING), "preparing can stop");
        expect(!TwinTaskState.canStop(Phase.DONE), "done cannot stop");
        expect(!TwinTaskState.canStop(Phase.USER_IN_CONTROL), "hand-over cannot stop");

        // Take-over only while the Twin is actually acting.
        expect(TwinTaskState.canTakeOver(Phase.RUNNING), "running can hand over");
        expect(TwinTaskState.canTakeOver(Phase.PAUSED), "paused can hand over");
        expect(!TwinTaskState.canTakeOver(Phase.QUEUED), "queued cannot hand over");
        expect(!TwinTaskState.canTakeOver(Phase.DONE), "done cannot hand over");

        // Busy and terminal classifications.
        expect(TwinTaskState.isBusy(Phase.QUEUED), "queued is busy");
        expect(TwinTaskState.isBusy(Phase.STOPPING), "stopping is busy");
        expect(!TwinTaskState.isBusy(Phase.PAUSED), "paused is not busy");
        expect(TwinTaskState.isTerminal(Phase.IDLE), "idle is terminal");
        expect(TwinTaskState.isTerminal(Phase.DONE), "done is terminal");
        expect(TwinTaskState.isTerminal(Phase.FAILED), "failed is terminal");
        expect(!TwinTaskState.isTerminal(Phase.USER_IN_CONTROL), "hand-over is not terminal");
    }

    private static void transitions() {
        expect(TwinTaskState.onPause(Phase.RUNNING) == Phase.PAUSED, "pause -> paused");
        expect(TwinTaskState.onPause(Phase.PREPARING) == Phase.PREPARING, "pause refused while preparing");
        expect(TwinTaskState.onResume(Phase.PAUSED) == Phase.RUNNING, "resume -> running");
        expect(TwinTaskState.onResume(Phase.IDLE) == Phase.IDLE, "resume refused when idle");

        expect(TwinTaskState.onStopRequested(Phase.RUNNING) == Phase.STOPPING, "stop -> stopping");
        expect(TwinTaskState.onStopRequested(Phase.DONE) == Phase.DONE, "stop refused when done");
        expect(TwinTaskState.onStopCompleted(Phase.STOPPING) == Phase.USER_IN_CONTROL,
                "stop completes into hand-over");
        expect(TwinTaskState.onStopCompleted(Phase.RUNNING) == Phase.RUNNING,
                "stop completion ignored when not stopping");

        expect(TwinTaskState.onTakeOver(Phase.RUNNING) == Phase.USER_IN_CONTROL, "take over");
        expect(TwinTaskState.onTakeOver(Phase.QUEUED) == Phase.QUEUED, "take over refused while queued");
        expect(TwinTaskState.onHandBack(Phase.USER_IN_CONTROL) == Phase.RUNNING, "hand back resumes");
        expect(TwinTaskState.onHandBack(Phase.RUNNING) == Phase.RUNNING, "hand back ignored when running");

        expect(TwinTaskState.onFinished(Phase.RUNNING, true) == Phase.DONE, "finish ok");
        expect(TwinTaskState.onFinished(Phase.RUNNING, false) == Phase.FAILED, "finish failed");
        expect(TwinTaskState.onFinished(Phase.DONE, false) == Phase.DONE, "terminal is sticky");

        expect(TwinTaskState.canStart(Phase.IDLE), "idle can start");
        expect(TwinTaskState.canStart(Phase.DONE), "done can start again");
        expect(!TwinTaskState.canStart(Phase.RUNNING), "running cannot start");
        expect(TwinTaskState.onStart(Phase.IDLE) == Phase.QUEUED, "start -> queued");
        expect(TwinTaskState.onStart(Phase.RUNNING) == Phase.RUNNING, "start refused while running");
    }

    private static void lifecycle() {
        // A full happy path with a pause, a stop and a hand-back.
        Phase phase = Phase.IDLE;
        phase = TwinTaskState.onStart(phase);
        expect(phase == Phase.QUEUED, "lifecycle queued");
        phase = Phase.PREPARING;
        phase = Phase.RUNNING;
        phase = TwinTaskState.onPause(phase);
        expect(phase == Phase.PAUSED, "lifecycle paused");
        phase = TwinTaskState.onResume(phase);
        phase = TwinTaskState.onStopRequested(phase);
        expect(phase == Phase.STOPPING, "lifecycle stopping");
        phase = TwinTaskState.onStopCompleted(phase);
        expect(phase == Phase.USER_IN_CONTROL, "lifecycle hand-over");
        phase = TwinTaskState.onHandBack(phase);
        expect(phase == Phase.RUNNING, "lifecycle resumed by hand-back");
        phase = TwinTaskState.onFinished(phase, true);
        expect(phase == Phase.DONE, "lifecycle done");
        expect(TwinTaskState.canStart(phase), "lifecycle can start again");
    }

    private static void labels() {
        expect("twin_status_running".equals(TwinTaskState.labelKey(Phase.RUNNING)), "running label");
        expect("twin_status_paused".equals(TwinTaskState.labelKey(Phase.PAUSED)), "paused label");
        expect("twin_status_stopping_hand".equals(TwinTaskState.labelKey(Phase.STOPPING)),
                "stopping label");
        expect("twin_status_user".equals(TwinTaskState.labelKey(Phase.USER_IN_CONTROL)),
                "hand-over label");
        expect("twin_agent_failed".equals(TwinTaskState.labelKey(Phase.FAILED)), "failed label");
        for (Phase phase : Phase.values()) {
            String key = TwinTaskState.labelKey(phase);
            expect(key != null && !key.isEmpty(), "label for " + phase);
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}