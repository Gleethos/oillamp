package dev.oillamp;

import java.time.Instant;

import dev.lamp.Problem;

import sprouts.Tuple;

/// Something that happened to a running session: the container becoming ready or exiting, a shell
/// connecting or disconnecting, a signal, `oillamp stop`, or a second passing.
///
/// Several threads produce these; one thread, the supervisor's event loop, consumes them. That is
/// why the session's state needs no locking.
sealed interface SessionEvent {

    /// One second has passed. This is how timeouts are noticed.
    record Tick(Instant now) implements SessionEvent {}

    /// The sandbox is up and answering on its sockets.
    record ContainerReady(ReadyInfo info) implements SessionEvent {}

    /// The container is gone. Always bad news: nothing in a healthy session stops the container.
    record ContainerExited(int exitCode) implements SessionEvent {}

    /// The terminal window oillamp opened has connected through the primary SSH relay.
    record PrimaryConnected() implements SessionEvent {}

    /// That terminal closed. The session carries on: the user may open another shell with
    /// `oillamp shell`, or none at all.
    record PrimaryDisconnected() implements SessionEvent {}

    record ShellConnected() implements SessionEvent {}
    record ShellDisconnected() implements SessionEvent {}

    /// A signal reached the process — Ctrl-C, the terminal oillamp was started from closing, or the
    /// session being killed politely.
    record Interrupted(String signal) implements SessionEvent {}

    /// Someone asked for the session to end, over the control socket or from `stop`.
    record StopRequested(String source) implements SessionEvent {}

    /// An action could not be carried out.
    ///
    /// Which action it was decides how much it matters, and the difference is sharp: a viewer
    /// that will not open costs the user their view of a session that is otherwise fine, while a
    /// terminal that will not open leaves a sandbox nobody is in.
    record ActionFailed(SessionAction action, Problem problem) implements SessionEvent {}

    /// The shutdown sequence has finished, with whatever went wrong along the way.
    record ShutdownCompleted(Tuple<Problem> problems) implements SessionEvent {}

    /// What this event is called in the session log.
    default String describe() {
        return switch (this) {
            case Tick ignored               -> "tick";
            case ContainerReady ready       -> "the sandbox is ready — " + ready.info().describe();
            case ContainerExited exited     -> "the container exited with code " + exited.exitCode();
            case PrimaryConnected ignored   -> "the shell window connected";
            case PrimaryDisconnected ignored-> "the shell window closed";
            case ShellConnected ignored     -> "an extra shell connected";
            case ShellDisconnected ignored  -> "an extra shell closed";
            case Interrupted interrupted    -> "interrupted by " + interrupted.signal();
            case StopRequested stop         -> "stop requested by " + stop.source();
            case ActionFailed failed        -> "could not " + failed.action().describe()
                                             + " — " + failed.problem().code();
            case ShutdownCompleted done     -> "shutdown finished with " + done.problems().size()
                                             + " problem(s)";
        };
    }
}
