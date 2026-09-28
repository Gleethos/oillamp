package dev.lamp;

import java.time.Duration;

/// Everything oillamp reports, as values.
///
/// oillamp never writes to the console directly. It emits these events, and the console
/// renderer prints them. Tests inspect them, and a future graphical front end could display them
/// its own way.
///
/// Public because any caller that wants to show progress, including a GUI, needs to see them.
public sealed interface LampEvent {

    /// The phases of `oillamp at`, in order.
    enum Phase {
        /// Check and repair the host itself: packages, subuid ranges, a working rootless podman.
        HOST,
        /// Create or load the lamp: config, identity, lock, keys, per-session files.
        LAMP,
        /// Make sure a sandbox image matching the current configuration exists.
        IMAGE,
        /// Start the container, the relays and the windows, then supervise.
        SESSION
    }

    /// A planned or executed unit of work, projected for display.
    ///
    /// @param kind     the step's type name, for example `"InstallPackages"`; safe to match on
    /// @param describe one line, for the console and for `--dry-run`
    /// @param detail   the full story, for the session log
    record StepInfo(String kind, String describe, String detail) {}

    record PhaseStarted(Phase phase)                    implements LampEvent {}
    record PhaseFinished(Phase phase, Duration took)    implements LampEvent {}

    /// A check that passed, e.g. `[host] ✓ podman 5.4.2, rootless, crun`.
    record Ok(String area, String text)                 implements LampEvent {}
    /// A neutral remark, e.g. why the session fell back to software rendering.
    record Info(String area, String text)               implements LampEvent {}

    /// Emitted instead of performing a step when `--dry-run` is given.
    record StepPlanned(StepInfo step)                   implements LampEvent {}
    record StepStarted(StepInfo step)                   implements LampEvent {}
    record StepSucceeded(StepInfo step, Duration took)  implements LampEvent {}
    record StepSkipped(StepInfo step, String why)       implements LampEvent {}

    /// A line of output from a subprocess, tagged with its source (e.g. `"image"`, `"sway"`).
    record Output(String sourceTag, String line)        implements LampEvent {}

    /// What the user asked to see: the effective configuration, the version, the usage text.
    ///
    /// Unlike [Output], which is a subprocess's output and hidden unless `--verbose`,
    /// an answer is always shown.
    record Answer(String text)                          implements LampEvent {}

    /// A summary of a session's state, such as "running, up 3 minutes, one extra shell".
    ///
    /// This is a description of the supervisor's internal state, not the state itself, so the
    /// internal states can change without changing this public type.
    record SessionStatus(String state, String detail, Duration uptime, int extraShells) {}

    record SessionStateChanged(SessionStatus status)    implements LampEvent {}

    /// A window oillamp opened on the user's desktop: the terminal or a viewer.
    ///
    /// It carries the full command line, because when a window opens and closes again, the first
    /// question is what exactly was run.
    record WindowOpened(String what, sprouts.Tuple<String> argv) implements LampEvent {}

    /// A block of lines with a title, such as the briefing when a session starts or the summary when it ends.
    record Summary(String title, sprouts.Tuple<String> lines) implements LampEvent {}

    /// Something went wrong but oillamp carried on.
    record Warning(Problem problem)                     implements LampEvent {}
    /// Something went wrong and oillamp stopped.
    record Failure(Problem problem)                     implements LampEvent {}
}
