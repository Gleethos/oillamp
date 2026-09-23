package dev.oillamp;

/// Something the supervisor should do during a session.
///
/// [SessionMachine] decides which actions to take and returns them as values;
/// [Supervisor] carries them out and reports the results back as [SessionEvent]s. This
/// split keeps the session's rules in a pure function that tests can check quickly.
sealed interface SessionAction {

    /// Open a desktop viewer window. Closing it does not end the session.
    record LaunchViewer(boolean viewOnly) implements SessionAction {}

    /// Open the terminal window whose SSH connection is the session.
    ///
    /// Always a new window. The terminal oillamp was started from keeps printing what the
    /// session is doing.
    record LaunchTerminal() implements SessionAction {}

    /// Close any extra shells opened with `oillamp shell`. They do not outlive the session.
    record CloseShells() implements SessionAction {}

    /// Run the shutdown sequence (`Supervisor.shutDown`), then report `ShutdownCompleted`.
    record BeginShutdown(SessionState.ShutdownReason reason) implements SessionAction {}

    /// Report an event to the user.
    record Announce(LampEvent event) implements SessionAction {}

    /// Leave, with this code. Only ever the last action of a transition.
    record Exit(ExitStatus status) implements SessionAction {}

    /// What this action is called in the session log.
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
