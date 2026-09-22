package dev.oillamp;

import java.time.Instant;

/**
 * Where a session has got to — spec §10.6, §24.5.
 *
 * <p>A session is not "started" or "stopped"; it passes through states that differ in what is
 * true of the world, and the difference matters. In {@code AwaitingTerminal} the container is up
 * but nobody is in it yet, so a terminal that never appears has to end the session rather than
 * leave a sandbox running with no one watching. In {@code Running} the opposite holds: the
 * terminal closing is the user saying they are done.
 *
 * <p>The state is owned by one thread (§26.5) and only ever replaced, never mutated, which is
 * what allows every transition to be decided by a pure function — see {@link SessionMachine}.
 *
 * <p>Deliberately <b>package-private</b>: how the supervisor tracks a session. What a user sees
 * of it is {@link LampEvent.SessionStatus}, which is public and is a rendering of this.
 */
sealed interface SessionState {

    /** The container has been asked to start; nothing is known about it yet. */
    record Starting(Instant since) implements SessionState {}

    /** The sandbox answered on both sockets; the terminal window has been asked to open. */
    record AwaitingTerminal(Instant since, ReadyInfo ready) implements SessionState {}

    /** The user's shell is connected. {@code extraShells} counts {@code oillamp shell} sessions. */
    record Running(Instant since, ReadyInfo ready, int extraShells) implements SessionState {}

    /** The shutdown sequence of §10.7 is under way; further events are ignored. */
    record ShuttingDown(Instant since, ShutdownReason reason) implements SessionState {}

    /** Final. Carries the exit code the process will leave with. */
    record Stopped(ShutdownReason reason, ExitStatus exit) implements SessionState {}

    /**
     * Why a session is ending — spec §24.5.
     *
     * <p>This is the whole input to two decisions: the exit code (§27.5) and what the closing
     * summary tells the user. Keeping them derived from one value is what stops a session that
     * failed from exiting 0 because some other branch of the shutdown path forgot.
     */
    sealed interface ShutdownReason {

        /** The user closed the terminal window oillamp opened. The ordinary way to finish (FR-06). */
        record TerminalClosed() implements ShutdownReason {}

        /**
         * Ctrl-C, SIGTERM or SIGHUP.
         *
         * <p>{@code wasRunning} is carried here rather than looked up later because §27.5 draws
         * its line exactly there: interrupting a session that was <em>working</em> is a normal
         * way to end it and exits 0, while interrupting one that never got going is a failed
         * start and exits 130.
         */
        record UserInterrupt(String signal, boolean wasRunning) implements ShutdownReason {}

        /** {@code oillamp stop}, or another front end through the control socket. */
        record StopCommand(String source) implements ShutdownReason {}

        /** The container exited on its own — always a failure, whatever its exit code says. */
        record ContainerDied(int exitCode) implements ShutdownReason {}

        /** The session never came up. Carries the problem that already explained why. */
        record StartupFailed(Problem problem) implements ShutdownReason {}

        /** The exit code of §27.5 that this reason produces. */
        default ExitStatus exitStatus() {
            return switch (this) {
                case TerminalClosed ignored -> ExitStatus.SUCCESS;
                case StopCommand ignored    -> ExitStatus.SUCCESS;
                case UserInterrupt interrupt -> interrupt.wasRunning()
                        ? ExitStatus.SUCCESS : ExitStatus.INTERRUPTED;
                case ContainerDied ignored  -> ExitStatus.SESSION_FAILED;
                case StartupFailed ignored  -> ExitStatus.SESSION_FAILED;
            };
        }

        /** One line for the summary, in the terms the user would use. */
        default String describe() {
            return switch (this) {
                case TerminalClosed ignored  -> "you closed the terminal window";
                case UserInterrupt interrupt -> "interrupted (" + interrupt.signal() + ")";
                case StopCommand stop        -> "asked to stop by " + stop.source();
                case ContainerDied died      -> "the sandbox container exited (code "
                                              + died.exitCode() + ")";
                case StartupFailed failed    -> "the session could not be started — "
                                              + failed.problem().code();
            };
        }
    }

    /** The state's name as {@code oillamp status} prints it. */
    default String name() {
        return switch (this) {
            case Starting ignored         -> "starting";
            case AwaitingTerminal ignored -> "awaiting-terminal";
            case Running ignored          -> "running";
            case ShuttingDown ignored     -> "shutting-down";
            case Stopped ignored          -> "stopped";
        };
    }

    /** True once the supervisor has stopped reacting to anything. */
    default boolean isFinal() { return this instanceof Stopped; }

    /** True while the shutdown sequence has not begun — the states an event can still change. */
    default boolean isLive() {
        return this instanceof Starting || this instanceof AwaitingTerminal || this instanceof Running;
    }

    /** How many extra {@code oillamp shell} sessions are attached. */
    default int extraShells() {
        return this instanceof Running running ? running.extraShells() : 0;
    }

    /**
     * When the session entered this state — not when the session began.
     *
     * <p>That distinction is what the two startup timeouts are measured against: "the terminal
     * has not connected for 60 seconds" means sixty seconds since the terminal was asked to
     * open, and would be a different and much weaker statement if it counted from the moment
     * {@code oillamp at} was typed. The session's own start is held by the supervisor, which is
     * what the uptime and the closing summary are computed from.
     */
    default java.util.Optional<Instant> enteredAt() {
        return switch (this) {
            case Starting s         -> java.util.Optional.of(s.since());
            case AwaitingTerminal s -> java.util.Optional.of(s.since());
            case Running s          -> java.util.Optional.of(s.since());
            case ShuttingDown s     -> java.util.Optional.of(s.since());
            case Stopped ignored    -> java.util.Optional.empty();
        };
    }
}
