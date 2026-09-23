package dev.oillamp;

import java.time.Duration;
import java.time.Instant;

import sprouts.Tuple;

/// The rules a running session follows, as a pure function from (state, event, time) to the next
/// state and a list of actions. The full table is in `docs/ARCHITECTURE.md`, "The running
/// session".
///
/// Every way a session can end is decided here and nowhere else: Ctrl-C or the terminal oillamp was
/// started from closing, `oillamp stop`, the container dying, a window that never opened. Closing
/// a shell window or a viewer is deliberately not on that list: the user may close and reopen
/// those as often as they like. If each part of the supervisor decided for itself what a
/// disconnection means, it would be easy to end a session nobody asked to end, or to exit 0 after
/// the sandbox died.
///
/// Because there is no I/O here, tests can check awkward combinations quickly: a container
/// exiting during startup, the shell window closing while extra shells are attached, a stop
/// arriving while the session is already shutting down.
record SessionMachine(Settings settings) {

    /// The settings the rules need that are not part of the state.
    ///
    /// @param terminalTimeout how long the terminal window has to connect before the session is
    ///                        given up (`timeouts.terminal_connect_seconds`, 60 by default)
    /// @param openViewer      `viewer.open_on_start`, unless `--no-viewer` was given
    /// @param viewOnly        `viewer.view_only`: the user watches but cannot type
    /// @param lamp            the lamp directory, for the commands the messages suggest
    record Settings(Duration terminalTimeout, boolean openViewer, boolean viewOnly, String lamp) {}

    /// Where the session goes next, and what should be done on the way.
    record Transition(SessionState next, Tuple<SessionAction> actions) {

        static Transition to(SessionState next, SessionAction... actions) {
            return new Transition(next, Tuple.of(SessionAction.class, actions));
        }

        /// Nothing to do. Used for events that arrive after the decision they would have changed.
        static Transition stay(SessionState state) {
            return new Transition(state, Tuple.of(SessionAction.class));
        }
    }

    /// Decides what an event means in the current state.
    ///
    /// A container exiting means different things by state: during `Starting` the sandbox
    /// never came up (startup failed); later, a working session lost its sandbox (container died).
    /// Both exit with code 5 but are described differently.
    ///
    /// @param now when this event happened. It is the only clock the machine has, so that timeouts can
    ///            be decided without the machine being allowed to ask the time itself
    Transition step(SessionState state, SessionEvent event, Instant now) {
        // A stopped session has already exited; there is nothing left to change.
        if (state instanceof SessionState.Stopped) return Transition.stay(state);

        if (state instanceof SessionState.ShuttingDown shuttingDown)
            return event instanceof SessionEvent.ShutdownCompleted completed
                    ? finish(shuttingDown, completed)
                    : Transition.stay(state);   // anything else during shutdown is ignored

        return switch (event) {
            case SessionEvent.ActionFailed failed -> actionFailed(state, failed, now);

            // These two can arrive in any live state, so they are matched before the per-state
            // rows rather than repeated inside each of them.
            case SessionEvent.Interrupted interrupted -> shutDown(state, now,
                    new SessionState.ShutdownReason.UserInterrupt(
                            interrupted.signal(), state instanceof SessionState.Running));
            case SessionEvent.StopRequested stop -> shutDown(state, now,
                    new SessionState.ShutdownReason.StopCommand(stop.source()));

            case SessionEvent.ContainerExited exited -> containerExited(state, exited, now);

            case SessionEvent.ContainerReady ready when state instanceof SessionState.Starting ->
                    started(ready, now);

            case SessionEvent.PrimaryConnected ignored
                    when state instanceof SessionState.AwaitingTerminal awaiting ->
                    Transition.to(new SessionState.Running(now, awaiting.ready(), true, 0),
                            new SessionAction.Announce(new LampEvent.Ok("session",
                                    "your shell is connected — closing its window leaves the "
                                  + "session running")));

            // The user closed the shell window. That is not the user saying they are finished: they
            // may only want a fresh shell, or none for a while. The session ends when they say so.
            case SessionEvent.PrimaryDisconnected ignored
                    when state instanceof SessionState.Running running ->
                    Transition.to(new SessionState.Running(running.since(), running.ready(), false,
                                    running.extraShells()),
                            new SessionAction.Announce(new LampEvent.Info("session",
                                    "the shell window closed; the session keeps running — "
                                  + "`oillamp shell " + settings.lamp() + "` opens another, "
                                  + "Ctrl-C here ends the session")));

            case SessionEvent.ShellConnected ignored when state instanceof SessionState.Running running ->
                    shells(running, +1);
            case SessionEvent.ShellDisconnected ignored when state instanceof SessionState.Running running ->
                    shells(running, -1);

            case SessionEvent.Tick ignored -> tick(state, now);

            // Anything else is an event that no longer applies, such as a shell disconnecting while the
            // session is still starting, a second readiness report. Ignored on purpose.
            case SessionEvent ignored -> Transition.stay(state);
        };
    }

    // ─── the rows ──────────────────────────────────────────────────────────────────────────

    /// The sandbox is ready, so both windows open now. The terminal oillamp was started from is left
    /// alone; it keeps showing what the session is doing.
    private Transition started(SessionEvent.ContainerReady ready, Instant now) {
        Tuple<SessionAction> actions = Tuple.of(SessionAction.class,
                new SessionAction.Announce(new LampEvent.Ok("session",
                        "the desktop is up — " + ready.info().describe())));
        if (settings.openViewer())
            actions = actions.add(new SessionAction.LaunchViewer(settings.viewOnly()));
        actions = actions.add(new SessionAction.LaunchTerminal());
        return new Transition(new SessionState.AwaitingTerminal(now, ready.info()), actions);
    }

    private Transition shells(SessionState.Running running, int change) {
        int count = Math.max(0, running.extraShells() + change);
        return Transition.to(new SessionState.Running(running.since(), running.ready(),
                        running.shellWindowOpen(), count),
                new SessionAction.Announce(new LampEvent.Info("session",
                        count == 0 ? "the extra shell closed"
                                   : count + " extra shell" + (count == 1 ? "" : "s") + " attached")));
    }

    private Transition containerExited(SessionState state, SessionEvent.ContainerExited exited, Instant now) {
        if (state instanceof SessionState.Starting)
            return shutDown(state, now, new SessionState.ShutdownReason.StartupFailed(
                    Problems.sandboxDied("the sandbox", "(exit code " + exited.exitCode() + ")")));
        return shutDown(state, now, new SessionState.ShutdownReason.ContainerDied(exited.exitCode()));
    }

    /// A window could not be opened. A missing viewer only costs the user their view of a session
    /// that otherwise works, and `oillamp view` can open another, so it is a warning. A missing
    /// terminal means the session did not start the way the user asked, usually because the
    /// terminal setting is wrong. The session is shut down and the reason reported, so the user
    /// fixes the setting now rather than working around it every session.
    private Transition actionFailed(SessionState state, SessionEvent.ActionFailed failed, Instant now) {
        if (failed.action() instanceof SessionAction.LaunchTerminal)
            return shutDown(state, now,
                    new SessionState.ShutdownReason.StartupFailed(failed.problem()));
        return Transition.to(state, new SessionAction.Announce(new LampEvent.Warning(failed.problem())));
    }

    private Transition tick(SessionState state, Instant now) {
        if (state instanceof SessionState.AwaitingTerminal awaiting
                && !now.isBefore(awaiting.since().plus(settings.terminalTimeout())))
            return shutDown(state, now, new SessionState.ShutdownReason.StartupFailed(
                    Problems.terminalDidNotConnect(settings.terminalTimeout())));
        return Transition.stay(state);
    }

    /// Every path into shutdown goes through here, so the extra shells are always closed and the
    /// shutdown sequence is always started exactly once.
    private Transition shutDown(SessionState state, Instant now, SessionState.ShutdownReason reason) {
        Tuple<SessionAction> actions = Tuple.of(SessionAction.class);
        // A session that failed to start must say why before shutting down. Without this the
        // problem stays inside the shutdown reason and oillamp exits 5 without explanation.
        if (reason instanceof SessionState.ShutdownReason.StartupFailed failed)
            actions = actions.add(new SessionAction.Announce(new LampEvent.Failure(failed.problem())));
        if (state.extraShells() > 0) actions = actions.add(new SessionAction.CloseShells());
        actions = actions.add(new SessionAction.BeginShutdown(reason));
        return new Transition(new SessionState.ShuttingDown(now, reason), actions);
    }

    private Transition finish(SessionState.ShuttingDown state, SessionEvent.ShutdownCompleted done) {
        // Cleanup problems are reported as warnings, but do not change the exit code: that still
        // describes how the session itself ended.
        ExitStatus exit = state.reason().exitStatus();
        Tuple<SessionAction> actions = Tuple.of(SessionAction.class);
        for (Problem problem : done.problems())
            actions = actions.add(new SessionAction.Announce(new LampEvent.Warning(problem)));
        actions = actions.add(new SessionAction.Exit(exit));
        return new Transition(new SessionState.Stopped(state.reason(), exit), actions);
    }
}
