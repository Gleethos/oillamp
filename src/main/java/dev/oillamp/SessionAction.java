package dev.oillamp;

/**
 * Something the supervisor should do, decided by a pure function and carried out by an impure
 * one — spec §24.5.
 *
 * <p>This is the whole reason the session machine can be a pure function. Deciding "the terminal
 * window should open now" and actually opening it are different jobs: the first is a rule that a
 * scenario can check exhaustively in milliseconds, the second is a process on someone's desktop.
 * The machine returns these values; {@link Supervisor} performs them and reports back as events.
 *
 * <p>Deliberately <b>package-private</b>: the instruction set between the session machine and its
 * runner. Both sides are internal, and a new kind of action must never be an API change.
 */
sealed interface SessionAction {

    /** Open the desktop viewer. Its own window; its lifetime is independent of the session (§15). */
    record LaunchViewer(boolean viewOnly) implements SessionAction {}

    /**
     * Open the terminal window that owns the session.
     *
     * <p>A <em>new</em> window, never the one oillamp was started from: the launching terminal
     * goes on printing what the session is doing, and that log is the thing the user reads when
     * they want to know what happened.
     */
    record LaunchTerminal() implements SessionAction {}

    /** Close any extra {@code oillamp shell} connections — they do not outlive the session. */
    record CloseShells() implements SessionAction {}

    /** Run the shutdown sequence of §10.7, then report back with {@code ShutdownCompleted}. */
    record BeginShutdown(SessionState.ShutdownReason reason) implements SessionAction {}

    /** Tell the user something. The machine decides what is worth saying, the console decides how. */
    record Announce(LampEvent event) implements SessionAction {}

    /** Leave, with this code. Only ever the last action of a transition. */
    record Exit(ExitStatus status) implements SessionAction {}

    /** What this action is called in the session log. */
    default String describe() {
        return switch (this) {
            case LaunchViewer viewer -> "open the desktop viewer" + (viewer.viewOnly() ? " (view only)" : "");
            case LaunchTerminal ignored -> "open the terminal window";
            case CloseShells ignored -> "close the extra shells";
            case BeginShutdown shutdown -> "shut the session down — " + shutdown.reason().describe();
            case Announce announce -> "tell the user: " + announce.event().getClass().getSimpleName();
            case Exit exit -> "exit " + exit.status().code();
        };
    }
}
