package dev.lamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

    /// What [#leaveRunning()] writes on the engine's standard input. Named as text for the same
    /// reason as [#ENGINE].
    static final String LEAVE_RUNNING = "leave-running";

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
    /// Whether the session ends when this lamp is closed: true for a lamp this application
    /// started, until it is left running; false for one it joined.
    private volatile boolean holding;

    private Lamp(Path directory, Launcher launcher, Process engine, List<Consumer<LampEvent>> listeners,
                 boolean holding) {
        this.directory = directory;
        this.launcher = launcher;
        this.engine = engine;
        this.listeners = listeners;
        this.holding = holding;
    }

    /// Starts describing the lamp in `directory`, which oillamp creates if it does not exist.
    public static Starting at(Path directory) {
        return new Starting(directory.toAbsolutePath(), List.of(), Lamp::sameJava,
                            Optional.empty(), Optional.empty(), false);
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
        private final boolean scheduling;

        private Starting(Path directory, List<Consumer<LampEvent>> listeners, Launcher launcher,
                         Optional<URI> modelService, Optional<String> modelKey, boolean scheduling) {
            this.directory = directory;
            this.listeners = listeners;
            this.launcher = launcher;
            this.modelService = modelService;
            this.modelKey = modelKey;
            this.scheduling = scheduling;
        }

        /// Receives every event the engine reports, in order, from the moment it starts.
        ///
        /// Called on one thread that reads the engine's output. A listener that takes long holds
        /// up the ones after it, so a Swing application hands the event over to its event thread.
        public Starting onEvent(Consumer<LampEvent> listener) {
            List<Consumer<LampEvent>> more = new ArrayList<>(listeners);
            more.add(listener);
            return new Starting(directory, List.copyOf(more), launcher, modelService, modelKey, scheduling);
        }

        public Starting launchedBy(Launcher launcher) {
            return new Starting(directory, listeners, launcher, modelService, modelKey, scheduling);
        }

        /// Sends the sandbox's model requests to `service`, in place of the lamp's `model.service`:
        /// `https://api.eu.edenai.run`, say, or a model server on this machine with the path of
        /// its API, such as `http://127.0.0.1:11434/v1` for Ollama. It must be `https`, unless it
        /// is on this machine's loopback; the engine refuses anything else, and the events say why.
        ///
        /// Only Eden AI's models are filtered to those served in the EU. Any other service's
        /// models are all offered, since its model list names no regions.
        public Starting modelService(URI service) {
            return new Starting(directory, listeners, launcher, Optional.of(service), modelKey, scheduling);
        }

        /// Uses `key` for the sandbox's model requests, in place of the one in the variable the
        /// lamp's `model.key_env` names. The sandbox never sees it.
        ///
        /// @throws IllegalArgumentException when `key` is blank, which would only fail later
        public Starting modelKey(String key) {
            if (key.isBlank()) throw new IllegalArgumentException("a model key cannot be blank");
            return new Starting(directory, listeners, launcher, modelService, Optional.of(key.strip()), scheduling);
        }

        /// Lets jobs on the schedule wake the agent in the session [#start] starts, as
        /// `enabled = true` under `[schedule]` in the lamp's oillamp.toml would. The file is not
        /// changed.
        public Starting enableScheduling() {
            return new Starting(directory, listeners, launcher, modelService, modelKey, true);
        }

        /// Starts the engine, and returns at once. The sandbox is running when
        /// [Lamp#awaitRunning] says so; building its image the first time takes several minutes.
        ///
        /// @throws IOException when the engine's process could not be started at all
        public Lamp start() throws IOException {
            List<String> arguments = new ArrayList<>(List.of("at", directory.toString(), "--embedded"));
            modelService.ifPresent(service -> arguments.addAll(List.of("--model-service", service.toString())));
            modelKey.ifPresent(key -> arguments.addAll(List.of("--model-key-env", MODEL_KEY_VARIABLE)));
            if (scheduling) arguments.add("--enable-scheduling");
            Process engine = launcher.launch(List.copyOf(arguments),
                    modelKey.map(key -> Map.of(MODEL_KEY_VARIABLE, key)).orElse(Map.of()));
            Lamp lamp = new Lamp(directory, launcher, engine, listeners, true);
            Thread.ofVirtual().name("lamp-events-" + directory.getFileName()).start(lamp::readEvents);
            return lamp;
        }

        /// Joins the session already running this lamp, whoever started it: this application
        /// before it was closed and [left it running][Lamp#leaveRunning()], another application,
        /// or a person at a terminal. Returns at once.
        ///
        /// The lamp returned is used as one this application started. Its listeners first hear
        /// what the session is doing now: [LampEvent.SessionOpened], after which
        /// [Lamp#awaitRunning] is true; the session's state; the run in progress, from its
        /// [LampEvent.RunStarted], with what the agent wrote so far; a [LampEvent.RunQueued]
        /// for each run waiting; and last a [LampEvent.AgentStatus], which marks the end of the
        /// catching up. Then they hear every event as it happens. The model settings are
        /// those the session was started with.
        ///
        /// Closing it only stops following; the session goes on. [Lamp#stop()] ends it.
        ///
        /// Where no session runs, [Lamp#awaitRunning] is false, and a [LampEvent.Failure]
        /// with problem `OIL-SESSION-001` says so.
        ///
        /// @throws IOException when the engine's process could not be started at all
        public Lamp join() throws IOException {
            Process follower = launcher.launch(List.of("follow", directory.toString(), "--embedded"), Map.of());
            Lamp lamp = new Lamp(directory, launcher, follower, listeners, false);
            Thread.ofVirtual().name("lamp-events-" + directory.getFileName()).start(lamp::readEvents);
            return lamp;
        }

        /// Whether a session is running this lamp, so that [#join] finds it. Looks only at the
        /// record a session keeps while it runs; a session whose engine was killed leaves that
        /// record behind, and [#join] then says it cannot reach it.
        public boolean isRunning() {
            return java.nio.file.Files.exists(directory.resolve(".oillamp").resolve("session.json"));
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

        /// This lamp's schedule: the jobs that wake the agent while a session runs.
        ///
        /// @throws Failed      when the schedule could not be read; the problem says why
        /// @throws IOException when the engine's process could not be started at all
        public LampEvent.Schedule schedule() throws IOException, InterruptedException, Failed {
            return Lamp.schedule(launcher, listeners, directory);
        }

        /// Adds a job that wakes the agent again and again, as `cron` says.
        ///
        /// @param cron   five fields, as cron has them, on this machine's clock, such as
        ///               `0 9 * * 1-5` for nine on every weekday
        /// @param prompt what the agent is asked each time
        /// @throws Failed when the expression cannot be read; the problem says why
        public LampEvent.Job repeat(String cron, String prompt) throws IOException, InterruptedException, Failed {
            return Lamp.addJob(launcher, listeners, directory, "--cron", cron, prompt);
        }

        /// The same, taken off the schedule at `until`.
        public LampEvent.Job repeat(String cron, String prompt, Instant until) throws IOException, InterruptedException, Failed {
            return Lamp.addJob(launcher, listeners, directory, "--cron", cron, prompt, "--expires", until.toString());
        }

        /// Adds a job that wakes the agent once.
        ///
        /// @param at when: `2026-10-01 09:00` on this machine's clock, `2026-10-01T07:00Z`, or
        ///           `in 2h`
        /// @throws Failed when the time cannot be read or has passed; the problem says why
        public LampEvent.Job once(String at, String prompt) throws IOException, InterruptedException, Failed {
            return Lamp.addJob(launcher, listeners, directory, "--at", at, prompt);
        }

        /// Adds a job that wakes the agent once, at `at`.
        ///
        /// @throws Failed when `at` has passed
        public LampEvent.Job once(Instant at, String prompt) throws IOException, InterruptedException, Failed {
            return once(at.toString(), prompt);
        }

        /// Switches a job back on. A repeating job then runs at its next time from now, not once
        /// for each time it was off.
        ///
        /// @throws Failed when there is no such job
        public void enable(String job) throws IOException, InterruptedException, Failed {
            runToEnd(launcher, listeners, "schedule", directory.toString(), "enable", job).orThrow();
        }

        /// Switches a job off. It stays on the schedule, and does not run until it is switched on.
        ///
        /// @throws Failed when there is no such job
        public void disable(String job) throws IOException, InterruptedException, Failed {
            runToEnd(launcher, listeners, "schedule", directory.toString(), "disable", job).orThrow();
        }

        /// Pauses the whole schedule: no job runs until [#resume].
        public void pause() throws IOException, InterruptedException, Failed {
            runToEnd(launcher, listeners, "schedule", directory.toString(), "pause").orThrow();
        }

        public void resume() throws IOException, InterruptedException, Failed {
            runToEnd(launcher, listeners, "schedule", directory.toString(), "resume").orThrow();
        }

        /// Every conversation the agent had in this lamp, the most recent first. See
        /// [Lamp#conversations(Path)].
        public List<Conversation> conversations() { return Lamp.conversations(directory); }

        /// The conversation named by `id` or a unique beginning of it.
        public Optional<Conversation> conversation(String id) { return Lamp.conversation(directory, id); }

        /// Deletes a conversation for good. See [Lamp#forget(Path, String)].
        public void forget(String conversation) throws IOException { Lamp.forget(directory, conversation); }

        /// Asks the agent `question` in the session that is running this lamp, whoever started it,
        /// and waits for the answer. See [Lamp#ask(Question)].
        ///
        /// @throws Failed when no session is running, or the question has no place to go
        public LampEvent.RunFinished ask(Question question) throws IOException, InterruptedException, Failed {
            return Lamp.ask(launcher, listeners, directory, question);
        }

        /// Takes a job off the schedule, whoever added it.
        ///
        /// @param job such as `job-3`
        /// @throws Failed when there is no such job
        public void unschedule(String job) throws IOException, InterruptedException, Failed {
            runToEnd(launcher, listeners, "schedule", directory.toString(), "remove", job).orThrow();
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
        // Before `--`, after which the engine takes every argument as text, such as a prompt.
        int literal = arguments.indexOf("--");
        arguments.add(literal < 0 ? arguments.size() : literal, "--embedded");
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

    private static LampEvent.Schedule schedule(Launcher launcher, List<Consumer<LampEvent>> listeners, Path directory)
            throws IOException, InterruptedException, Failed {
        Ran ran = runToEnd(launcher, listeners, "schedule", directory.toString());
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.Schedule schedule) return schedule;
        throw new Failed(ran.status(), internal("the engine reported no schedule"));
    }

    private static LampEvent.Job addJob(Launcher launcher, List<Consumer<LampEvent>> listeners, Path directory,
                                        String option, String when, String prompt, String... more)
            throws IOException, InterruptedException, Failed {
        List<String> command = new ArrayList<>(List.of("schedule", directory.toString(), "add", option, when));
        command.addAll(List.of(more));
        command.addAll(List.of("--", prompt));
        Ran ran = runToEnd(launcher, listeners, command.toArray(String[]::new));
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.JobAdded added) return added.job();
        throw new Failed(ran.status(), internal("the engine reported no job"));
    }

    // ─── conversations ─────────────────────────────────────────────────────────────────────

    /// Something to ask the agent, and where in its conversations it goes.
    ///
    /// @param conversation the conversation it continues, by its id or a unique start of it; empty
    ///                     for a new conversation
    /// @param after        an entry of that conversation to continue after, such as an earlier
    ///                     answer, which starts a branch there if the conversation went on since
    /// @param insteadOf    a question of that conversation to ask this instead of. The old question
    ///                     and what followed stay, as a branch of their own
    public record Question(String prompt, Optional<String> conversation, Optional<String> after,
                           Optional<String> insteadOf) {

        public Question {
            if (prompt.isBlank()) throw new IllegalArgumentException("a question needs something to ask");
            if ((after.isPresent() || insteadOf.isPresent()) && conversation.isEmpty())
                throw new IllegalArgumentException("an entry belongs to a conversation; name the conversation too");
            if (after.isPresent() && insteadOf.isPresent())
                throw new IllegalArgumentException("a question goes after an entry or instead of a question, not both");
        }

        /// In a new conversation.
        public static Question fresh(String prompt) {
            return new Question(prompt, Optional.empty(), Optional.empty(), Optional.empty());
        }

        /// In `conversation`, where it stands: after the entry written last.
        public static Question in(String conversation, String prompt) {
            return new Question(prompt, Optional.of(conversation), Optional.empty(), Optional.empty());
        }

        public static Question after(String conversation, String entry, String prompt) {
            return new Question(prompt, Optional.of(conversation), Optional.of(entry), Optional.empty());
        }

        public static Question insteadOf(String conversation, String entry, String prompt) {
            return new Question(prompt, Optional.of(conversation), Optional.empty(), Optional.of(entry));
        }
    }

    /// One conversation the agent had, as pi keeps it in the agent's home.
    ///
    /// A conversation is a tree of entries: every entry names the one before it. Most of the time
    /// that makes a line, a question, its answer and the next question. Asking something else
    /// instead of an earlier question starts a second line from the same entry, and both are kept.
    /// The conversation stands at its [leaf][#leaf()]: the entry written last. Asking in it
    /// continues from there, unless the question says where else it goes.
    ///
    /// @param id       pi's id for it, such as `01a0ec69-de1c-7234-914c-b970a862c13e`. Any unique
    ///                 beginning of it of at least four characters names it too
    /// @param name     the name it was given, such as `run-12 (job-3)`, or empty
    /// @param file     where pi keeps it, relative to the agent's home
    /// @param modified when its last entry was written
    /// @param entries  every entry, in the order pi wrote them
    public record Conversation(String id, String name, String file, java.time.Instant started,
                               java.time.Instant modified, sprouts.Tuple<Entry> entries) {

        /// One line of pi's session file.
        ///
        /// @param id       unique within the conversation; what [Question#after] and
        ///                 [Question#insteadOf] take
        /// @param parent   the entry before this one; empty for the first. Entries with the same
        ///                 parent are where the conversation forks
        /// @param text     for a question, the message sent; for an answer, the agent's text, or the
        ///                 error when it failed; for a tool's output, the output; for a summary, the
        ///                 summary; otherwise empty
        /// @param thinking for an answer, the model's thinking; otherwise empty
        /// @param calls    for an answer, the tools it called; otherwise empty
        /// @param tool     for a tool's output, the tool's name; otherwise empty
        /// @param failed   for an answer, the model failed or was stopped; for a tool's output, the
        ///                 tool reported an error
        public record Entry(String id, Optional<String> parent, java.time.Instant at, Kind kind, String text,
                            String thinking, sprouts.Tuple<ToolCall> calls, Optional<String> tool, boolean failed) {}

        public enum Kind {
            /// A message to the agent: a person's question, or a job's prompt.
            MESSAGE_TO_AGENT,
            /// A message from the agent.
            MESSAGE_FROM_AGENT,
            /// The output of one tool call.
            TOOL_OUTPUT,
            /// pi's summary of entries it took out of the model's context.
            SUMMARY,
            /// Anything else, such as pi's instructions to the model or a change of model.
            OTHER
        }

        /// A tool an answer called.
        ///
        /// @param summary the command for `bash`, the path for the file tools, otherwise the
        ///                arguments as JSON; one line, at most 160 characters
        public record ToolCall(String id, String name, String summary) {}

        /// How the conversation is shown: its name, or its first question.
        public String title() {
            if (!name.isBlank()) return oneLine(name);
            for (Entry entry : entries)
                if (entry.kind() == Kind.MESSAGE_TO_AGENT && !entry.text().isBlank()) return oneLine(entry.text());
            return "New conversation";
        }

        /// The job whose run had this conversation, such as `job-3`, or nothing for one someone
        /// asked. The engine names a job's conversation after the run and the job, `run-12 (job-3)`,
        /// and pi's file keeps nothing else that says so.
        public Optional<String> job() {
            java.util.regex.Matcher named = JOB_RUN_NAME.matcher(name);
            return named.matches() ? Optional.of(named.group(1)) : Optional.empty();
        }

        /// How the engine names a job run's conversation. Written out, because this package must
        /// not depend on the engine.
        private static final java.util.regex.Pattern JOB_RUN_NAME = java.util.regex.Pattern.compile("run-\\d+ \\((job-\\d+)\\)");

        /// The entry it stands at: the last one written. Empty for a conversation with none.
        public Optional<String> leaf() {
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.last().id());
        }

        public Optional<Entry> entry(String id) {
            return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
        }

        /// The line the conversation stands on: every entry from the first to the leaf.
        public sprouts.Tuple<Entry> line() {
            return leaf().map(this::lineTo).orElse(sprouts.Tuple.of(Entry.class));
        }

        /// Every entry from the first up to and including `id`. Empty when there is no such entry.
        public sprouts.Tuple<Entry> lineTo(String id) {
            java.util.Map<String, Entry> byId = new java.util.HashMap<>();
            for (Entry entry : entries) byId.put(entry.id(), entry);
            java.util.List<Entry> line = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Entry at = byId.get(id); at != null && seen.add(at.id()); at = at.parent().map(byId::get).orElse(null))
                line.add(at);
            return sprouts.Tuple.of(Entry.class, line.reversed());
        }

        /// The entries that follow `id` directly: one, or several where the conversation forks.
        public sprouts.Tuple<Entry> children(String id) {
            return entries.retainIf(entry -> entry.parent().filter(id::equals).isPresent());
        }

        /// The first eighteen characters of [#id()], which is how oillamp shows a conversation.
        /// pi's ids begin with the time, so a shorter beginning is not always unique.
        public String shortId() { return id.substring(0, Math.min(18, id.length())); }

        private static String oneLine(String text) {
            String line = text.strip().replaceAll("\\s+", " ");
            return line.length() <= 60 ? line : line.substring(0, 59) + "…";
        }
    }

    /// Where pi keeps conversations, relative to the agent's home.
    static final String SESSIONS = ".pi/agent/sessions";

    /// Session files larger than this are left out. A long conversation with much tool output is
    /// a few megabytes.
    static final long LARGEST_CONVERSATION = 64L * 1024 * 1024;

    /// Every conversation the agent had in the lamp at `directory`, the most recent first. Works
    /// whether or not the lamp is running, and reads the files directly, so it is quick enough
    /// to call whenever something may have changed.
    ///
    /// The agent writes these files, so they are read as the agent's work: no link is followed,
    /// not even a directory on the way, and a file that is not a conversation is left out.
    public static List<Conversation> conversations(Path directory) {
        Optional<Path> sessions = agentHome(directory).flatMap(home -> directoryWithoutLinks(home, SESSIONS));
        if (sessions.isEmpty()) return List.of();
        List<Conversation> found = new ArrayList<>();
        for (Path folder : listed(sessions.get())) {
            if (!java.nio.file.Files.isDirectory(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            for (Path file : listed(folder)) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl") || !java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    continue;
                try {
                    if (java.nio.file.Files.size(file) > LARGEST_CONVERSATION) continue;
                    List<String> lines = java.nio.file.Files.readAllLines(file, StandardCharsets.UTF_8);
                    PiSessionFile.parse(SESSIONS + "/" + folder.getFileName() + "/" + name, lines).ifPresent(found::add);
                } catch (IOException | java.io.UncheckedIOException unreadable) {
                    // Being written, or gone since it was listed: left out until the next look.
                }
            }
        }
        found.sort(java.util.Comparator.comparing(Conversation::modified).reversed());
        return List.copyOf(found);
    }

    /// The conversation named `id`, or by a unique beginning of it of at least four characters.
    public static Optional<Conversation> conversation(Path directory, String id) {
        String wanted = id.strip();
        if (wanted.length() < 4) return Optional.empty();
        List<Conversation> matching = conversations(directory).stream().filter(c -> c.id().startsWith(wanted)).toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }

    /// Deletes the conversation named `id` from the lamp at `directory`, for good.
    ///
    /// Deleting the conversation the agent is working in makes that run fail; the next one starts
    /// afresh.
    ///
    /// @throws IOException when there is no such conversation, or it could not be deleted
    public static void forget(Path directory, String id) throws IOException {
        Conversation doomed = conversation(directory, id)
                .orElseThrow(() -> new IOException("the lamp has no conversation called '" + id + "'"));
        Path home = agentHome(directory).orElseThrow(() -> new IOException("the lamp has no agent home"));
        Path file = home.resolve(doomed.file());
        // Found by listing without following links, so it is a plain file where it should be.
        java.nio.file.Files.delete(file);
    }

    private static Optional<Path> directoryWithoutLinks(Path from, String relative) {
        Path at = from;
        for (Path part : Path.of(relative)) {
            at = at.resolve(part);
            if (!java.nio.file.Files.isDirectory(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        }
        return Optional.of(at);
    }

    private static List<Path> listed(Path directory) {
        try (var entries = java.nio.file.Files.list(directory)) {
            return entries.toList();
        } catch (IOException unreadable) {
            return List.of();
        }
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

    /// The models the agent of a lamp given `service` and `key` with [Starting#modelService] and
    /// [Starting#modelKey] is offered, in the service's order. Asked on this machine, without the
    /// engine, so an application can offer them in its settings before any lamp runs.
    ///
    /// The service is asked where the engine's relay asks it: under its own path, such as
    /// `http://127.0.0.1:11434/v1/models` for Ollama, or under `/v3` without one, as for Eden AI.
    /// The key goes with the request as `Authorization: Bearer`, and nowhere else: no proxy, no
    /// redirect. For Eden AI only the models served in the EU are listed, as in the sandbox.
    ///
    /// @throws IllegalArgumentException when the engine would refuse `service`, such as plain
    ///                                  `http` anywhere but on this machine
    /// @throws IOException              when the service cannot be asked, or refuses; the message
    ///                                  says why, in words an application can show
    public static List<String> models(URI service, String key) throws IOException, InterruptedException {
        return ModelList.of(service, Optional.of(key));
    }

    /// The models a service offers without a key, such as a model server on this machine or
    /// Eden AI, whose list is public. See [#models(URI, String)].
    public static List<String> models(URI service) throws IOException, InterruptedException {
        return ModelList.of(service, Optional.empty());
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

    /// This lamp's schedule, as [Starting#schedule] reads it.
    public LampEvent.Schedule schedule() throws IOException, InterruptedException, Failed {
        return schedule(launcher, listeners, directory);
    }

    /// Adds a repeating job, as [Starting#repeat] does. The running session sees it at once.
    public LampEvent.Job repeat(String cron, String prompt) throws IOException, InterruptedException, Failed {
        return addJob(launcher, listeners, directory, "--cron", cron, prompt);
    }

    /// Adds a job that runs once, as [Starting#once] does. The running session sees it at once.
    public LampEvent.Job once(String at, String prompt) throws IOException, InterruptedException, Failed {
        return addJob(launcher, listeners, directory, "--at", at, prompt);
    }

    /// Every conversation the agent had in this lamp, the most recent first.
    public List<Conversation> conversations() { return conversations(directory); }

    /// The conversation named by `id` or a unique beginning of it.
    public Optional<Conversation> conversation(String id) { return conversation(directory, id); }

    /// Deletes a conversation for good. See [Lamp#forget(Path, String)].
    public void forget(String conversation) throws IOException { forget(directory, conversation); }

    /// Takes a job off the schedule, as [Starting#unschedule] does.
    public void unschedule(String job) throws IOException, InterruptedException, Failed {
        runToEnd(launcher, listeners, "schedule", directory.toString(), "remove", job).orThrow();
    }

    /// Wakes the agent with `prompt`, and waits until it has answered.
    ///
    /// The session holds one agent, which works on one thing at a time. When it is busy, with a
    /// job or with another question, this waits its turn. The lamp is saved just before and just
    /// after, so what the agent changed is one snapshot of its own, named in the result.
    ///
    /// Blocks until the agent is done, which can take minutes. What the engine reports goes to
    /// this lamp's listeners.
    ///
    /// @return how it ended, and what the agent said at the end
    /// @throws Failed      when the session is not running or is ending; the problem says why
    /// @throws IOException when the engine's process could not be started at all
    public LampEvent.RunFinished ask(String prompt) throws IOException, InterruptedException, Failed {
        return ask(Question.fresh(prompt));
    }

    /// Asks the agent `question`, where it says: in a new conversation, or in one of the agent's
    /// conversations, where it stands, after one of its entries, or instead of one of its
    /// questions. Otherwise as [#ask(String)].
    ///
    /// @throws Failed when the conversation or the entry is not there, or the session is not
    ///                running; the problem says which
    public LampEvent.RunFinished ask(Question question) throws IOException, InterruptedException, Failed {
        return ask(launcher, listeners, directory, question);
    }

    /// Hands the agent `question`, and returns at once with the run that will answer it.
    ///
    /// For an application that shows the answer as it is written: the run's events, from
    /// [LampEvent.RunStarted] through each [LampEvent.RunProgress] to [LampEvent.RunFinished],
    /// arrive at this lamp's listeners, with the returned run's id. When the agent is busy, the
    /// run waits its turn, and [LampEvent.RunQueued] says so.
    ///
    /// @throws Failed when the conversation or the entry is not there, or the session is ending
    public LampEvent.Run send(Question question) throws IOException, InterruptedException, Failed {
        Ran ran = runToEnd(launcher, listeners, askCommand(directory, question, "--no-wait"));
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.RunAccepted accepted) return accepted.run();
        ran.orThrow();
        throw new Failed(ran.status(), internal("the engine reported no run"));
    }

    /// Stops the run the agent is working on. What it did until then is saved, as for any run,
    /// and its [LampEvent.RunFinished] says it was [cancelled][LampEvent.RunOutcome#CANCELLED].
    ///
    /// @throws Failed when the agent is not working on anything
    public void cancel() throws IOException, InterruptedException, Failed {
        runToEnd(launcher, listeners, "cancel", directory.toString()).orThrow();
    }

    /// Stops `run`: the one in progress, or one still waiting, which then never starts.
    ///
    /// @throws Failed when no such run is in progress or waiting
    public void cancel(String run) throws IOException, InterruptedException, Failed {
        runToEnd(launcher, listeners, "cancel", directory.toString(), run).orThrow();
    }

    /// What the agent is doing: the run in progress, if any, and the runs waiting.
    ///
    /// @throws Failed when the session is not running
    public LampEvent.AgentStatus agentStatus() throws IOException, InterruptedException, Failed {
        Ran ran = runToEnd(launcher, listeners, "status", directory.toString());
        ran.orThrow();
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.AgentStatus status) return status;
        throw new Failed(ran.status(), internal("the engine reported no status of the agent"));
    }

    private static String[] askCommand(Path directory, Question question, String... options) {
        List<String> command = new ArrayList<>(List.of("ask", directory.toString()));
        question.conversation().ifPresent(id -> command.addAll(List.of("--in", id)));
        question.after().ifPresent(id -> command.addAll(List.of("--after", id)));
        question.insteadOf().ifPresent(id -> command.addAll(List.of("--instead-of", id)));
        command.addAll(List.of(options));
        command.addAll(List.of("--", question.prompt()));
        return command.toArray(String[]::new);
    }

    private static LampEvent.RunFinished ask(Launcher launcher, List<Consumer<LampEvent>> listeners, Path directory,
                                             Question question) throws IOException, InterruptedException, Failed {
        Ran ran = runToEnd(launcher, listeners, askCommand(directory, question));
        for (LampEvent event : ran.events())
            if (event instanceof LampEvent.RunFinished finished) return finished;
        ran.orThrow();
        throw new Failed(ran.status(), internal("the engine reported no answer"));
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

    /// How the engine ended, once it has. For a joined lamp, how following ended:
    /// [ExitStatus#SUCCESS] when the session ended, or when this lamp was closed.
    public Optional<ExitStatus> exitStatus() { return exit; }

    /// Ends the session and waits until the engine has shut the sandbox down.
    ///
    /// Closing the engine's standard input is the signal. The engine then stops the container,
    /// which takes up to `timeouts.stop_seconds`, and exits.
    ///
    /// After [#leaveRunning()], this ends nothing and returns at once.
    ///
    /// Interrupting the waiting thread stops the wait, not the shutdown.
    @Override public void close() {
        try {
            engine.getOutputStream().close();
        } catch (IOException alreadyGone) {
            // The pipe could not be closed because the engine is no longer reading it.
        }
        if (!holding) return;
        try {
            ended.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Lets the session go on after this application is gone, until [#stop()] or
    /// `oillamp stop <dir>` ends it. [Starting#join] finds it again.
    ///
    /// For an application that closes while the agent should keep working: on its schedule, say.
    /// The engine is told on its standard input, which is closed after. Its events keep coming
    /// to the listeners until this application exits.
    ///
    /// Without this, the session ends when the application does, however it ends, so a crash
    /// never leaves a sandbox running. On a lamp this application joined, it is the same as
    /// [#close()].
    public void leaveRunning() {
        holding = false;
        try (var input = engine.getOutputStream()) {
            input.write((LEAVE_RUNNING + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException alreadyGone) {
            // The engine has ended, so there is nothing left to leave running.
        }
    }

    /// Ends the session, whoever started it, and waits until it has ended: the sandbox is
    /// shut down and the lamp saved.
    ///
    /// @throws Failed when no session is running
    public void stop() throws IOException, InterruptedException, Failed {
        runToEnd(launcher, listeners, "stop", directory.toString()).orThrow();
        ended.await();
    }

    /// Whether the session ends when this lamp is closed: true for a lamp this application
    /// started, until it was left running.
    public boolean holds() { return holding; }

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
    ///
    /// The engine runs in a session of its own (`setsid`), so a session left running is not hung
    /// up when the terminal the application was started from closes. One still held ends anyway,
    /// with the application.
    private static Process sameJava(List<String> arguments, Map<String, String> environment) throws IOException {
        List<String> command = new ArrayList<>(List.of("setsid",
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ENGINE));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environment);
        return builder.start();
    }
}
