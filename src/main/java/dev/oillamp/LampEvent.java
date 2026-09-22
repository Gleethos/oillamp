package dev.oillamp;

import java.time.Duration;

/**
 * Everything oillamp does, as an observable stream of values — spec §26.7 / NFR-08.
 *
 * <p>oillamp never writes to the console directly. It emits these events, and subscribers
 * render them: the CLI's console renderer, the session log, and — later — the Swing GUI,
 * which must be able to drive the same core without changes. Tests subscribe too, which is
 * why this is public API while the machinery producing it is not.
 *
 * <p>Deliberately <b>public</b>: NFR-08 requires a GUI to drive this same core and render its own
 * view of progress. It can only do that if it can see the events, so they are part of the contract.
 */
public sealed interface LampEvent {

    /** The four startup phases of spec §10.5. Each re-probes, because earlier phases change the host. */
    enum Phase {
        /** Check and repair the host itself: packages, subuid ranges, a working rootless podman. */
        HOST,
        /** Create or load the lamp: config, identity, lock, keys, per-session files. */
        LAMP,
        /** Make sure a sandbox image matching the current configuration exists. */
        IMAGE,
        /** Start the container, the relays and the windows, then supervise. */
        SESSION
    }

    /**
     * A planned or executed unit of work, projected for display.
     *
     * @param kind     stable type name, e.g. {@code "InstallPackages"} — safe to assert on
     * @param describe one line, for the console and for {@code --dry-run}
     * @param detail   the full story, for the session log
     */
    record StepInfo(String kind, String describe, String detail) {}

    record PhaseStarted(Phase phase)                    implements LampEvent {}
    record PhaseFinished(Phase phase, Duration took)    implements LampEvent {}

    /** A check that passed, e.g. {@code [host] ✓ podman 5.4.2, rootless, crun}. */
    record Ok(String area, String text)                 implements LampEvent {}
    /** A neutral remark, e.g. why the session fell back to software rendering. */
    record Info(String area, String text)               implements LampEvent {}

    /** Emitted instead of running anything when {@code --dry-run} is given — spec FR-12. */
    record StepPlanned(StepInfo step)                   implements LampEvent {}
    record StepStarted(StepInfo step)                   implements LampEvent {}
    record StepSucceeded(StepInfo step, Duration took)  implements LampEvent {}
    record StepSkipped(StepInfo step, String why)       implements LampEvent {}

    /** A line of output from a subprocess, tagged with its source (e.g. {@code "image"}, {@code "sway"}). */
    record Output(String sourceTag, String line)        implements LampEvent {}

    /**
     * What the user asked to see — the effective configuration, the version, the usage text.
     *
     * <p>Distinct from {@link Output}, which is a subprocess talking and is hidden unless
     * {@code --verbose}. An answer is the whole point of the command that produced it, so it is
     * always shown.
     */
    record Answer(String text)                          implements LampEvent {}

    /**
     * How far a session has got — spec §26.7.
     *
     * <p>A rendering of the supervisor's own state, not the state itself: the states are
     * internal and change with the design, while "running, up 3 minutes, one extra shell" is
     * what a front end and a user both want, and neither should have to be recompiled when a
     * state is added.
     */
    record SessionStatus(String state, String detail, Duration uptime, int extraShells) {}

    record SessionStateChanged(SessionStatus status)    implements LampEvent {}

    /**
     * A window oillamp opened on the user's desktop — the sandbox terminal, or the viewer.
     *
     * <p>Carries the whole command line, which is deliberate. These are the two things oillamp
     * does that a user cannot see the inside of, and when a terminal opens and closes again the
     * first useful question is always "what exactly did you run?".
     */
    record WindowOpened(String what, sprouts.Tuple<String> argv) implements LampEvent {}

    /** What a session came to: how long it ran, why it ended, what it left behind (§10.7). */
    record Summary(String title, sprouts.Tuple<String> lines) implements LampEvent {}

    /** Something went wrong but oillamp carried on. */
    record Warning(Problem problem)                     implements LampEvent {}
    /** Something went wrong and oillamp stopped. */
    record Failure(Problem problem)                     implements LampEvent {}
}
