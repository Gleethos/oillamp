package dev.oillamp;

import java.time.Instant;
import java.util.Optional;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;
import dev.lamp.Problem;

/// Where a session has got to.
///
/// The states differ in what an event means. In `AwaitingTerminal` the container is up but
/// nobody is connected, so a terminal that never connects must end the session. In `Running`,
/// shell windows come and go as the user likes; only Ctrl-C (or closing the terminal oillamp was
/// started from), `oillamp stop` or the sandbox dying ends it.
///
/// Only the supervisor's event loop changes the state, and only by replacing it with a new value.
/// [SessionMachine] decides each change. Users see a description of it as
/// [LampEvent.SessionStatus].
sealed interface SessionState {

    /// The container has been asked to start; nothing is known about it yet.
    record Starting(Instant since) implements SessionState {}

    /// The sandbox answered on both sockets; the terminal window has been asked to open.
    record AwaitingTerminal(Instant since, ReadyInfo ready) implements SessionState {}

    /// The user's shell connected, so the session is up.
    ///
    /// @param shellWindowOpen whether the shell window oillamp opened is still open. Closing it
    ///                        ends nothing; it is tracked only so that `oillamp status` is right
    /// @param extraShells     how many `oillamp shell` sessions are attached
    record Running(Instant since, ReadyInfo ready, boolean shellWindowOpen, int extraShells)
            implements SessionState {}

    /// The shutdown sequence is running; further events are ignored.
    record ShuttingDown(Instant since, ShutdownReason reason) implements SessionState {}

    /// Final. Carries the exit code the process will leave with.
    record Stopped(ShutdownReason reason, ExitStatus exit) implements SessionState {}

    /// Why a session is ending. Both the exit code and the closing summary are derived from it, so
    /// they cannot disagree.
    sealed interface ShutdownReason {

        /// Ctrl-C, SIGTERM or SIGHUP. SIGHUP is what the terminal oillamp was started from sends
        /// when it is closed, so closing that terminal ends the session too.
        ///
        /// `wasRunning` is recorded when the interrupt happens, because it decides the exit
        /// code: interrupting a working session is a normal way to end it (0), interrupting one that
        /// never reached `Running` is a failed start (130).
        record UserInterrupt(String signal, boolean wasRunning) implements ShutdownReason {}

        /// `oillamp stop`, or another front end through the control socket.
        record StopCommand(String source) implements ShutdownReason {}

        /// The container exited on its own — always a failure, whatever its exit code says.
        record ContainerDied(int exitCode) implements ShutdownReason {}

        /// The session never came up. Carries the problem that already explained why.
        record StartupFailed(Problem problem) implements ShutdownReason {}

        /// oillamp itself failed while it ran the session. Carries the problem that reported it.
        record Crashed(Problem problem) implements ShutdownReason {}

        /// The process exit code for this reason.
        default ExitStatus exitStatus() {
            return switch (this) {
                case StopCommand ignored    -> ExitStatus.SUCCESS;
                case UserInterrupt interrupt -> interrupt.wasRunning()
                        ? ExitStatus.SUCCESS : ExitStatus.INTERRUPTED;
                case ContainerDied ignored  -> ExitStatus.SESSION_FAILED;
                case StartupFailed ignored  -> ExitStatus.SESSION_FAILED;
                case Crashed ignored        -> ExitStatus.ERROR;
            };
        }

        /// One line for the summary, in the terms the user would use.
        default String describe() {
            return switch (this) {
                case UserInterrupt interrupt -> "interrupted (" + interrupt.signal() + ")";
                case StopCommand stop        -> "asked to stop by " + stop.source();
                case ContainerDied died      -> "the sandbox container exited (code "
                                              + died.exitCode() + ")";
                case StartupFailed failed    -> "the session could not be started — "
                                              + failed.problem().code();
                case Crashed crashed         -> "oillamp itself failed — " + crashed.problem().code();
            };
        }
    }

    /// The state's name as `oillamp status` prints it.
    default String name() {
        return switch (this) {
            case Starting ignored         -> "starting";
            case AwaitingTerminal ignored -> "awaiting-terminal";
            case Running ignored          -> "running";
            case ShuttingDown ignored     -> "shutting-down";
            case Stopped ignored          -> "stopped";
        };
    }

    /// True once the supervisor has stopped reacting to anything.
    default boolean isFinal() { return this instanceof Stopped; }

    /// True while the shutdown sequence has not begun — the states an event can still change.
    default boolean isLive() {
        return this instanceof Starting || this instanceof AwaitingTerminal || this instanceof Running;
    }

    /// How many extra `oillamp shell` sessions are attached.
    default int extraShells() {
        return this instanceof Running running ? running.extraShells() : 0;
    }

    /// When the session entered this state, not when the session began.
    ///
    /// The terminal timeout counts from here: "not connected for 60 seconds" means 60 seconds
    /// since the terminal was asked to open. The session's own start time is kept by the supervisor
    /// and used for the uptime.
    default Optional<Instant> enteredAt() {
        return switch (this) {
            case Starting s         -> Optional.of(s.since());
            case AwaitingTerminal s -> Optional.of(s.since());
            case Running s          -> Optional.of(s.since());
            case ShuttingDown s     -> Optional.of(s.since());
            case Stopped ignored    -> Optional.empty();
        };
    }
}
