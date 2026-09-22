package dev.oillamp;

import java.time.Duration;
import java.time.Instant;

import sprouts.Tuple;

/**
 * The rules a session follows, as a pure function — spec §25.1 (normative), §10.6.
 *
 * <p>Every way a session can end is decided here and nowhere else: the terminal closing, a
 * container dying, Ctrl-C, {@code oillamp stop}, a window that never opened. That is worth
 * insisting on, because the alternative — each producer deciding for itself what a disconnection
 * means — is how a tool ends up leaving a container running after its terminal closed, or exiting
 * 0 after the sandbox died under it.
 *
 * <p>Being a function of {@code (state, event, now)} with no I/O in it means the whole table can
 * be checked in milliseconds, including the combinations that are awkward to produce for real: a
 * container exiting during startup, two shells attached when the terminal closes, a stop arriving
 * while the session is already shutting down.
 *
 * <p>Deliberately <b>package-private</b>: the transition table of §25.1. It is normative as a
 * <em>table</em>; this class is only where it is written down in Java.
 */
record SessionMachine(Settings settings) {

    /**
     * The three things the table needs that are not in the state.
     *
     * @param terminalTimeout how long a terminal window has to connect before the session is
     *                        given up on — §10.6 puts it at 60 seconds
     * @param openViewer      {@code viewer.open_on_start}, minus {@code --no-viewer}
     * @param viewOnly        {@code viewer.view_only}: the user watches but cannot type
     */
    record Settings(Duration terminalTimeout, boolean openViewer, boolean viewOnly) {

        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(60), true, false);
        }
    }

    /** Where the session goes next, and what should be done on the way. */
    record Transition(SessionState next, Tuple<SessionAction> actions) {

        static Transition to(SessionState next, SessionAction... actions) {
            return new Transition(next, Tuple.of(SessionAction.class, actions));
        }

        /** Nothing to do. Used for events that arrive after the decision they would have changed. */
        static Transition stay(SessionState state) {
            return new Transition(state, Tuple.of(SessionAction.class));
        }
    }

    /**
     * The transition table of §25.1, in the order that table gives.
     *
     * <p>The order matters in one place. A container exiting is listed twice: once for
     * {@code Starting}, where it means the sandbox never came up, and once for the later states,
     * where it means a working session lost its sandbox. Those are different failures with
     * different exit codes, so the more specific row is taken first.
     *
     * @param now when this event happened — the only clock the machine has, so that timeouts can
     *            be decided without the machine being allowed to ask the time itself
     */
    Transition step(SessionState state, SessionEvent event, Instant now) {
        // A stopped session has already exited; there is nothing left to change.
        if (state instanceof SessionState.Stopped) return Transition.stay(state);

        if (state instanceof SessionState.ShuttingDown shuttingDown)
            return event instanceof SessionEvent.ShutdownCompleted completed
                    ? finish(shuttingDown, completed)
                    : Transition.stay(state);   // §25.1: anything else during shutdown is ignored

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
                    Transition.to(new SessionState.Running(now, awaiting.ready(), 0),
                            new SessionAction.Announce(new LampEvent.Ok("session",
                                    "your shell is connected — closing that window ends the session")));

            case SessionEvent.PrimaryDisconnected ignored when state instanceof SessionState.Running ->
                    shutDown(state, now, new SessionState.ShutdownReason.TerminalClosed());

            case SessionEvent.ShellConnected ignored when state instanceof SessionState.Running running ->
                    shells(running, +1);
            case SessionEvent.ShellDisconnected ignored when state instanceof SessionState.Running running ->
                    shells(running, -1);

            case SessionEvent.Tick ignored -> tick(state, now);

            // Anything else is an event that no longer applies — a shell disconnecting while the
            // session is still starting, a second readiness report. Ignored on purpose.
            case SessionEvent ignored -> Transition.stay(state);
        };
    }

    // ─── the rows ──────────────────────────────────────────────────────────────────────────

    /**
     * The sandbox answered, so both windows open now — and the terminal oillamp was launched from
     * is left alone. It goes on showing what the session is doing, which is where the user looks
     * when something needs explaining.
     */
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
        return Transition.to(new SessionState.Running(running.since(), running.ready(), count),
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

    /**
     * §25.1 draws the line between the two windows here, and it is the right line. The viewer is
     * how the user watches; losing it costs them the view of a session that is otherwise fine,
     * and they can open another with {@code oillamp view}. The terminal <em>is</em> the session —
     * if it cannot open, nobody is in the sandbox and nothing will ever end it.
     */
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

    /**
     * Every path into shutdown goes through here, so the extra shells are always closed and the
     * sequence of §10.7 is always begun exactly once.
     */
    private Transition shutDown(SessionState state, Instant now, SessionState.ShutdownReason reason) {
        Tuple<SessionAction> actions = Tuple.of(SessionAction.class);
        // A session that failed to start must say why before it tidies itself away. The reason
        // is already a Problem, with its evidence and its fixes; leaving it inside the shutdown
        // reason would exit 5 in silence, which is the failure mode this tool exists to avoid.
        if (reason instanceof SessionState.ShutdownReason.StartupFailed failed)
            actions = actions.add(new SessionAction.Announce(new LampEvent.Failure(failed.problem())));
        if (state.extraShells() > 0) actions = actions.add(new SessionAction.CloseShells());
        actions = actions.add(new SessionAction.BeginShutdown(reason));
        return new Transition(new SessionState.ShuttingDown(now, reason), actions);
    }

    private Transition finish(SessionState.ShuttingDown state, SessionEvent.ShutdownCompleted done) {
        // A shutdown that could not tidy up does not turn a good session into a failed one, but
        // it must not be silent either: the problems are reported and the exit code stays the
        // session's own, so a script keeps reading the session's outcome rather than the tidying.
        ExitStatus exit = state.reason().exitStatus();
        Tuple<SessionAction> actions = Tuple.of(SessionAction.class);
        for (Problem problem : done.problems())
            actions = actions.add(new SessionAction.Announce(new LampEvent.Warning(problem)));
        actions = actions.add(new SessionAction.Exit(exit));
        return new Transition(new SessionState.Stopped(state.reason(), exit), actions);
    }
}
