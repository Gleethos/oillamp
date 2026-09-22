package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import sprouts.Tuple;

/**
 * Phase D: the session itself — spec §26.5, §10.6, §10.7.
 *
 * <p>Everything up to here was setup that ends. This does not: it opens the two windows, holds
 * the relays the shells come through, answers the control socket, and waits. The waiting is the
 * feature. A sandbox with a desktop and a shell in it needs something that notices when the user
 * is finished and takes it all down again — otherwise a closed terminal leaves a container, a
 * recording and a lock behind, and the next {@code oillamp at} on that lamp refuses to start.
 *
 * <p>One thread owns the state and nothing else may touch it (§29). Everything that can happen —
 * a shell connecting, the container dying, Ctrl-C, {@code oillamp stop}, a second passing —
 * becomes a {@link SessionEvent} on one queue, and the only code that decides what any of them
 * means is {@link SessionMachine}, which is pure. What is left here is the part that genuinely
 * cannot be pure: starting processes, moving bytes, and stopping the container.
 *
 * <p>One deliberate difference from §25.1: the closing summary is emitted here rather than
 * returned by the machine as an action. The machine knows the <em>reason</em> a session ended,
 * which is what the exit code needs, but the duration, the recording and what the shutdown
 * actually managed to clean up are facts only this class has.
 *
 * <p>Deliberately <b>package-private</b>: Phase D of §10.5, wired together. On the effects
 * allowlist — it is the most effectful class in oillamp, and none of its decisions are its own.
 */
final class Supervisor {

    /** How long a viewer has to stay open before it counts as having opened at all (§10.6). */
    private static final Duration VIEWER_GRACE = Duration.ofSeconds(3);

    /** How often the container is checked. A dead sandbox should be noticed in seconds, not minutes. */
    private static final Duration CONTAINER_POLL = Duration.ofSeconds(2);

    /**
     * How often the session says out loud that it is still healthy.
     *
     * <p>Often enough that the terminal oillamp was started from stays a live account of the
     * session rather than a screen that stopped updating, rare enough that it does not bury the
     * things that matter. Anything that actually changes — a socket that stops answering, a shell
     * connecting — is reported when it happens, not at the next heartbeat.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);

    private final Machine machine;
    private final Context context;
    private final HostFacts host;
    private final LampPhase.Prepared prepared;
    private final SandboxPhase.Running sandbox;
    private final SessionMachine rules;

    /** Every producer writes here; exactly one thread reads. That is the whole concurrency story. */
    private final BlockingQueue<Timed> events = new LinkedBlockingQueue<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /**
     * The session's state. Written only by the event loop, but read by four other threads — the
     * container watcher, the control socket, the shutdown sequence and the JVM's shutdown hook.
     *
     * <p>{@code volatile} is what entitles those reads to see it. Without it the memory model
     * allows the shutdown hook to keep watching a stale copy until its own thirty-second patience
     * runs out — long after the session it is waiting for has finished. That failure would be
     * unusually hard to notice, because everything the hook is responsible for would have worked:
     * the sandbox stopped, the container removed, and only the process left hanging about.
     */
    private volatile SessionState state;
    private volatile Instant sessionStarted;
    private volatile Optional<Relay> primary = Optional.empty();
    private volatile Optional<Relay> extras = Optional.empty();
    private volatile Optional<Control.Server> control = Optional.empty();
    private volatile Optional<Machine.Window> terminal = Optional.empty();
    /** What the last health check found, so that only a <em>change</em> is reported. */
    private boolean desktopAnswering = true;
    private boolean shellAnswering = true;
    private boolean briefed;
    private final java.util.List<Machine.Window> viewers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** An event and the moment it happened — the machine is given the clock rather than asking for it. */
    private record Timed(SessionEvent event, Instant at) {}

    Supervisor(Machine machine, Context context, HostFacts host,
               LampPhase.Prepared prepared, SandboxPhase.Running sandbox) {
        this.machine = machine;
        this.context = context;
        this.host = host;
        this.prepared = prepared;
        this.sandbox = sandbox;
        this.rules = new SessionMachine(new SessionMachine.Settings(
                prepared.config().timeouts().terminalConnect(),
                prepared.config().viewer().openOnStart() && context.options().openViewer(),
                prepared.config().viewer().viewOnly()));
        this.sessionStarted = machine.now();
        this.state = new SessionState.Starting(sessionStarted);
    }

    /**
     * Runs the session to its end and reports how it ended.
     *
     * <p>Returns only once the container is stopped and everything this session created is gone,
     * whichever way it ended — which is what makes {@code oillamp at} safe to run from a script.
     */
    public ExitStatus run() {
        sessionStarted = machine.now();
        state = new SessionState.Starting(sessionStarted);

        Result<Tuple<Problem>> opened = openTheSession();
        if (opened instanceof Result.Err<Tuple<Problem>> failure) {
            // Nothing is listening yet, so there is no session to shut down in the ordinary way —
            // but the container is already running and must not be left behind.
            context.report(failure.problems());
            context.report(shutDown(new SessionState.ShutdownReason.StartupFailed(
                    failure.problems().first())));
            return ExitStatus.SESSION_FAILED;
        }
        context.report(((Result.Ok<Tuple<Problem>>) opened).value());

        Thread hook = new Thread(this::onSignal, "oillamp-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);
        watchTheSandbox();
        post(new SessionEvent.ContainerReady(ReadyInfo.parse(sandbox.readyJson())));

        ExitStatus status = loop();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The JVM is on its way out and running the hook itself; the sequence is idempotent.
        }
        return status;
    }

    // ─── the event loop ────────────────────────────────────────────────────────────────────

    /**
     * Takes one event at a time, asks the rules what it means, and carries out the answer.
     *
     * <p>The poll timeout is the ticker of §26.5: an idle session produces one {@code Tick} a
     * second, which is the only way a timeout can ever be noticed, and no extra thread is needed
     * to produce it.
     */
    private ExitStatus loop() {
        ExitStatus exit = ExitStatus.SUCCESS;
        while (!state.isFinal()) {
            Timed timed;
            try {
                timed = events.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Deliberately not re-asserting the interrupt. It is being handled, not passed
                // on: it becomes an event that ends the session cleanly, and a loop that kept
                // the flag set would find every later poll throwing before it could take the
                // event it just posted — spinning instead of shutting down.
                post(new SessionEvent.Interrupted("interrupt"));
                continue;
            }
            Instant now = timed == null ? machine.now() : timed.at();
            SessionEvent event = timed == null ? new SessionEvent.Tick(now) : timed.event();

            SessionState before = state;
            SessionMachine.Transition transition = rules.step(state, event, now);
            state = transition.next();
            if (!before.getClass().equals(state.getClass())) announceState();
            // Said once the shell is actually connected, because that is the moment everything in
            // it is true: both windows are open and the session is the user's to work in.
            if (state instanceof SessionState.Running) brief();

            for (SessionAction action : transition.actions()) {
                if (action instanceof SessionAction.Exit leaving) exit = leaving.status();
                else perform(action);
            }
        }
        return exit;
    }

    /** Puts an event on the queue, stamped with the time it happened. Safe from any thread. */
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
            case SessionState.Running ignored -> "your shell is connected";
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
            // Handled by the loop, which owns the exit code; listed so that adding an action is
            // a compile error here rather than a silently ignored instruction.
            case SessionAction.Exit ignored -> { }
        }
    }

    /**
     * Opens the desktop viewer in a window of its own.
     *
     * <p>Its lifetime is deliberately independent of the session's (§10.6): a user who closes the
     * viewer wanted to stop watching, not to stop working. The one case worth reporting is a
     * viewer that vanishes immediately, because that is indistinguishable from one that never
     * opened, and the user would be left looking at a desktop they cannot see.
     */
    private void openViewer(boolean viewOnly) {
        Tuple<String> argv = Viewers.argv(prepared.layout(), prepared.config(), viewOnly);
        if (host.vncViewer().isEmpty()) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchViewer(viewOnly),
                    Problems.viewerDiedImmediately(argv, 127,
                            "vncviewer is not installed — `sudo apt-get install -y tigervnc-viewer`")));
            return;
        }
        Machine.Window window = machine.launch(Machine.Command.of(argv).labelled("viewer"),
                                               Machine.Window.Stdio.DETACHED);
        if (window.failure().isPresent()) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchViewer(viewOnly),
                    Problems.viewerDiedImmediately(argv, 127, window.failure().get())));
            return;
        }
        viewers.add(window);
        context.emit(new LampEvent.WindowOpened("the desktop viewer", argv));
        watchBriefly(window, argv, exitCode -> new SessionEvent.ActionFailed(
                new SessionAction.LaunchViewer(viewOnly),
                Problems.viewerDiedImmediately(argv, exitCode, window.output())));
    }

    /**
     * Opens the sandbox shell in a <em>new</em> terminal window.
     *
     * <p>A new one, never this one. The terminal oillamp was started from goes on printing what
     * the session is doing, and that running account is where the user looks when something needs
     * explaining — taking it over with a shell would hide exactly the thing they would want to
     * read.
     */
    private void openTerminal() {
        Result<Tuple<String>> command = terminalCommand();
        if (command instanceof Result.Err<Tuple<String>> failure) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchTerminal(),
                    failure.problems().first()));
            return;
        }
        Tuple<String> argv = ((Result.Ok<Tuple<String>>) command).value();
        Machine.Window window = machine.launch(Machine.Command.of(argv).labelled("terminal"),
                                               Machine.Window.Stdio.DETACHED);
        if (window.failure().isPresent()) {
            post(new SessionEvent.ActionFailed(new SessionAction.LaunchTerminal(),
                    Problems.terminalNotStarted(argv, window.failure().get(), window.output())));
            return;
        }
        terminal = Optional.of(window);
        context.emit(new LampEvent.WindowOpened("your shell, in a new terminal window", argv));
        watchBriefly(window, argv, exitCode -> new SessionEvent.ActionFailed(
                new SessionAction.LaunchTerminal(),
                Problems.terminalNotStarted(argv, "it exited with code " + exitCode, window.output())));
    }

    /** The terminal emulator from §17.4, wrapped around the ssh command of §17.1. */
    private Result<Tuple<String>> terminalCommand() {
        Tuple<String> shell = Ssh.clientArgv(prepared.layout(), Ssh.SocketRole.PRIMARY);
        String title = Terminals.titleFor(prepared.layout().name());
        return switch (prepared.config().terminal()) {
            case LampConfig.Terminal.Custom custom ->
                    Result.ok(Terminals.render(custom.template(), title, shell));
            case LampConfig.Terminal.Profile profile ->
                    Terminals.choose(Optional.of(profile.id()), host.terminals(), host.session())
                             .map(chosen -> Terminals.render(chosen.template(), title, shell));
            case LampConfig.Terminal.Auto ignored ->
                    Terminals.choose(Optional.empty(), host.terminals(), host.session())
                             .map(chosen -> Terminals.render(chosen.template(), title, shell));
        };
    }

    /**
     * Watches a window for the first few seconds only.
     *
     * <p>A window that is still there after three seconds has opened as far as oillamp is
     * concerned; what happens to it afterwards is the user's business. This is what turns "the
     * viewer flashed and disappeared" into a reported problem with the viewer's own output
     * attached, instead of a desktop the user cannot see and no explanation anywhere.
     */
    private void watchBriefly(Machine.Window window, Tuple<String> argv,
                              java.util.function.IntFunction<SessionEvent> onEarlyExit) {
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

    /**
     * Binds everything a session needs before anything is allowed to connect.
     *
     * <p>Order matters: the relays and the control socket come first, then {@code session.json},
     * so that a {@code session.json} on disk always means a session another process can actually
     * reach. Written the other way round, {@code oillamp status} would have a window in which it
     * found a session and then could not talk to it.
     */
    private Result<Tuple<Problem>> openTheSession() {
        LampLayout layout = prepared.layout();
        Result<Relay> primaryRelay = Relay.open(layout.primarySshSocket(), layout.agentSshSocket(),
                1, new PrimaryListener());
        if (primaryRelay instanceof Result.Err<Relay> failure) return Result.err(failure.problems());
        primary = Optional.of(((Result.Ok<Relay>) primaryRelay).value());

        Result<Relay> extraRelay = Relay.open(layout.extraSshSocket(), layout.agentSshSocket(),
                Integer.MAX_VALUE, new ExtraListener());
        if (extraRelay instanceof Result.Err<Relay> failure) return Result.err(failure.problems());
        extras = Optional.of(((Result.Ok<Relay>) extraRelay).value());

        Result<Control.Server> server = Control.Server.open(layout.controlSocket(), this::answer);
        if (server instanceof Result.Err<Control.Server> failure) return Result.err(failure.problems());
        control = Optional.of(((Result.Ok<Control.Server>) server).value());

        Tuple<Problem> warnings = Tuple.of(Problem.class);
        try {
            Filesystem.writeFile(layout.sessionMeta(), sessionJson(), PosixMode.PRIVATE_FILE);
        } catch (java.io.IOException e) {
            // Informational only — the lock is the truth (§10.4). Worth saying, not worth failing.
            warnings = warnings.add(Problems.internal("session.json", Problems.reason(e)));
        }
        return Result.ok(warnings);
    }

    /** The session's own record, read by {@code oillamp at} on a busy lamp and by {@code status}. */
    private String sessionJson() {
        return "{\n"
             + "  \"session\": \"" + prepared.session() + "\",\n"
             + "  \"agentId\": \"" + prepared.layout().agentId() + "\",\n"
             + "  \"container\": \"" + sandbox.container() + "\",\n"
             + "  \"image\": \"" + sandbox.image() + "\",\n"
             + "  \"startedAt\": \"" + sessionStarted + "\",\n"
             + "  \"supervisorPid\": " + ProcessHandle.current().pid() + ",\n"
             + "  \"controlSocket\": \"" + prepared.layout().controlSocket() + "\"\n"
             + "}\n";
    }

    private void beginShutdown(SessionState.ShutdownReason reason) {
        if (!shuttingDown.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("oillamp-shutdown").start(() -> {
            Tuple<Problem> problems = shutDown(reason);
            summarise(reason);
            post(new SessionEvent.ShutdownCompleted(problems));
        });
    }

    /**
     * The shutdown sequence of §10.7, in order — and every step runs even if an earlier one
     * failed.
     *
     * <p>That is the rule that matters here. The steps are independent cleanups, and giving up
     * at the first failure is how a session that could not stop its container also leaves its
     * sockets, its {@code session.json} and its lock behind, turning one problem into four.
     */
    private Tuple<Problem> shutDown(SessionState.ShutdownReason reason) {
        Tuple<Problem> problems = Tuple.of(Problem.class);
        context.info("session", "shutting down — " + reason.describe());

        // 1. Stop accepting shells, and drop the ones that are open.
        primary.ifPresent(Relay::close);
        extras.ifPresent(Relay::close);

        // 2. Ask the container to stop, so the entrypoint can finalise the recording on SIGTERM.
        Duration stopTimeout = prepared.config().timeouts().stop();
        Machine.Outcome stopped = machine.run(Machine.Command
                .of("podman", "stop", "--time", String.valueOf(stopTimeout.toSeconds()),
                    sandbox.container().value())
                .withTimeout(stopTimeout.plusSeconds(15)).labelled("podman stop"));
        if (!stopped.succeeded())
            problems = problems.add(Problems.internal("podman stop",
                    "the sandbox did not stop cleanly: " + stopped.errorOutput().strip()));

        // 3. Remove it either way: a container left behind makes the next session fail on a name
        //    clash, which says nothing about what actually went wrong here.
        Machine.Outcome removed = machine.run(Machine.Command
                .of("podman", "rm", "-f", sandbox.container().value())
                .withTimeout(Duration.ofSeconds(30)).labelled("podman rm"));
        if (!removed.succeeded())
            problems = problems.add(Problems.internal("podman rm",
                    "the sandbox container could not be removed: " + removed.errorOutput().strip()));

        // 4. The host-only sockets, and the windows that were opened onto the session.
        control.ifPresent(Control.Server::close);
        for (Machine.Window viewer : viewers) viewer.close();
        terminal.ifPresent(Machine.Window::close);

        // 5. session.json goes last of the files: while it exists, another process may believe
        //    there is a session here to talk to.
        try {
            Filesystem.deleteIfPresent(prepared.layout().sessionMeta());
        } catch (java.io.IOException e) {
            problems = problems.add(Problems.internal("session.json", Problems.reason(e)));
        }
        problems = problems.addAll(recordLastSession());
        return problems;
    }

    /** {@code lamp.json.lastSessionAt} — the one thing a session leaves in the lamp's identity. */
    private Tuple<Problem> recordLastSession() {
        Path metaFile = prepared.layout().lampMeta();
        Optional<String> json = Filesystem.readString(metaFile);
        if (json.isEmpty()) return Tuple.of(Problem.class);
        LampState meta = LampClassifier.classify(prepared.layout().root(),
                Filesystem.list(prepared.layout().root()), json);
        if (!(meta instanceof LampState.Existing existing)) return Tuple.of(Problem.class);
        try {
            Filesystem.writeFile(metaFile,
                    LampClassifier.render(existing.meta().usedAt(machine.now())),
                    PosixMode.PUBLIC_FILE);
            return Tuple.of(Problem.class);
        } catch (java.io.IOException e) {
            return Tuple.of(Problem.class, Problems.internal("lamp.json", Problems.reason(e)));
        }
    }

    /** Step 6 of §10.7: what the session came to, in the terms the user was working in. */
    private void summarise(SessionState.ShutdownReason reason) {
        Tuple<String> lines = Tuple.of(String.class,
                "ended because   " + reason.describe(),
                "ran for         " + describe(uptime()),
                "session         " + prepared.session(),
                "sandbox         " + sandbox.container() + " (removed)");
        Path recording = prepared.layout().recording(prepared.session());
        if (prepared.config().recording().enabled() && Filesystem.exists(recording))
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

    /**
     * Notices a container that went away.
     *
     * <p>The gap M3 left open: until now the sandbox was checked once, during startup, and a
     * container that died an hour later left the user typing into a terminal whose other end was
     * gone. Polling is not elegant, but it is the only check that crosses the user-namespace
     * boundary without giving the container a way to talk back.
     */
    private void watchTheSandbox() {
        Thread.ofVirtual().name("oillamp-sandbox-watch").start(() -> {
            // The first one comes as soon as there is something to report, so that a user who
            // just watched two windows open learns straight away that the terminal they started
            // from is still watching. After that, every half minute.
            Instant nextHeartbeat = Instant.now();
            while (!shuttingDown.get() && !state.isFinal()) {
                sleep(CONTAINER_POLL);
                if (shuttingDown.get() || state.isFinal()) return;
                if (!containerIsRunning()) {
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

    /**
     * Opens both of the sandbox's sockets, and says so when the answer changes.
     *
     * <p>M3 checked these once, at startup, which left the case the user actually hit only half
     * covered: a desktop that dies <em>during</em> a session looked, to a container check, exactly
     * like one that is fine. Neither failure ends the session — a desktop with a working shell is
     * degraded, not over — but the user learns it from oillamp rather than from a viewer that
     * will not connect.
     */
    private void checkTheEndpoints(boolean reportEvenIfUnchanged) {
        boolean desktop = Relay.answers(prepared.layout().vncSocket());
        boolean shell = Relay.answers(prepared.layout().agentSshSocket());
        if (desktop != desktopAnswering)
            context.emit(desktop
                    ? new LampEvent.Ok("health", "the desktop is answering again")
                    : new LampEvent.Warning(Problems.sandboxEndpointDead("the desktop (VNC)",
                            prepared.layout().vncSocket(), sandbox.container().value(),
                            lastLinesOfTheSandboxLog())));
        if (shell != shellAnswering)
            context.emit(shell
                    ? new LampEvent.Ok("health", "the shell is answering again")
                    : new LampEvent.Warning(Problems.sandboxEndpointDead("the shell (SSH)",
                            prepared.layout().agentSshSocket(), sandbox.container().value(),
                            lastLinesOfTheSandboxLog())));
        desktopAnswering = desktop;
        shellAnswering = shell;
        if (reportEvenIfUnchanged && desktop && shell && state.isLive())
            context.info("health", "desktop and shell both still answering");
    }

    /**
     * One line, every half minute, in the terminal oillamp was started from.
     *
     * <p>That terminal is not taken over by the session and it is not left frozen either: it goes
     * on being the place where the state of the sandbox is written down, which is where the user
     * looks when they want to know what is happening behind the two windows.
     */
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

    /**
     * Everything the user needs to know while the session runs, said once, when it is true.
     *
     * <p>Two windows have just appeared on their desktop and a container is running that they
     * cannot see into. This is the answer to "what is going on, and what can I do about it" —
     * including the part that is easy to miss, that the agent can only see one directory of the
     * lamp, and the part that is easy to forget, which of the three ways of ending the session
     * they have.
     */
    private void brief() {
        if (briefed) return;
        briefed = true;
        LampLayout layout = prepared.layout();
        LampConfig config = prepared.config();
        Tuple<String> lines = Tuple.of(String.class,
                "desktop        " + config.display().size() + ", renderer " + prepared.gpu().renderer()
                        + (prepared.gpu() instanceof Gpu.Decision.Hardware ? " (hardware)" : " (software)"),
                // What is actually on screen, not what was asked for: `--no-viewer` and a viewer
                // that refused to start both end here, and telling the user a window is open
                // when it is not would send them looking for it.
                (viewers.isEmpty()
                        ? "viewer         none — open one with `oillamp view " + layout.root() + "`"
                        : "viewer         open now — another with `oillamp view " + layout.root() + "`"),
                "shell          open now — extra shells with `oillamp shell " + layout.root() + "`",
                "the agent sees " + layout.agentDir() + " and nothing else of this lamp",
                "network        " + config.network().defaultDecision().configName() + " by default, "
                        + config.network().rules().size() + " rule(s), "
                        + config.forwards().size() + " forward(s)");
        if (config.recording().enabled())
            lines = lines.add("recording      " + layout.recording(prepared.session()));
        lines = lines.add("this terminal  keeps reporting the sandbox's health until the session ends");
        lines = lines.add("to finish      close the shell window, press Ctrl-C here, "
                        + "or run `oillamp stop " + layout.root() + "`");
        context.emit(new LampEvent.Summary("your session is up", lines));
    }

    private boolean containerIsRunning() {
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "container", "inspect", "--format", "{{.State.Running}}",
                    sandbox.container().value())
                .withTimeout(Duration.ofSeconds(15)).labelled("podman inspect"));
        return outcome.succeeded() && outcome.output().strip().equals("true");
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

    /** The terminal window oillamp opened. Its connection is the session (D-09). */
    private final class PrimaryListener implements Relay.Listener {
        @Override public void connected()    { post(new SessionEvent.PrimaryConnected()); }
        @Override public void disconnected() { post(new SessionEvent.PrimaryDisconnected()); }
        @Override public void trouble(Problem problem) { context.emit(new LampEvent.Warning(problem)); }
    }

    /** {@code oillamp shell}. Closing one of these ends nothing. */
    private final class ExtraListener implements Relay.Listener {
        @Override public void connected()    { post(new SessionEvent.ShellConnected()); }
        @Override public void disconnected() { post(new SessionEvent.ShellDisconnected()); }
        @Override public void trouble(Problem problem) { context.emit(new LampEvent.Warning(problem)); }
    }

    /** Ctrl-C, SIGTERM or SIGHUP: ask for a clean end, and give it time to happen (§26.5). */
    private void onSignal() {
        post(new SessionEvent.Interrupted("SIGINT/SIGTERM"));
        Instant deadline = Instant.now().plusSeconds(30);
        while (!state.isFinal() && Instant.now().isBefore(deadline)) sleep(Duration.ofMillis(100));
        // Last resort: the loop did not get there, so run the sequence directly. It is idempotent.
        if (!state.isFinal())
            shutDown(new SessionState.ShutdownReason.UserInterrupt("SIGINT/SIGTERM", false));
    }

    // ─── the control socket (§26.6) ────────────────────────────────────────────────────────

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
            // The extra shell runs in the terminal the user typed `oillamp shell` into, so the
            // supervisor hands back the command rather than running it: the session has no
            // terminal to give it, and would only be in the way of one that has.
            case "shell" -> state.isLive()
                    ? Control.Reply.ok().withArgv(
                            Ssh.clientArgv(prepared.layout(), Ssh.SocketRole.EXTRA))
                    : Control.Reply.failed("this session is already ending");
            default -> Control.Reply.failed("unknown request: " + request.op());
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
