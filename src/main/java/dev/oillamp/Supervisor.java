package dev.oillamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Tuple;

/// Runs a session: opens the two windows, holds the SSH relays, the control socket and the egress
/// proxy, watches the container, and shuts everything down when the session ends.
///
/// Without it, ending the session would leave a container, a recording and a lock behind.
///
/// One thread, the event loop in [#loop()], owns the session state. Everything that can
/// happen (a shell connecting, the container dying, Ctrl-C, `oillamp stop`, a second passing)
/// becomes a [SessionEvent] on one queue, and [SessionMachine], a pure function, decides
/// what each means. This class does the parts that cannot be pure: starting processes, moving
/// bytes, stopping the container.
///
/// The closing summary is printed here rather than decided by [SessionMachine], because it
/// needs facts only this class has: how long the session ran and what the shutdown cleaned up.
///
/// `docs/ARCHITECTURE.md`, "The running session", describes the states, threads and
/// shutdown sequence.
final class Supervisor {

    /// How long a window must stay open to count as opened. A window that exits sooner with an error is reported.
    private static final Duration VIEWER_GRACE = Duration.ofSeconds(3);

    /// How often the container is checked. A dead sandbox should be noticed in seconds, not minutes.
    private static final Duration CONTAINER_POLL = Duration.ofSeconds(2);

    /// How often a health line is printed in the terminal oillamp was started from. Changes, such as
    /// a socket that stops answering, are reported immediately, not at the next health line.
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);
    /// How much longer than `timeouts.stop_seconds` `podman stop` itself may take.
    private static final Duration STOP_GRACE = Duration.ofSeconds(15);
    private static final Duration REMOVE_TIMEOUT = Duration.ofSeconds(30);
    /// How long the shutdown save may take. A save reads every file in the agent's home, and the
    /// first one also stores them all: a few hundred megabytes once a JDK is in there.
    private static final Duration SAVE_ALLOWANCE = Duration.ofMinutes(5);

    /// How a signal is described in the closing summary. The JVM runs the same shutdown hook for all
    /// three, so it cannot say which one it was.
    private static final String SIGNALLED = "Ctrl-C, this terminal closing, or a kill";

    private final Machine machine;
    private final Context context;
    private final HostFacts host;
    private final LampPhase.Prepared prepared;
    private final SandboxPhase.Running sandbox;
    private final SessionMachine rules;

    /// Every thread may add events here; only the event loop takes them.
    private final BlockingQueue<Timed> events = new LinkedBlockingQueue<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /// The session's state. Written only by the event loop, but read by four other threads: the
    /// container watcher, the control socket, the shutdown sequence and the JVM's shutdown hook.
    ///
    /// It is `volatile` so that those threads see the latest value. Without it, the shutdown
    /// hook could keep seeing an old state and wait its full time after the session had
    /// already ended.
    private volatile SessionState state;
    private volatile Instant sessionStarted;
    private volatile Optional<Relay> primary = Optional.empty();
    private volatile Optional<Relay> extras = Optional.empty();
    private volatile Optional<Control.Server> control = Optional.empty();
    private volatile Optional<Egress> egress = Optional.empty();
    /// The socket through which the agent reads and changes the schedule. Open only while
    /// `schedule.enabled` is on.
    private volatile Optional<Control.Server> scheduleDesk = Optional.empty();
    /// Every time the agent is woken, by a job or by `oillamp ask`.
    private final Runs runs;
    /// Everyone following the session with `oillamp follow`. Every event passes through it.
    private final Followers followers = new Followers();
    private volatile Optional<Machine.Window> terminal = Optional.empty();
    /// Whether the application that started this embedded session left it running, so that the
    /// end of oillamp's standard input no longer ends it.
    private volatile boolean leftRunning;
    /// What the last health check found, so that only a _change_ is reported.
    private boolean desktopAnswering = true;
    private boolean shellAnswering = true;
    private boolean briefed;
    /// Whether the last question to podman about the container went unanswered, so that a podman
    /// that stops answering is reported once, not every two seconds.
    private boolean podmanSilent;
    private final List<Machine.Window> viewers = new CopyOnWriteArrayList<>();

    /// An event and when it happened. [SessionMachine] gets the time from here rather than asking the clock.
    private record Timed(SessionEvent event, Instant at) {}

    Supervisor(Machine machine, Context context, HostFacts host,
               LampPhase.Prepared prepared, SandboxPhase.Running sandbox) {
        this.machine = machine;
        this.context = context.alsoTelling(followers);
        this.host = host;
        this.prepared = prepared;
        this.sandbox = sandbox;
        this.rules = new SessionMachine(new SessionMachine.Settings(
                prepared.config().timeouts().terminalConnect(),
                prepared.config().viewer().openOnStart() && context.options().openViewer(),
                prepared.config().viewer().viewOnly(),
                prepared.layout().root().toString(),
                !context.options().embedded() && context.options().openWindows()));
        this.sessionStarted = machine.now();
        this.state = new SessionState.Starting(sessionStarted);
        this.runs = new Runs(machine, this.context, prepared.layout(), prepared.config().schedule(), prepared.session());
    }

    /// Runs the session to its end and reports how it ended.
    ///
    /// Returns only once the container is stopped and everything the session created is gone,
    /// however it ended.
    public ExitStatus run() {
        sessionStarted = machine.now();
        state = new SessionState.Starting(sessionStarted);

        Result<Tuple<Problem>> opened = openTheSession();
        if (opened instanceof Result.Err<Tuple<Problem>>(Tuple<Problem> problems)) {
            // The session could not be opened, but the container is already running and must
            // not be left behind.
            context.report(problems);
            context.report(shutDown(new SessionState.ShutdownReason.StartupFailed(
                    problems.first())));
            followers.end();
            return ExitStatus.SESSION_FAILED;
        }
        context.report(((Result.Ok<Tuple<Problem>>) opened).value());

        Thread hook = new Thread(this::onSignal, "oillamp-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);
        watchTheSandbox();
        if (context.options().embedded()) watchTheApplication();
        post(new SessionEvent.ContainerReady(ReadyInfo.parse(sandbox.readyJson())));

        ExitStatus status = loop();
        followers.end();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The JVM is on its way out and running the hook itself; the sequence is idempotent.
        }
        return status;
    }

    // ─── the event loop ────────────────────────────────────────────────────────────────────

    /// Takes one event at a time, asks the rules what it means, and carries out the answer.
    ///
    /// When no event arrives within a second, a `Tick` is processed instead. That is how
    /// timeouts are noticed, without a separate timer thread.
    private ExitStatus loop() {
        ExitStatus exit = ExitStatus.SUCCESS;
        while (!state.isFinal()) {
            Timed timed;
            try {
                timed = events.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // The interrupt is handled here by turning it into an event, so the interrupt flag
                // is not set again. If it were, every later poll would throw at once and the loop
                // would spin instead of shutting down.
                post(new SessionEvent.Interrupted("interrupt"));
                continue;
            }
            Instant now = timed == null ? machine.now() : timed.at();
            SessionEvent event = timed == null ? new SessionEvent.Tick(now) : timed.event();

            SessionState before = state;
            SessionMachine.Transition transition = rules.step(state, event, now);
            state = transition.next();
            if (!before.getClass().equals(state.getClass())) announceState();
            // The briefing is printed once the shell is connected, when everything it says is true.
            if (state instanceof SessionState.Running)
                dispatchUserBriefing();

            for (SessionAction action : transition.actions()) {
                if (action instanceof SessionAction.Exit(ExitStatus status)) exit = status;
                else perform(action);
            }
        }
        return exit;
    }

    /// Puts an event on the queue, stamped with the time it happened. Safe from any thread.
    private void post(SessionEvent event) {
        events.add(new Timed(event, machine.now()));
    }

    private void announceState() {
        context.emit(new LampEvent.SessionStateChanged(new LampEvent.SessionStatus(
                state.name(), describeState(), uptime(), state.extraShells())));
    }

    private String describeState() {
        return switch (state) {
            case SessionState.Starting ignored -> "the sandbox is coming up";
            case SessionState.AwaitingTerminal ignored -> "waiting for your terminal window";
            case SessionState.Running running -> leftRunning
                    ? "running on its own; the application that started it left it running"
                    : context.options().embedded()
                    ? "running, for the application that started it"
                    : !context.options().openWindows()
                    ? "running, with no windows opened"
                    : running.shellWindowOpen()
                    ? "your shell is connected"
                    : "running; the shell window oillamp opened is closed";
            case SessionState.ShuttingDown shutting -> shutting.reason().describe();
            case SessionState.Stopped stopped -> stopped.reason().describe();
        };
    }

    private Duration uptime() { return Duration.between(sessionStarted, machine.now()); }

    // ─── what the machine asks for ─────────────────────────────────────────────────────────

    private void perform(SessionAction action) {
        switch (action) {
            case SessionAction.Announce announce -> context.emit(announce.event());
            case SessionAction.LaunchViewer viewer -> openViewer(viewer.viewOnly());
            case SessionAction.LaunchTerminal ignored -> openTerminal();
            case SessionAction.CloseShells ignored -> extras.ifPresent(Relay::close);
            case SessionAction.BeginShutdown shutdown -> beginShutdown(shutdown.reason());
            // Handled by the loop, which records the exit code. Listed so that a new kind of action
            // does not compile until it is handled here.
            case SessionAction.Exit ignored -> { }
        }
    }

    /// Opens the desktop viewer in a window of its own.
    ///
    /// Closing the viewer does not end the session: the user only stopped watching. A viewer that
    /// closes immediately with an error is reported as a warning (`OIL-VIEW-001`).
    private void openViewer(boolean viewOnly) {
        Tuple<String> argv = VncViewerUtil.argv(prepared.layout(), prepared.config(), viewOnly);
        if (host.vncViewer().isEmpty()) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchViewer(viewOnly),
                    ProblemCatalogUtil.viewerDiedImmediately(argv, 127,
                            "vncviewer is not installed — `sudo apt-get install -y tigervnc-viewer`")));
            return;
        }
        Machine.Window window = machine.launch(Machine.Command.of(argv).labelled("viewer"),
                                               Machine.Window.Stdio.DETACHED);
        if (window.failure().isPresent()) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchViewer(viewOnly),
                    ProblemCatalogUtil.viewerDiedImmediately(argv, 127, window.failure().get())));
            return;
        }
        viewers.add(window);
        context.emit(new LampEvent.WindowOpened("the desktop viewer", argv));
        watchBriefly(window, argv, exitCode -> new SessionEvent.ActionFailed(
                new SessionAction.LaunchViewer(viewOnly),
                ProblemCatalogUtil.viewerDiedImmediately(argv, exitCode, window.output())));
    }

    /// Opens the sandbox shell in a _new_ terminal window.
    ///
    /// Never the terminal oillamp was started from, which keeps printing what the session is
    /// doing.
    private void openTerminal() {
        Result<Tuple<String>> command = terminalCommand();
        if (command instanceof Result.Err<Tuple<String>>(Tuple<Problem> problems)) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchTerminal(),
                    problems.first()));
            return;
        }
        Tuple<String> argv = ((Result.Ok<Tuple<String>>) command).value();
        Machine.Window window = machine.launch(Machine.Command.of(argv).labelled("terminal"),
                                               Machine.Window.Stdio.DETACHED);
        if (window.failure().isPresent()) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchTerminal(),
                    ProblemCatalogUtil.terminalNotStarted(argv, window.failure().get(), window.output())));
            return;
        }
        terminal = Optional.of(window);
        context.emit(new LampEvent.WindowOpened("your shell, in a new terminal window", argv));
        watchBriefly(window, argv, exitCode -> new SessionEvent.ActionFailed(
                new SessionAction.LaunchTerminal(),
                ProblemCatalogUtil.terminalNotStarted(argv, "it exited with code " + exitCode, window.output())));
    }

    /// The terminal emulator's command line, with the ssh command inside it.
    private Result<Tuple<String>> terminalCommand() {
        Tuple<String> shell = SandboxSshUtil.clientArgv(prepared.layout(), SandboxSshUtil.SocketRole.PRIMARY);
        String title = TerminalEmulatorUtil.titleFor(prepared.layout().name());
        return switch (prepared.config().terminal()) {
            case LampConfig.Terminal.Custom custom ->
                    Result.ok(TerminalEmulatorUtil.render(custom.template(), title, shell));
            case LampConfig.Terminal.Profile profile ->
                    TerminalEmulatorUtil.choose(Optional.of(profile.id()), host.terminals(), host.session())
                             .map(chosen -> TerminalEmulatorUtil.render(chosen.template(), title, shell));
            case LampConfig.Terminal.Auto ignored ->
                    TerminalEmulatorUtil.choose(Optional.empty(), host.terminals(), host.session())
                             .map(chosen -> TerminalEmulatorUtil.render(chosen.template(), title, shell));
        };
    }

    /// Watches a window for the first few seconds only.
    ///
    /// A window still running after three seconds counts as opened. One that exits sooner with a
    /// non-zero code is reported, with its own output, so "the window flashed and disappeared" comes
    /// with an explanation.
    private void watchBriefly(Machine.Window window, Tuple<String> argv,
                              IntFunction<SessionEvent> onEarlyExit) {
        Thread.ofVirtual().name("oillamp-window-" + argv.first()).start(() -> {
            Instant deadline = Instant.now().plus(VIEWER_GRACE);
            while (Instant.now().isBefore(deadline)) {
                if (!window.isRunning()) {
                    int code = window.exitCode().orElse(0);
                    if (code != 0) post(onEarlyExit.apply(code));
                    return;
                }
                sleep(Duration.ofMillis(100));
            }
        });
    }

    // ─── opening and closing the session ───────────────────────────────────────────────────

    /// Binds everything a session needs before anything is allowed to connect.
    ///
    /// The sockets are bound before `session.json` is written, so that whenever
    /// `session.json` exists, the control socket is already answering.
    private Result<Tuple<Problem>> openTheSession() {
        LampLayout layout = prepared.layout();
        Result<Relay> primaryRelay = Relay.open(layout.primarySshSocket(), layout.agentSshSocket(),
                1, new PrimaryListener());
        if (primaryRelay instanceof Result.Err<Relay>(Tuple<Problem> problems)) return Result.err(problems);
        primary = Optional.of(((Result.Ok<Relay>) primaryRelay).value());

        Result<Relay> extraRelay = Relay.open(layout.extraSshSocket(), layout.agentSshSocket(),
                Integer.MAX_VALUE, new ExtraListener());
        if (extraRelay instanceof Result.Err<Relay>(Tuple<Problem> problems)) return Result.err(problems);
        extras = Optional.of(((Result.Ok<Relay>) extraRelay).value());

        Result<Control.Server> server = Control.Server.open(layout.controlSocket(), this::answer);
        if (server instanceof Result.Err<Control.Server>(Tuple<Problem> problems)) return Result.err(problems);
        control = Optional.of(((Result.Ok<Control.Server>) server).value());

        // The sandbox has no network of its own. Without the proxy, every outbound connection the
        // agent makes fails, so failing to start it fails the session.
        // The key is read here, on the host, and kept in memory only. It is never written into
        // the lamp or the sandbox.
        LampConfig.Model configured = prepared.config().model();
        Egress.Model model = new Egress.Model(configured.service(), configured.keyEnv(),
                machine.environmentVariable(configured.keyEnv()));
        Result<Egress> proxy = Egress.open(layout, prepared.config(), prepared.session(),
                model, new EgressListener());
        if (proxy instanceof Result.Err<Egress>(Tuple<Problem> problems)) return Result.err(problems);
        egress = Optional.of(((Result.Ok<Egress>) proxy).value());

        // The agent's way to the schedule, in the directory of sockets the host serves to the sandbox.
        if (prepared.config().schedule().enabled()) {
            Result<Control.Server> desk = Control.Server.open(layout.scheduleSocket(), runs::answerAgent);
            if (desk instanceof Result.Err<Control.Server>(Tuple<Problem> problems)) return Result.err(problems);
            scheduleDesk = Optional.of(((Result.Ok<Control.Server>) desk).value());
        }

        Tuple<Problem> warnings = Tuple.of(Problem.class);
        try {
            FilesystemUtil.writeFile(layout.sessionMeta(), sessionJson(), PosixMode.PRIVATE_FILE);
        } catch (IOException e) {
            // session.json is only informational (the lock decides), so this is a warning.
            warnings = warnings.add(ProblemCatalogUtil.internal("session.json", ProblemCatalogUtil.reason(e)));
        }
        return Result.ok(warnings);
    }

    /// The session's own record, read by `oillamp at` on a busy lamp and by `status`.
    private String sessionJson() {
        return JsonUtil.readable(JsonUtil.object()
                .put("session", prepared.session().toString())
                .put("agentId", prepared.layout().agentId().toString())
                .put("container", sandbox.container().toString())
                .put("image", sandbox.image().toString())
                .put("startedAt", sessionStarted.toString())
                .put("supervisorPid", ProcessHandle.current().pid())
                .put("controlSocket", prepared.layout().controlSocket().toString()));
    }

    private void beginShutdown(SessionState.ShutdownReason reason) {
        if (!shuttingDown.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("oillamp-shutdown").start(() -> {
            Tuple<Problem> problems = shutDown(reason);
            summarise(reason);
            post(new SessionEvent.ShutdownCompleted(problems));
        });
    }

    /// The shutdown sequence. Every step runs even if an earlier one failed, because they are
    /// independent cleanups: stopping at the first failure would leave the sockets and
    /// `session.json` behind as well.
    private Tuple<Problem> shutDown(SessionState.ShutdownReason reason) {
        Tuple<Problem> problems = Tuple.of(Problem.class);
        context.info("session", "shutting down — " + reason.describe());

        // 0. Stop the agent's run, if one is going, and save it, while the sandbox still runs.
        if (runs.busy()) context.info("run", "stopping the agent's run and saving what it did");
        scheduleDesk.ifPresent(Control.Server::close);
        runs.stop();

        // 1. Stop accepting shells, and drop the ones that are open.
        primary.ifPresent(Relay::close);
        extras.ifPresent(Relay::close);

        // 2. Ask the container to stop, so the entrypoint can finish the recording on SIGTERM.
        //    Both podman commands are shielded from Ctrl-C, which would otherwise reach them
        //    through the terminal's process group if the user pressed it again during shutdown.
        Duration stopTimeout = prepared.config().timeouts().stop();
        context.info("session", "stopping the sandbox — up to "
                + stopTimeout.toSeconds() + "s while the container finishes"
                + (prepared.config().recording().enabled() ? " and the recording is finalised" : ""));
        Machine.Outcome stopped = machine.run(Machine.Command
                .of("podman", "stop", "--time", String.valueOf(stopTimeout.toSeconds()),
                    sandbox.container().value())
                .withTimeout(stopTimeout.plus(STOP_GRACE)).labelled("podman stop").shieldedFromSignals());

        // 3. Remove it either way, so the next session does not find the name taken.
        Machine.Outcome removed = machine.run(Machine.Command
                .of("podman", "rm", "-f", sandbox.container().value())
                .withTimeout(REMOVE_TIMEOUT).labelled("podman rm").shieldedFromSignals());

        // What matters is whether the container is gone. `podman stop` failing and then
        // `podman rm -f` succeeding is a complete shutdown, not an error.
        if (!stopped.succeeded() && !removed.succeeded())
            problems = problems.add(ProblemCatalogUtil.containerNotRemoved(sandbox.container().value(),
                    stopped.errorOutput().strip(), removed.errorOutput().strip()));
        else if (!stopped.succeeded())
            context.info("session", "the container had to be forced — "
                    + describeFailure(stopped) + (prepared.config().recording().enabled()
                        ? "; the recording may end a moment early" : ""));

        // 4. The host-only sockets, and the windows that were opened onto the session.
        egress.ifPresent(Egress::close);
        control.ifPresent(Control.Server::close);
        for (Machine.Window viewer : viewers) viewer.close();
        terminal.ifPresent(Machine.Window::close);

        // 5. Delete session.json last, because while it exists other oillamp commands assume a
        //    session is running.
        try {
            FilesystemUtil.deleteIfPresent(prepared.layout().sessionMeta());
        } catch (IOException e) {
            problems = problems.add(ProblemCatalogUtil.internal("session.json", ProblemCatalogUtil.reason(e)));
        }
        problems = problems.addAll(recordLastSession());

        // 6. Save the lamp as the session left it. The container is gone, so nothing is writing.
        //    Here rather than after the session, because after Ctrl-C this sequence is the last
        //    thing that runs before the JVM exits.
        problems = problems.addAll(Commands.saveLamp(context, prepared.layout(),
                dev.lamp.LampEvent.SaveKind.SHUTDOWN, "", Optional.of(prepared.session()), machine.now()));
        return problems;
    }

    /// Why a cleanup command did not succeed, in a few words.
    ///
    /// A command killed by a signal prints nothing, so an empty error output is common and needs
    /// its own wording.
    private static String describeFailure(Machine.Outcome outcome) {
        return switch (outcome) {
            case Machine.Outcome.NotFound missing -> missing.executable() + " is not installed";
            case Machine.Outcome.TimedOut timedOut -> "it did not finish within "
                    + timedOut.after().toSeconds() + "s";
            case Machine.Outcome.Finished finished -> finished.standardError().isBlank()
                    ? "it exited " + finished.exitCode() + " without saying why"
                      + (finished.exitCode() > 128 ? " (killed by a signal)" : "")
                    : finished.standardError().strip();
        };
    }

    /// Updates `lastSessionAt` in `lamp.json`.
    private Tuple<Problem> recordLastSession() {
        Path metaFile = prepared.layout().lampMeta();
        Optional<String> json = FilesystemUtil.readString(metaFile);
        if (json.isEmpty()) return Tuple.of(Problem.class);
        LampState meta = LampDirectoryUtil.classify(prepared.layout().root(),
                FilesystemUtil.list(prepared.layout().root()), json);
        if (!(meta instanceof LampState.Existing existing)) return Tuple.of(Problem.class);
        try {
            FilesystemUtil.writeFile(metaFile,
                    LampDirectoryUtil.render(existing.meta().usedAt(machine.now())),
                    PosixMode.PUBLIC_FILE);
            return Tuple.of(Problem.class);
        } catch (IOException e) {
            return Tuple.of(Problem.class, ProblemCatalogUtil.internal("lamp.json", ProblemCatalogUtil.reason(e)));
        }
    }

    /// Prints the closing summary: why the session ended, how long it ran, and where the recording is.
    private void summarise(SessionState.ShutdownReason reason) {
        Tuple<String> lines = Tuple.of(String.class,
                "ended because   " + reason.describe(),
                "ran for         " + describe(uptime()),
                "session         " + prepared.session(),
                "sandbox         " + sandbox.container() + " (removed)");
        Path recording = prepared.layout().recording(prepared.session());
        if (prepared.config().recording().enabled() && FilesystemUtil.exists(recording))
            lines = lines.add("recording       " + recording);
        context.emit(new LampEvent.Summary("session " + prepared.session(), lines));
    }

    private static String describe(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return seconds < 60 ? seconds + "s"
             : seconds < 3600 ? seconds / 60 + "m " + seconds % 60 + "s"
             : seconds / 3600 + "h " + (seconds % 3600) / 60 + "m";
    }

    // ─── the producers ─────────────────────────────────────────────────────────────────────

    /// Ends an embedded session when the application that started it goes away, unless it left
    /// the session running first.
    ///
    /// The application holds oillamp's standard input open for as long as it wants the session.
    /// When it closes it, or dies, and the operating system closes it on its behalf, reading
    /// reaches the end, and the session shuts down as it would for `oillamp stop`.
    ///
    /// An application that writes the line [#LEAVE_RUNNING] before it closes leaves the session
    /// running: from then on it ends only as a session started from a terminal with
    /// `--no-windows` does, with `oillamp stop`. Anything else the application writes is ignored.
    /// The line arrives before the end of the input, so an application may write it and exit at
    /// once.
    private void watchTheApplication() {
        Thread.ofVirtual().name("oillamp-application-watch").start(() -> {
            try (var input = new BufferedReader(new InputStreamReader(
                    machine.standardInput(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (!line.strip().equals(LEAVE_RUNNING)) continue;
                    leftRunning = true;
                    context.info("session", "the application left the session running; it ends with `oillamp stop "
                            + prepared.layout().root() + "`");
                    announceState();
                    return;
                }
            } catch (IOException closed) {
                // A broken pipe means the same as a closed one: the application is gone.
            }
            post(new SessionEvent.StopRequested("the application that started it"));
        });
    }

    /// What an application writes on oillamp's standard input to leave its session running.
    static final String LEAVE_RUNNING = "leave-running";

    /// Checks every two seconds that the container is still running and that its sockets answer,
    /// and prints a health line every 30 seconds.
    ///
    /// Without this, a container that died during a session would leave the user typing into a
    /// terminal connected to nothing, with no explanation.
    private void watchTheSandbox() {
        Thread.ofVirtual().name("oillamp-sandbox-watch").start(() -> {
            // The first health line comes straight away, then one every 30 seconds.
            Instant nextHeartbeat = Instant.now();
            while (!shuttingDown.get() && !state.isFinal()) {
                sleep(CONTAINER_POLL);
                if (shuttingDown.get() || state.isFinal()) return;
                Optional<Boolean> running = containerIsRunning();
                if (running.isPresent() && !running.get()) {
                    post(new SessionEvent.ContainerExited(exitCodeOfContainer()));
                    return;
                }
                boolean due = !Instant.now().isBefore(nextHeartbeat);
                checkTheEndpoints(due);
                if (due) {
                    heartbeat();
                    nextHeartbeat = Instant.now().plus(HEARTBEAT);
                }
            }
        });
    }

    /// Connects to the desktop, shell and proxy sockets, and reports when one stops or starts
    /// answering.
    ///
    /// A desktop that dies during a session looks fine from the container's point of view, so
    /// the sockets themselves are checked. A failure does not end the session, because a session
    /// with a working shell is still usable, but the user is told.
    private void checkTheEndpoints(boolean reportEvenIfUnchanged) {
        boolean desktop = Relay.answers(prepared.layout().vncSocket());
        boolean shell = Relay.answers(prepared.layout().agentSshSocket());
        if (desktop != desktopAnswering)
            context.emit(desktop
                    ? new LampEvent.Ok("health", "the desktop is answering again")
                    : new LampEvent.Warning(ProblemCatalogUtil.sandboxEndpointDead("the desktop (VNC)",
                            prepared.layout().vncSocket(), sandbox.container().value(),
                            lastLinesOfTheSandboxLog())));
        if (shell != shellAnswering)
            context.emit(shell
                    ? new LampEvent.Ok("health", "the shell is answering again")
                    : new LampEvent.Warning(ProblemCatalogUtil.sandboxEndpointDead("the shell (SSH)",
                            prepared.layout().agentSshSocket(), sandbox.container().value(),
                            lastLinesOfTheSandboxLog())));
        desktopAnswering = desktop;
        shellAnswering = shell;
        // The proxy is the sandbox's only way out. If it stopped answering, the agent could not
        // fetch anything, and nothing else would look different.
        boolean network = Egress.answers(prepared.layout().proxySocket());
        if (reportEvenIfUnchanged && desktop && shell && state.isLive())
            context.info("health", network
                    ? "desktop, shell and network all still answering"
                    : "desktop and shell answering — the egress proxy is NOT");
    }

    /// The health line printed every 30 seconds in the terminal oillamp was started from.
    private void heartbeat() {
        if (!state.isLive()) return;
        context.info("session", "up " + describe(uptime())
                + " — " + sandbox.container()
                + ", " + prepared.config().display().size()
                + ", " + state.extraShells() + " extra shell"
                + (state.extraShells() == 1 ? "" : "s")
                + " (`oillamp status " + prepared.layout().root() + "` for more)");
    }

    private String lastLinesOfTheSandboxLog() {
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "logs", "--tail", "20", sandbox.container().value())
                .withTimeout(Duration.ofSeconds(20)).labelled("podman logs"));
        String text = (outcome.output() + outcome.errorOutput()).strip();
        return text.isEmpty() ? "(the container logged nothing)" : text;
    }

    /// The briefing printed once the shell is connected: the desktop, how to open more windows,
    /// the one directory the agent can see, the network policy, the recording, and the three ways
    /// to end the session.
    private void dispatchUserBriefing() {
        if (briefed) return;
        briefed = true;
        // The sandbox is up, so the agent can be woken from now on.
        runs.begin();
        LampLayout layout = prepared.layout();
        LampConfig config = prepared.config();
        context.emit(new LampEvent.SessionOpened(prepared.session().value(), SandboxSshUtil.commandArgv(layout),
                layout.vncSocket()));
        if (context.options().embedded()) {
            context.emit(new LampEvent.Summary("your session is up", Tuple.of(String.class,
                    "started by      an application, which ends it when it is done",
                    "the agent sees  " + layout.agentDir() + " and nothing else of this lamp",
                    "look inside     `oillamp shell " + layout.root() + "`, `oillamp view "
                            + layout.root() + "`",
                    "network log     " + layout.networkLog(prepared.session()),
                    "to finish early `oillamp stop " + layout.root() + "`")));
            return;
        }
        if (!context.options().openWindows()) {
            // Nothing opened, so these lines are the only way the user learns how to get in.
            context.emit(new LampEvent.Summary("your session is up", Tuple.of(String.class,
                    "windows        none opened (--no-windows); attach from any terminal:",
                    "shell          `oillamp shell " + layout.root() + "`",
                    // Said per machine: the ssh line run on this machine only fails, and says why
                    // in a way that does not point back here.
                    "desktop        on this machine: `oillamp view " + layout.root() + "`, or any VNC viewer",
                    "               given the socket: vncviewer " + layout.vncSocket(),
                    "               from another machine (needs an ssh server here), run there:",
                    "               ssh -N -L 5901:" + layout.vncSocket() + " <you>@<this machine>",
                    "               then point a VNC viewer there at localhost:5901",
                    "the agent sees " + layout.agentDir() + " and nothing else of this lamp",
                    "network log    " + layout.networkLog(prepared.session()),
                    scheduleLine(),
                    "this terminal  keeps reporting the sandbox's health until the session ends",
                    "to finish      press Ctrl-C here, close this terminal, "
                            + "or run `oillamp stop " + layout.root() + "`")));
            return;
        }
        Tuple<String> lines = Tuple.of(String.class,
                "desktop        " + config.display().size() + ", renderer " + prepared.gpu().renderer()
                        + (prepared.gpu() instanceof DesktopRendererUtil.Decision.Hardware ? " (hardware)" : " (software)"),
                // What is actually open: with `--no-viewer`, or if the viewer failed, there is none.
                (viewers.isEmpty()
                        ? "viewer         none — open one with `oillamp view " + layout.root() + "`"
                        : "viewer         open now — another with `oillamp view " + layout.root() + "`"),
                "shell          open now — more, at any time, with `oillamp shell " + layout.root() + "`",
                "the agent sees " + layout.agentDir() + " and nothing else of this lamp",
                // Described by what the agent can reach, not by how the policy is written.
                "network        " + (config.network().defaultDecision() == Decision.ALLOW
                        ? "the open web, through oillamp's proxy"
                        : "denied by default — only what the rules allow")
                        + " (" + config.network().rules().size() + " rule(s), "
                        + config.forwards().size() + " forward(s))",
                "               the host's own loopback and private ranges stay out of reach"
                        + (config.network().consoleDenied() ? "; denials are printed here" : ""),
                "network log    " + layout.networkLog(prepared.session()),
                scheduleLine());
        // Recording is off by default, so say whether it is on, and how to turn it on if not.
        lines = config.recording().enabled()
                ? lines.add("recording      " + layout.recording(prepared.session()))
                : lines.add("recording      off — set `recording.enabled = true` in "
                          + layout.config() + " to record this desktop");
        lines = lines.add("this terminal  keeps reporting the sandbox's health until the session ends");
        lines = lines.add("to finish      press Ctrl-C here, close this terminal, "
                        + "or run `oillamp stop " + layout.root() + "`");
        lines = lines.add("               closing the shell or viewer windows leaves the session running");
        context.emit(new LampEvent.Summary("your session is up", lines));
    }

    /// What the briefing says about the schedule: whether jobs wake the agent in this session.
    private String scheduleLine() {
        String lamp = prepared.layout().root().toString();
        if (!prepared.config().schedule().enabled())
            return "schedule       off — jobs do not run; `oillamp ask " + lamp + " \"…\"` still wakes the agent";
        int jobs = new ScheduleBook(prepared.layout()).read().map(schedule -> schedule.jobs().size()).orElseGet(problems -> 0);
        return "schedule       on — " + jobs + (jobs == 1 ? " job" : " jobs") + " may wake the agent while this "
             + "session runs; `oillamp schedule " + lamp + "` lists them";
    }

    /// Whether the container is running, or empty when podman could not say.
    ///
    /// Only a clear answer ends the session: podman saying the container is not running, or that
    /// it no longer exists. A podman that is slow or failing, for example while another lamp's
    /// image is being built, is reported as a warning, and the session carries on. Ending a
    /// working session because podman was busy for 15 seconds would lose the user's shell for
    /// nothing.
    private Optional<Boolean> containerIsRunning() {
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "container", "inspect", "--format", "{{.State.Running}}",
                    sandbox.container().value())
                .withTimeout(Duration.ofSeconds(15)).labelled("podman inspect"));
        Optional<Boolean> answer = switch (outcome) {
            case Machine.Outcome.Finished finished when finished.exitCode() == 0 ->
                    Optional.of(finished.standardOutput().strip().equals("true"));
            case Machine.Outcome.Finished finished
                    when finished.standardError().toLowerCase(Locale.ROOT).contains("no such container") ->
                    Optional.of(false);
            case Machine.Outcome ignored -> Optional.empty();
        };
        if (answer.isEmpty() && !podmanSilent)
            context.emit(new LampEvent.Warning(ProblemCatalogUtil.sandboxStateUnknown(
                    sandbox.container().value(), describeFailure(outcome))));
        podmanSilent = answer.isEmpty();
        return answer;
    }

    private int exitCodeOfContainer() {
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "container", "inspect", "--format", "{{.State.ExitCode}}",
                    sandbox.container().value())
                .withTimeout(Duration.ofSeconds(15)).labelled("podman inspect"));
        try {
            return Integer.parseInt(outcome.output().strip());
        } catch (NumberFormatException unknown) {
            return -1;
        }
    }

    /// The shell window oillamp opened. Its connecting is what makes the session count as up;
    /// its closing is reported and ends nothing.
    private final class PrimaryListener implements Relay.Listener {
        @Override public void connected()    { post(new SessionEvent.PrimaryConnected()); }
        @Override public void disconnected() { post(new SessionEvent.PrimaryDisconnected()); }
        @Override public void trouble(Problem problem) { context.emit(new LampEvent.Warning(problem)); }
    }

    /// `oillamp shell`. Closing one of these ends nothing.
    private final class ExtraListener implements Relay.Listener {
        @Override public void connected()    { post(new SessionEvent.ShellConnected()); }
        @Override public void disconnected() { post(new SessionEvent.ShellDisconnected()); }
        @Override public void trouble(Problem problem) { context.emit(new LampEvent.Warning(problem)); }
    }

    /// Reports what the egress proxy has to say. With `network.console_denied` on (the
    /// default), every denied connection is printed with the rule that denied it, so the user knows
    /// which rule to change.
    private final class EgressListener implements Egress.Listener {
        @Override public void denied(Egress.Journey journey) {
            if (prepared.config().network().consoleDenied())
                context.info("network", "denied " + journey.describe());
        }
        @Override public void trouble(Problem problem) { context.emit(new LampEvent.Warning(problem)); }
    }

    /// Runs on Ctrl-C, SIGTERM or SIGHUP: asks for a clean shutdown and waits for it.
    ///
    /// SIGHUP is what the terminal oillamp was started from sends when it is closed. That terminal
    /// is where the session reports what it is doing, so closing it ends the session.
    ///
    /// The JVM exits as soon as this returns, so it waits as long as the shutdown sequence may
    /// take: `podman stop` gets `timeouts.stop_seconds` and 15 seconds more, `podman rm` 30.
    /// Returning earlier would leave the container running, or cut a recording short.
    private void onSignal() {
        post(new SessionEvent.Interrupted(SIGNALLED));
        Instant deadline = Instant.now().plus(longestShutdown());
        while (!state.isFinal() && Instant.now().isBefore(deadline)) sleep(Duration.ofMillis(100));
        // Last resort: the event loop never began the sequence, so run it here. Once begun, it is
        // not started a second time: its `podman rm -f` would kill the container in the middle
        // of the first one's `podman stop`, while the recording is being finished.
        if (!state.isFinal() && shuttingDown.compareAndSet(false, true))
            shutDown(new SessionState.ShutdownReason.UserInterrupt(SIGNALLED, false));
    }

    /// The longest the shutdown sequence can take, with a margin for everything besides podman.
    ///
    /// The save at the end has [#SAVE_ALLOWANCE]. A save cut short by the JVM exiting leaves the
    /// history as it was, so a limit here costs a snapshot, never the history.
    private Duration longestShutdown() {
        return prepared.config().timeouts().stop().plus(STOP_GRACE).plus(REMOVE_TIMEOUT)
                       .plus(SAVE_ALLOWANCE).plus(Runs.WIND_DOWN).plusSeconds(15);
    }

    // ─── the control socket ────────────────────────────────────────────────────────────────

    /// Answers one request from another oillamp process, such as `oillamp stop` or
    /// `oillamp status`.
    ///
    /// The request arrived on the control socket, `run/control.sock` in the runtime directory.
    /// [Control.Server] reads it, calls this method on that connection's own thread, and writes the
    /// returned reply back on the same connection. This method does no socket work itself.
    ///
    /// Because it runs outside the event loop, it never changes the state. It reads it (the field
    /// is volatile) and posts events: `stop` posts [SessionEvent.StopRequested] and replies at once,
    /// and the event loop shuts the session down as it would for Ctrl-C. `view` opens a viewer
    /// directly. `shell` opens nothing: it returns the ssh command, and the asking process runs it
    /// in its own terminal.
    private Control.Reply answer(Control.Request request) {
        return switch (request.op()) {
            case "status" -> Control.Reply.ok()
                    .with("state", state.name())
                    .with("detail", describeState())
                    .with("lamp", prepared.layout().root().toString())
                    .with("session", prepared.session().value())
                    .with("container", sandbox.container().value())
                    .with("renderer", prepared.gpu().renderer())
                    .with("desktop", prepared.config().display().size())
                    .with("uptime", describe(uptime()))
                    .with("shells", String.valueOf(state.extraShells()))
                    .with("agent", runs.describe())
                    .with("agent_status", runs.status().toJson())
                    .with("schedule", prepared.config().schedule().enabled() ? "on" : "off")
                    .with("viewer", prepared.layout().vncSocket().toString());
            case "stop" -> {
                post(new SessionEvent.StopRequested("oillamp stop"));
                yield Control.Reply.ok().with("state", "shutting-down");
            }
            case "view" -> {
                if (!state.isLive()) yield Control.Reply.failed("this session is already ending");
                openViewer(request.flag("view_only") || prepared.config().viewer().viewOnly());
                yield Control.Reply.ok();
            }
            // The extra shell runs in the terminal where the user typed `oillamp shell`, so the
            // supervisor returns the ssh command instead of running it.
            case "shell" -> state.isLive()
                    ? Control.Reply.ok().withArgv(
                            SandboxSshUtil.clientArgv(prepared.layout(), SandboxSshUtil.SocketRole.EXTRA))
                    : Control.Reply.failed("this session is already ending");
            // Answered once the run has ended, which is what `oillamp ask` waits for.
            case "ask" -> {
                if (!state.isLive()) yield Control.Reply.failed("this session is already ending");
                Optional<Harness.Target> where;
                try {
                    where = request.arguments().get("file").map(file -> new Harness.Target(file,
                            request.arguments().get("move_to").filter(entry -> !entry.isBlank())));
                } catch (IllegalArgumentException wrong) {
                    yield Control.Reply.failed(ProblemCatalogUtil.reason(wrong));
                }
                Result<Runs.Asked> asked = runs.ask(request.arguments().get("prompt").orElse(""),
                        request.arguments().get("conversation"), where);
                if (!(asked instanceof Result.Ok<Runs.Asked>(Runs.Asked taken, var _)))
                    yield Control.Reply.failed(asked.problems().first().whatHappened());
                // Answered at once, for an application that follows the run on the session's events.
                if (request.flag("no_wait")) yield Control.Reply.ok().with("run", taken.run().id());
                try {
                    LampEvent.RunFinished finished = taken.done().get();
                    String answer = finished.answer();
                    Control.Reply reply = Control.Reply.ok()
                            .with("run", finished.run().id())
                            .with("outcome", finished.outcome().name())
                            .with("answer", answer.length() <= ANSWER_SENT ? answer : answer.substring(0, ANSWER_SENT) + "\n[…]")
                            .with("seconds", String.valueOf(finished.took().toSeconds()));
                    Control.Reply withConversation = finished.conversation().map(id -> reply.with("conversation", id)).orElse(reply);
                    yield finished.snapshot().map(snapshot -> withConversation.with("snapshot", snapshot.id())).orElse(withConversation);
                } catch (ExecutionException failed) {
                    yield Control.Reply.failed(ProblemCatalogUtil.reason(failed.getCause() == null ? failed : failed.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    yield Control.Reply.failed("the session is ending");
                }
            }
            case "cancel" -> {
                Result<String> cancelled = runs.cancel(request.arguments().get("run").filter(run -> !run.isBlank()));
                yield cancelled instanceof Result.Ok<String>(String run, var _)
                        ? Control.Reply.ok().with("run", run)
                        : Control.Reply.failed(cancelled.problems().first().whatHappened());
            }
            // Answered with every event from now on, after what a newcomer needs to catch up.
            case "follow" -> Control.Reply.ok().withStream(out -> {
                try (Followers.Feed feed = followers.follow()) {
                    for (Optional<LampEvent> event = feed.next(); event.isPresent(); event = feed.next())
                        out.write(event.get().toJson());
                }
            });
            case "schedule-changed" -> {
                runs.scheduleChanged();
                yield Control.Reply.ok();
            }
            default -> Control.Reply.failed("unknown request: " + request.op());
        };
    }

    /// The most of an agent's answer sent back to `oillamp ask`. The whole of it is in the event
    /// the session reports, and a control reply is one line of limited length.
    private static final int ANSWER_SENT = 64 * 1024;

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
