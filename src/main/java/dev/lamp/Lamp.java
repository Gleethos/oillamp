package dev.lamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// A running lamp, held by the application that started it.
///
/// ```
/// try (Lamp lamp = Lamp.at(Path.of("/home/me/campaigns/north")).onEvent(ui::show).start()) {
///     if (lamp.awaitRunning(Duration.ofMinutes(15))) { … }
/// }   // closing ends the session and waits until the sandbox is gone
/// ```
///
/// The sandbox itself is run by oillamp's engine, in a separate Java process started from the
/// same classpath as the application: `oillamp at <dir> --embedded`. This object holds that
/// process:
///
/// - its **standard output** carries every [LampEvent] as a line of JSON, which is read on a
///   thread of its own and passed to the listeners;
/// - its **standard input** stays open for as long as the application wants the session. Closing
///   it, in [#close()], ends the session. If the application dies, the operating system closes it,
///   so the sandbox never outlives the application.
///
/// Model requests from the sandbox go through the engine, which adds the key. By default the
/// engine uses the lamp's `[model]` settings and reads the key from its environment, which it
/// inherits from the application. An application with its own settings screen gives both instead:
///
/// ```
/// Lamp.at(dir).modelService(URI.create("https://llm.example.com")).modelKey(userKey).start()
/// ```
///
/// The key goes to the engine in its environment, which only this user can read, and never onto
/// its command line or into the lamp.
///
/// Public because this is what an application uses oillamp through.
public final class Lamp implements AutoCloseable {

    /// The engine's main class. Named as text, because this package must not depend on the engine.
    static final String ENGINE = "dev.oillamp.OilLamp";

    /// The variable in the engine's environment that holds a key given with [Starting#modelKey].
    static final String MODEL_KEY_VARIABLE = "OILLAMP_MODEL_KEY";

    private final Path directory;
    private final Launcher launcher;
    private final Process engine;
    private final List<Consumer<LampEvent>> listeners;
    private final CountDownLatch runningOrEnded = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);
    /// How to run a command in the sandbox, once the engine has said so. Present means running.
    private volatile Optional<LampEvent.SessionOpened> opened = Optional.empty();
    private volatile Optional<ExitStatus> exit = Optional.empty();

    private Lamp(Path directory, Launcher launcher, Process engine, List<Consumer<LampEvent>> listeners) {
        this.directory = directory;
        this.launcher = launcher;
        this.engine = engine;
        this.listeners = listeners;
    }

    /// Starts describing the lamp in `directory`, which oillamp creates if it does not exist.
    public static Starting at(Path directory) {
        return new Starting(directory.toAbsolutePath(), List.of(), Lamp::sameJava,
                            Optional.empty(), Optional.empty());
    }

    /// Starts the engine as a separate process, with `arguments` after the engine's main class
    /// and `environment` added to the environment it inherits.
    ///
    /// Given a replacement with [Starting#launchedBy], for tests, or for an application that
    /// runs the engine some other way.
    @FunctionalInterface
    public interface Launcher {
        Process launch(List<String> arguments, Map<String, String> environment) throws IOException;
    }

    /// A lamp that has been described but not started yet.
    public static final class Starting {

        private final Path directory;
        private final List<Consumer<LampEvent>> listeners;
        private final Launcher launcher;
        private final Optional<URI> modelService;
        private final Optional<String> modelKey;

        private Starting(Path directory, List<Consumer<LampEvent>> listeners, Launcher launcher,
                         Optional<URI> modelService, Optional<String> modelKey) {
            this.directory = directory;
            this.listeners = listeners;
            this.launcher = launcher;
            this.modelService = modelService;
            this.modelKey = modelKey;
        }

        /// Receives every event the engine reports, in order, from the moment it starts.
        ///
        /// Called on one thread that reads the engine's output. A listener that takes long holds
        /// up the ones after it, so a Swing application hands the event over to its event thread.
        public Starting onEvent(Consumer<LampEvent> listener) {
            List<Consumer<LampEvent>> more = new ArrayList<>(listeners);
            more.add(listener);
            return new Starting(directory, List.copyOf(more), launcher, modelService, modelKey);
        }

        public Starting launchedBy(Launcher launcher) {
            return new Starting(directory, listeners, launcher, modelService, modelKey);
        }

        /// Sends the sandbox's model requests to `service`, in place of the lamp's `model.service`:
        /// `https://api.eu.edenai.run`, say, or a model server on this machine with the path of
        /// its API, such as `http://127.0.0.1:11434/v1` for Ollama. It must be `https`, unless it
        /// is on this machine's loopback; the engine refuses anything else, and the events say why.
        ///
        /// Only Eden AI's models are filtered to those served in the EU. Any other service's
        /// models are all offered, since its model list names no regions.
        public Starting modelService(URI service) {
            return new Starting(directory, listeners, launcher, Optional.of(service), modelKey);
        }

        /// Uses `key` for the sandbox's model requests, in place of the one in the variable the
        /// lamp's `model.key_env` names. The sandbox never sees it.
        ///
        /// @throws IllegalArgumentException when `key` is blank, which would only fail later
        public Starting modelKey(String key) {
            if (key.isBlank()) throw new IllegalArgumentException("a model key cannot be blank");
            return new Starting(directory, listeners, launcher, modelService, Optional.of(key.strip()));
        }

        /// Starts the engine, and returns at once. The sandbox is running when
        /// [Lamp#awaitRunning] says so; building its image the first time takes several minutes.
        ///
        /// @throws IOException when the engine's process could not be started at all
        public Lamp start() throws IOException {
            List<String> arguments = new ArrayList<>(List.of("at", directory.toString(), "--embedded"));
            modelService.ifPresent(service -> arguments.addAll(List.of("--model-service", service.toString())));
            modelKey.ifPresent(key -> arguments.addAll(List.of("--model-key-env", MODEL_KEY_VARIABLE)));
            Process engine = launcher.launch(List.copyOf(arguments),
                    modelKey.map(key -> Map.of(MODEL_KEY_VARIABLE, key)).orElse(Map.of()));
            Lamp lamp = new Lamp(directory, launcher, engine, listeners);
            Thread.ofVirtual().name("lamp-events-" + directory.getFileName()).start(lamp::readEvents);
            return lamp;
        }

        /// Deletes this lamp for good: the agent's home with everything the agent made in it,
        /// the lamp's state and its configuration. Files in the directory that oillamp did not
        /// make are left, and so is the directory then.
        ///
        /// Part of a lamp belongs to the sandbox's own users, so neither the application nor
        /// `rm -rf` can delete it; the engine can, as `oillamp remove <dir> --yes`. A lamp whose
        /// sandbox is still running is refused: close it first.
        ///
        /// Blocks until the engine is done. What it reports goes to the listeners.
        ///
        /// @return [ExitStatus#SUCCESS] once the lamp is gone; otherwise the events said why
        /// @throws IOException when the engine's process could not be started at all
        public ExitStatus remove() throws IOException, InterruptedException {
            return runToEnd(launcher, listeners, "remove", directory.toString(), "--yes").status();
        }

        /// Takes a snapshot of this lamp: the agent's home and its `oillamp.toml`, as they are
        /// now. [#restore] brings the lamp back to it later. Works whether or not the lamp's
        /// sandbox is running; see [Lamp#save] for one this application holds.
        ///
        /// Blocks until the engine is done. What it reports goes to the listeners.
        ///
        /// @param message what to remember the snapshot by; may be empty
        /// @return the snapshot, or empty when nothing changed since the last one
        /// @throws Failed      when the lamp could not be saved; the problem says why
        /// @throws IOException when the engine's process could not be started at all
        public Optional<LampEvent.Snapshot> save(String message) throws IOException, InterruptedException, Failed {
            return Lamp.save(launcher, listeners, directory, message);
        }

        /// Every snapshot of this lamp, newest first.
        ///
        /// @throws Failed      when the lamp's history could not be read; the problem says why
        /// @throws IOException when the engine's process could not be started at all
        public List<LampEvent.Snapshot> history() throws IOException, InterruptedException, Failed {
            Ran ran = runToEnd(launcher, listeners, "history", directory.toString());
            ran.orThrow();
            for (LampEvent event : ran.events())
                if (event instanceof LampEvent.History history) {
                    List<LampEvent.Snapshot> snapshots = new ArrayList<>();
                    for (LampEvent.Snapshot snapshot : history.snapshots()) snapshots.add(snapshot);
                    return List.copyOf(snapshots);
                }
            return List.of();
        }

        /// Brings this lamp back to `snapshot`: the agent's home and `oillamp.toml` become what
        /// they were when it was saved. The lamp is saved first, so a restore can be undone by
        /// restoring that save. A lamp whose sandbox is running is refused: close it first.
        ///
        /// @param snapshot a snapshot's [id][LampEvent.Snapshot#id()], or a unique beginning of
        ///                 it of at least four characters
        /// @return the snapshot that records the restore, or the one restored when the lamp
        ///         already was in that state
        /// @throws Failed      when there is no such snapshot, the sandbox is running, or the
        ///                     restore did not finish; the problem says which
        /// @throws IOException when the engine's process could not be started at all
        public LampEvent.Snapshot restore(String snapshot) throws IOException, InterruptedException, Failed {
            Ran ran = runToEnd(launcher, listeners, "restore", directory.toString(), snapshot);
            ran.orThrow();
            for (LampEvent event : ran.events())
                if (event instanceof LampEvent.Restored restored) return restored.result();
            throw new Failed(ran.status(), internal("the engine reported no restore"));
        }
    }

    /// The engine said no, or could not do what was asked. [#problem()] says why, in full.
    public static final class Failed extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient Problem problem;
        private final ExitStatus status;

        Failed(ExitStatus status, Problem problem) {
            super(problem.code() + " " + problem.title() + ": " + problem.whatHappened());
            this.problem = problem;
            this.status = status;
        }

        public Problem problem() { return problem; }

        /// The engine's exit status: [ExitStatus#LAMP_BUSY] for a lamp whose sandbox is running,
        /// [ExitStatus#USAGE] for a snapshot that does not exist.
        public ExitStatus status() { return status; }
    }

    /// Everything an engine said, and how it ended.
    private record Ran(ExitStatus status, List<LampEvent> events) {

        /// Throws the first error the engine reported, if it did not succeed.
        void orThrow() throws Failed {
            if (status.isSuccess()) return;
            for (LampEvent event : events)
                if (event instanceof LampEvent.Failure failure) throw new Failed(status, failure.problem());
            throw new Failed(status, internal("the engine exited with " + status + " without saying why"));
        }
    }

    private static Problem internal(String what) {
        return new Problem(new Problem.Code("OIL-INTERNAL-001"), Problem.Severity.ERROR,
                "Unexpected internal error (please report)", what,
                "this is a bug in oillamp, not something you did wrong",
                sprouts.Tuple.of(Problem.Evidence.class), sprouts.Tuple.of(Problem.Fix.class), Optional.empty());
    }

    /// Runs the engine for one command that ends by itself, passes each event to the listeners,
    /// and waits for it to exit.
    private static Ran runToEnd(Launcher launcher, List<Consumer<LampEvent>> listeners, String... command)
            throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.add("--embedded");
        Process engine = launcher.launch(List.copyOf(arguments), Map.of());
        engine.getOutputStream().close();
        List<LampEvent> events = new ArrayList<>();
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null)
                LampEvent.fromJson(line).ifPresent(event -> {
                    events.add(event);
                    listeners.forEach(listener -> listener.accept(event));
                });
        }
        return new Ran(ExitStatus.ofCode(engine.waitFor()).orElse(ExitStatus.ERROR), List.copyOf(events));
    }

    private static Optional<LampEvent.Snapshot> save(Launcher launcher, List<Consumer<LampEvent>> listeners,
                                                     Path directory, String message)
            throws IOException, InterruptedException, Failed {
        Ran ran = message.isBlank()
                ? runToEnd(launcher, listeners, "save", directory.toString())
                : runToEnd(launcher, listeners, "save", directory.toString(), "--message", message);
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.Saved saved) return Optional.of(saved.snapshot());
        return Optional.empty();
    }

    /// The prefix of the agent directory's name in a lamp. Written out, because this package must
    /// not depend on the engine, whose layout says the same.
    static final String AGENT_DIR_PREFIX = "agent-lamp-";

    /// The agent's home in the lamp at `directory`: the directory the sandbox sees as
    /// `/home/agent`. Nothing, before the lamp was first started.
    ///
    /// For an application that reads what the agent keeps there, such as a harness's saved
    /// conversations, while the sandbox runs or not. The agent writes it, so read it as the
    /// agent's work: never follow a link in it, and never trust a name in it to be a plain name.
    public static Optional<Path> agentHome(Path directory) {
        try (var entries = java.nio.file.Files.list(directory)) {
            List<Path> homes = entries.filter(entry -> entry.getFileName().toString().startsWith(AGENT_DIR_PREFIX))
                    .filter(entry -> java.nio.file.Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .toList();
            return homes.size() == 1 ? Optional.of(homes.getFirst()) : Optional.empty();
        } catch (IOException notThere) {
            return Optional.empty();
        }
    }

    /// The lamp directory.
    public Path directory() { return directory; }

    /// Takes a snapshot of this lamp while its sandbox runs, as [Starting#save] does. Programs in
    /// the sandbox may be writing at that moment, so the snapshot is marked as a running save.
    ///
    /// Blocks until it is done. What the engine reports goes to this lamp's listeners.
    ///
    /// @return the snapshot, or empty when nothing changed since the last one
    /// @throws Failed      when the lamp could not be saved; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public Optional<LampEvent.Snapshot> save(String message) throws IOException, InterruptedException, Failed {
        return save(launcher, listeners, directory, message);
    }

    /// Waits until the session is running, the engine has ended, or `limit` has passed.
    ///
    /// @return true once the session is running. False if it ended without getting there, in
    ///         which case the events said why, or if it is still starting after `limit`
    public boolean awaitRunning(Duration limit) throws InterruptedException {
        runningOrEnded.await(limit.toMillis(), TimeUnit.MILLISECONDS);
        return opened.isPresent() && exit.isEmpty();
    }

    /// Runs a command in the sandbox, as the agent user, with the agent's shell environment and
    /// no terminal. The returned process's standard input, output and error are the command's.
    ///
    /// For example `lamp.exec("opencode", "acp")` starts a harness whose standard input and output
    /// the application then speaks a protocol over.
    ///
    /// @throws IllegalStateException when the session is not running
    /// @throws IOException           when ssh could not be started at all
    public Process exec(String... command) throws IOException {
        return new ProcessBuilder(commandLine(command)).start();
    }

    /// The command line [#exec] runs: the engine's ssh command, followed by `command`, each
    /// argument quoted so the sandbox's shell receives it exactly as given.
    ///
    /// For an application that wants to start the process itself, for example with its own
    /// working directory or redirections.
    ///
    /// @throws IllegalStateException when the session is not running
    public List<String> commandLine(String... command) {
        LampEvent.SessionOpened session = opened.filter(ignored -> exit.isEmpty())
                .orElseThrow(() -> new IllegalStateException(
                        "the lamp at " + directory + " is not running"));
        List<String> line = new ArrayList<>();
        for (String part : session.command()) line.add(part);
        for (String argument : command) line.add(quoted(argument));
        return List.copyOf(line);
    }

    /// ssh joins its arguments with spaces and gives them to a shell in the sandbox. Quoting each
    /// one keeps spaces, quotes and `$` in an argument from being read by that shell.
    private static String quoted(String argument) {
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    /// The Unix socket of the sandbox's desktop, for an application that shows it in a window of
    /// its own. It speaks VNC (RFB 3.8) with no password: only this user can open the socket.
    ///
    /// @throws IllegalStateException when the session is not running
    public Path desktop() {
        return opened.filter(ignored -> exit.isEmpty()).map(LampEvent.SessionOpened::desktop)
                .orElseThrow(() -> new IllegalStateException("the lamp at " + directory + " is not running"));
    }

    /// How the engine ended, once it has.
    public Optional<ExitStatus> exitStatus() { return exit; }

    /// Ends the session and waits until the engine has shut the sandbox down.
    ///
    /// Closing the engine's standard input is the signal. The engine then stops the container,
    /// which takes up to `timeouts.stop_seconds`, and exits.
    ///
    /// Interrupting the waiting thread stops the wait, not the shutdown.
    @Override public void close() {
        try {
            engine.getOutputStream().close();
        } catch (IOException alreadyGone) {
            // The pipe could not be closed because the engine is no longer reading it.
        }
        try {
            ended.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void readEvents() {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null)
                LampEvent.fromJson(line).ifPresent(this::deliver);
        } catch (IOException ended) {
            // The engine's output closed; it has exited, or is about to.
        }
        int code;
        try {
            code = engine.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        exit = Optional.of(ExitStatus.ofCode(code).orElse(ExitStatus.ERROR));
        runningOrEnded.countDown();
        ended.countDown();
    }

    private void deliver(LampEvent event) {
        // The engine reports this once the session is running, with how to reach the sandbox.
        if (event instanceof LampEvent.SessionOpened session) {
            opened = Optional.of(session);
            runningOrEnded.countDown();
        }
        for (Consumer<LampEvent> listener : listeners) listener.accept(event);
    }

    /// Starts the engine with this process's own Java runtime and classpath, so that the
    /// application and the engine are always the same version of oillamp. The engine's error
    /// output goes where the application's does.
    private static Process sameJava(List<String> arguments, Map<String, String> environment) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ENGINE));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environment);
        return builder.start();
    }
}
