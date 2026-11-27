/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

/**
 * The lifetime of a Twin task, as the shell needs to render it.
 *
 * The newest build shows a live status bar over the phone: queued, preparing,
 * working, paused at a step, stopping so the user gets control, done, failed. The
 * transitions matter more than the labels — a task may only stop when it is
 * running, and a finished task cannot be paused — so the rules live here, without
 * Android types, and are covered by host tests.
 */
public final class TwinTaskState {
    private TwinTaskState() {}

    public enum Phase {
        /** Nothing asked yet. */
        IDLE,
        /** The phone is starting; the task has not begun. */
        QUEUED,
        /** Reading the screen before acting. */
        PREPARING,
        /** Acting on the phone. */
        RUNNING,
        /** The user asked to pause; the task holds at the current step. */
        PAUSED,
        /** Stopping so the user can take the phone. */
        STOPPING,
        /** The user holds the phone. */
        USER_IN_CONTROL,
        DONE,
        FAILED
    }

    /** Whether the phone is being driven by the Twin right now. */
    public static boolean isBusy(Phase phase) {
        return phase == Phase.QUEUED
                || phase == Phase.PREPARING
                || phase == Phase.RUNNING
                || phase == Phase.STOPPING;
    }

    /** Whether the task has ended, one way or another. */
    public static boolean isTerminal(Phase phase) {
        return phase == Phase.IDLE || phase == Phase.DONE || phase == Phase.FAILED;
    }

    public static boolean canPause(Phase phase) {
        return phase == Phase.RUNNING;
    }

    public static boolean canResume(Phase phase) {
        return phase == Phase.PAUSED;
    }

    /** Stopping is only meaningful while the Twin is working; it ends in hand-over. */
    public static boolean canStop(Phase phase) {
        return phase == Phase.RUNNING || phase == Phase.PAUSED || phase == Phase.PREPARING;
    }

    /** Take-over is offered whenever the Twin is not already handing the phone over. */
    public static boolean canTakeOver(Phase phase) {
        return phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    /** The phase after the user asks to pause, or the same phase when refused. */
    public static Phase onPause(Phase phase) {
        return canPause(phase) ? Phase.PAUSED : phase;
    }

    public static Phase onResume(Phase phase) {
        return canResume(phase) ? Phase.RUNNING : phase;
    }

    /** Asking to stop first finishes the current step, hence STOPPING. */
    public static Phase onStopRequested(Phase phase) {
        return canStop(phase) ? Phase.STOPPING : phase;
    }

    /** The step boundary finally reached: the user gets the phone. */
    public static Phase onStopCompleted(Phase phase) {
        return phase == Phase.STOPPING ? Phase.USER_IN_CONTROL : phase;
    }

    public static Phase onTakeOver(Phase phase) {
        return canTakeOver(phase) ? Phase.USER_IN_CONTROL : phase;
    }

    /** Handing the phone back resumes work from where it stopped. */
    public static Phase onHandBack(Phase phase) {
        return phase == Phase.USER_IN_CONTROL ? Phase.RUNNING : phase;
    }

    public static Phase onFinished(Phase phase, boolean success) {
        if (isTerminal(phase)) return phase;
        return success ? Phase.DONE : Phase.FAILED;
    }

    /** A new task may only start from idle or after a finished one. */
    public static boolean canStart(Phase phase) {
        return phase == Phase.IDLE || phase == Phase.DONE || phase == Phase.FAILED;
    }

    public static Phase onStart(Phase phase) {
        return canStart(phase) ? Phase.QUEUED : phase;
    }

    /** Label key for the status bar; callers map this to their own strings. */
    public static String labelKey(Phase phase) {
        switch (phase) {
            case IDLE:
                return "twin_status_done";
            case QUEUED:
                return "twin_agent_queued";
            case PREPARING:
                return "twin_agent_preparing";
            case RUNNING:
                return "twin_status_running";
            case PAUSED:
                return "twin_status_paused";
            case STOPPING:
                return "twin_status_stopping_hand";
            case USER_IN_CONTROL:
                return "twin_status_user";
            case DONE:
                return "twin_status_done";
            case FAILED:
                return "twin_agent_failed";
            default:
                throw new IllegalStateException("unhandled phase " + phase);
        }
    }
}