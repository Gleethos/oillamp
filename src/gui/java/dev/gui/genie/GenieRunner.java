package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Conversation;
import dev.gui.model.Conversations;
import dev.gui.model.Genie;
import dev.gui.model.Settings;
import dev.gui.pi.PiEvent;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

import sprouts.Tuple;

/// Keeps one genie alive: lights its lamp, asks its agent through the lamp's session, and passes
/// files.
///
/// The genie's agent is the pi that the lamp's session holds. Everything goes through the Lamp
/// API: a message is sent with [Lamp#send], the answer comes back as the session's run events,
/// and the conversations are read from the lamp with [Lamp#conversations], awake or asleep.
///
/// It decides nothing about what the window shows. Everything that happens becomes a change to
/// the genie, `UnaryOperator<Genie>`, handed to `changes`; the app applies it to its state.
///
/// Waking and sleeping take long, so they run one after the other on a thread of the runner's
/// own; the calls return at once.
public final class GenieRunner {

    private final Path directory;
    private final Lighter lighter;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("genie").factory());
    /// One reading after the other, so that an older reading never arrives after a newer one.
    private final ExecutorService conversationReader = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("conversations").factory());
    private volatile Optional<Lighter.Lit> lamp = Optional.empty();
    private volatile boolean sleeping;

    /// Where the next message goes: the conversation's file, relative to the genie's home, and
    /// the entry it continues after. Both empty for a new conversation.
    private volatile Conversations.Here where = Conversations.Here.UNKNOWN;
    /// Other runs, such as a scheduled job's, change only the tree of conversations.
    private final Set<String> runsOfThisChat = ConcurrentHashMap.newKeySet();
    /// Even a failed answer counts.
    private final Set<String> runsThatAnswered = ConcurrentHashMap.newKeySet();
    /// Until [Lamp#send] returns. The run's first events can come before that, and are
    /// recognised by it.
    private volatile Optional<String> promptBeingSent = Optional.empty();
    /// The one the stop button stops.
    private volatile Optional<String> runInProgress = Optional.empty();
    /// As [#where] named it when they were asked: empty for a new one. The chat follows them
    /// only while it shows that conversation.
    private volatile String conversationOfRuns = "";
    private final ScheduleKeeper schedule;
    /// The events of a lamp being joined, until the catch-up ends with the agent's status, so
    /// that the chat is set up before the run in progress is replayed into it. Empty otherwise.
    private volatile Optional<List<LampEvent>> heldWhileCatchingUp = Optional.empty();
    /// Where the chat goes on joining, when the genie is not answering a question of the user's.
    private volatile Conversations.Here rejoinAt = Conversations.Here.UNKNOWN;
    /// Whether the lamp was left running as the app closes. What it reports after that is for
    /// the app that joins it next.
    private volatile boolean leftRunning;

    public GenieRunner(Path directory, Lighter lighter, Consumer<UnaryOperator<Genie>> changes) {
        this.directory = directory;
        this.lighter = lighter;
        this.changes = changes;
        this.schedule = new ScheduleKeeper(lighter.unlit(directory), changes);
    }

    /// The genie's schedule, which can be read and changed awake or asleep.
    public ScheduleKeeper schedule() { return schedule; }

    /// The genie's lamp directory.
    public Path directory() { return directory; }

    /// Lights the lamp, and shows the genie's most recent conversation.
    ///
    /// @param key the model key, from the settings or the environment; the lamp's engine keeps it
    public void wake(String name, Settings settings, String key) {
        wake(name, settings, key, "", "");
    }

    /// Wakes the genie in one of its conversations.
    ///
    /// @param conversation the conversation's file relative to the genie's home, or empty for
    ///                     its most recent one
    /// @param leaf         the entry to continue after, or empty for where the conversation stands
    public void wake(String name, Settings settings, String key, String conversation, String leaf) {
        work.execute(() -> {
            if (lamp.isPresent()) return;
            sleeping = false;
            changes.accept(Genie::waking);
            try {
                Lighter.Lit lit = lighter.light(directory, settings, key,
                        what -> changes.accept(genie -> genie.lampSays(what)), this::onLampEvent);
                lamp = Optional.of(lit);
                changes.accept(genie -> genie.lampSays("waking the genie"));
                Handouts.makeDirectories(lit);
                GeniePrompt.prepare(home().orElseThrow(() -> new IOException("the genie's lamp has no home")),
                        name, settings.model());
                List<Lamp.Conversation> all = Lamp.conversations(directory);
                String file = !conversation.isEmpty() ? conversation : all.isEmpty() ? "" : all.getFirst().file();
                showConversation(file, leaf);
                var files = Handouts.list(lit);
                changes.accept(genie -> genie.withHandouts(files).awake());
                reloadConversations();
            } catch (IOException | RuntimeException failed) {
                putOutLamp();
                changes.accept(genie -> genie.broken(reasonOf(failed)));
            } catch (InterruptedException interrupted) {
                putOutLamp();
                changes.accept(Genie::asleep);
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Sends the user's message, to where the chat is. While the genie still works on another
    /// run, it waits its turn.
    public void sendMessage(String text) {
        sendQuestion(questionWhereTheChatIs(text));
    }

    /// Stops the run that answers this chat. What the genie did until then stays, and is saved.
    public void stopAnswering() {
        runInProgress.ifPresent(this::stopRun);
    }

    /// Stops `run`, such as a job's. What the genie did until then stays, and is saved.
    public void stopRun(String run) {
        Optional<Lighter.Lit> lit = lamp;
        if (lit.isEmpty()) return;
        Thread.ofVirtual().name("stop").start(() -> {
            try {
                lit.get().cancel(run);
            } catch (IOException | Lamp.Failed failed) {
                showProblem("The genie could not be stopped: " + reasonOf(failed));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ─── its conversations ─────────────────────────────────────────────────────────────────

    /// Shows a conversation, from its start to the entry `leaf`. The next message continues from
    /// there. Works while the genie sleeps, and while it answers in another conversation.
    ///
    /// @param conversation its file relative to the genie's home
    /// @param leaf         the entry to continue after, or empty for where the conversation stands
    public void goToConversation(String conversation, String leaf) {
        work.execute(() -> showConversation(conversation, leaf));
    }

    /// Shows the conversation pi knows by `id`, where it stands, such as the one a job's run had.
    public void openConversation(String id) {
        work.execute(() -> Lamp.conversation(directory, id).ifPresentOrElse(conversation -> showConversation(conversation.file(), ""),
                () -> showProblem("That conversation is not there any more.")));
    }

    /// Starts a new conversation: the chat empties, and the next message begins it. The others stay.
    public void startAfresh() {
        work.execute(() -> showConversation("", ""));
    }

    /// Asks `text` instead of the user's question `id`. The question and what followed it stay
    /// in the conversation, as a branch of their own.
    public void askInstead(String id, String text) {
        Optional<Lamp.Conversation> conversation = conversationIn(where.file());
        if (conversation.isEmpty()) {
            showProblem("The conversation of that question is not there any more.");
            return;
        }
        sendQuestion(Lamp.Question.insteadOf(conversation.get().id(), id, text));
    }

    /// Deletes a conversation for good. Deleting the one the chat shows starts a new one.
    ///
    /// @param conversation its file relative to the genie's home
    public void forgetConversation(String conversation) {
        work.execute(() -> {
            try {
                Optional<Lamp.Conversation> doomed = conversationIn(conversation);
                if (doomed.isEmpty()) throw new IOException("it is not there any more");
                if (where.file().equals(conversation)) showConversation("", "");
                Lamp.forget(directory, doomed.get().id());
            } catch (IOException failed) {
                showProblem("The conversation could not be deleted: " + reasonOf(failed));
            }
            reloadConversations();
        });
    }

    /// Reads the genie's conversations from its lamp again, awake or asleep.
    public void reloadConversations() {
        conversationReader.execute(() -> {
            Tuple<Conversation> all = Tuple.of(Conversation.class);
            for (Lamp.Conversation conversation : Lamp.conversations(directory)) all = all.add(LampTalk.conversation(conversation));
            Tuple<Conversation> read = all;
            changes.accept(genie -> genie.withConversations(genie.conversations().withAll(read)));
        });
    }

    /// Makes the chat show `file` up to `leaf`, and the next message go there. Empty `file` for a
    /// new conversation.
    private void showConversation(String file, String leaf) {
        Optional<Lamp.Conversation> conversation = conversationIn(file);
        if (conversation.isEmpty()) {
            where = Conversations.Here.UNKNOWN;
            changes.accept(genie -> genie.shows("", new PiEvent.History(Tuple.of(PiEvent.History.Line.class), "")));
            return;
        }
        String at = conversation.get().entry(leaf).isPresent() ? leaf : conversation.get().leaf().orElse("");
        where = new Conversations.Here(file, at);
        PiEvent.History history = LampTalk.history(conversation.get(), at);
        changes.accept(genie -> genie.shows(file, history));
    }

    /// The conversation kept in `file`, relative to the genie's home.
    private Optional<Lamp.Conversation> conversationIn(String file) {
        if (file.isEmpty()) return Optional.empty();
        return Lamp.conversations(directory).stream().filter(c -> c.file().equals(file)).findFirst();
    }

    /// The next message's place: after the entry the chat shows, in the conversation it shows.
    private Lamp.Question questionWhereTheChatIs(String text) {
        Conversations.Here here = where;
        Optional<Lamp.Conversation> conversation = conversationIn(here.file());
        if (conversation.isEmpty()) return Lamp.Question.fresh(text);
        String id = conversation.get().id();
        if (here.leaf().isEmpty() || conversation.get().leaf().filter(here.leaf()::equals).isPresent())
            return Lamp.Question.in(id, text);
        boolean aQuestion = conversation.get().entry(here.leaf())
                .filter(entry -> entry.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT).isPresent();
        return aQuestion ? Lamp.Question.insteadOf(id, here.leaf(), text) : Lamp.Question.after(id, here.leaf(), text);
    }

    // ─── a lamp left running ───────────────────────────────────────────────────────────────

    /// Joins the genie's lamp, if it was left running when the app last closed. The chat shows
    /// the answer the genie is writing, if it answers one of the user's questions; otherwise the
    /// conversation given.
    ///
    /// @param conversation the conversation's file relative to the genie's home, or empty for
    ///                     its most recent one
    /// @param leaf         the entry to continue after, or empty for where the conversation stands
    public void rejoin(String conversation, String leaf) {
        work.execute(() -> {
            if (lamp.isPresent() || !lighter.isLit(directory)) return;
            sleeping = false;
            rejoinAt = new Conversations.Here(conversation, leaf);
            heldWhileCatchingUp = Optional.of(new ArrayList<>());
            changes.accept(genie -> genie.waking().withActivity("joining its lamp, which was left running"));
            try {
                Optional<Lighter.Lit> lit = lighter.join(directory, this::onLampEvent);
                if (lit.isEmpty()) {
                    heldWhileCatchingUp = Optional.empty();
                    changes.accept(Genie::asleep);
                    return;
                }
                lamp = lit;
                var files = Handouts.list(lit.get());
                changes.accept(genie -> genie.withHandouts(files).awake());
                reloadConversations();
            } catch (IOException | RuntimeException failed) {
                heldWhileCatchingUp = Optional.empty();
                putOutLamp();
                changes.accept(genie -> genie.broken(reasonOf(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// The catch-up of a lamp being joined has ended. A question of the user's that the genie
    /// answers becomes this chat's run again, and the chat shows it; then what was held back is
    /// heard, in order.
    private void caughtUp(LampEvent.AgentStatus status, List<LampEvent> held) {
        Optional<LampEvent.Run> answering = status.current().filter(run -> run.job().isEmpty());
        if (answering.isPresent()) {
            runsOfThisChat.add(answering.get().id());
            showAnswering(answering.get());
        } else {
            showConversation(fileOrMostRecent(rejoinAt.file()), rejoinAt.leaf());
        }
        held.forEach(this::onLampEvent);
    }

    private String fileOrMostRecent(String file) {
        List<Lamp.Conversation> all = Lamp.conversations(directory);
        if (all.stream().anyMatch(conversation -> conversation.file().equals(file))) return file;
        return all.isEmpty() ? "" : all.getFirst().file();
    }

    /// Shows the conversation `run` answers in, as it stood before its question.
    private void showAnswering(LampEvent.Run run) {
        Optional<Lamp.Conversation> conversation = run.conversation().flatMap(id -> Lamp.conversation(directory, id));
        String file = conversation.map(Lamp.Conversation::file).orElse("");
        String before = conversation.map(it -> entryBefore(it, run.prompt())).orElse("");
        where = conversation.isPresent() ? new Conversations.Here(file, before) : Conversations.Here.UNKNOWN;
        conversationOfRuns = where.file();
        PiEvent.History history = conversation.map(it -> LampTalk.history(it, before))
                .orElseGet(() -> new PiEvent.History(Tuple.of(PiEvent.History.Line.class), ""));
        changes.accept(genie -> genie.answering(file, history, run.prompt()));
    }

    /// The entry the question `prompt` follows in `conversation`: the one before it, once pi has
    /// written the question down, otherwise where the conversation stands. What pi wrote after
    /// the question is the run's, which is heard again.
    private static String entryBefore(Lamp.Conversation conversation, String prompt) {
        Tuple<Lamp.Conversation.Entry> line = conversation.line();
        for (int i = line.size() - 1; i >= 0; i--) {
            Lamp.Conversation.Entry entry = line.get(i);
            if (entry.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT && entry.text().strip().equals(prompt.strip()))
                return entry.parent().orElse("");
        }
        return conversation.leaf().orElse("");
    }

    /// Leaves the lamp running as the app closes, and waits until it is told.
    public void leaveRunningAndWait(long seconds) throws InterruptedException {
        if (work.isShutdown()) return;
        work.execute(() -> {
            sleeping = true;
            leftRunning = true;
            lamp.ifPresent(Lighter.Lit::leaveRunning);
            lamp = Optional.empty();
        });
        work.shutdown();
        work.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    /// Hands a question to the lamp's session, on a thread of its own: it starts a short-lived
    /// engine process, which takes a moment.
    private void sendQuestion(Lamp.Question question) {
        Optional<Lighter.Lit> lit = lamp;
        if (lit.isEmpty()) {
            showProblem("The genie is asleep; wake it first.");
            return;
        }
        promptBeingSent = Optional.of(question.prompt());
        conversationOfRuns = where.file();
        Thread.ofVirtual().name("send").start(() -> {
            try {
                runsOfThisChat.add(lit.get().send(question).id());
            } catch (IOException | Lamp.Failed failed) {
                changes.accept(genie -> genie.hear(new PiEvent.Refused("prompt", reasonOf(failed))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                promptBeingSent = Optional.empty();
            }
        });
    }

    // ─── what the lamp reports ─────────────────────────────────────────────────────────────

    /// Every event of the lamp's session, on the thread that reads them.
    private void onLampEvent(LampEvent event) {
        if (leftRunning) return;
        Optional<List<LampEvent>> held = heldWhileCatchingUp;
        if (held.isPresent()) {
            if (event instanceof LampEvent.AgentStatus status) {
                heldWhileCatchingUp = Optional.empty();
                caughtUp(status, held.get());
            } else {
                held.get().add(event);
            }
            return;
        }
        schedule.onLampEvent(event, Instant.now());
        switch (event) {
            case LampEvent.RunStarted started -> {
                LampEvent.Run run = started.run();
                if (run.job().isEmpty() && promptBeingSent.filter(run.prompt()::equals).isPresent()) runsOfThisChat.add(run.id());
                if (runsOfThisChat.contains(run.id())) runInProgress = Optional.of(run.id());
            }
            case LampEvent.RunProgress progress when runsOfThisChat.contains(progress.run()) -> {
                if (progress.progress() instanceof LampEvent.Progress.Answered) runsThatAnswered.add(progress.run());
                LampTalk.chatEventFor(progress.progress()).ifPresent(heard -> changes.accept(genie -> genie.hear(heard)));
            }
            case LampEvent.RunFinished finished when runsOfThisChat.contains(finished.run().id()) -> chatRunFinished(finished);
            // Another run, such as a scheduled job's, may have added to the conversations.
            case LampEvent.RunFinished ignored -> reloadConversations();
            case LampEvent.SessionStateChanged changed when changed.status().state().equals("stopped") && !sleeping -> {
                lamp = Optional.empty();
                schedule.wentOut();
                changes.accept(genie -> genie.broken("The genie's sandbox stopped: " + changed.status().detail()));
            }
            default -> { }
        }
    }

    /// A run of this chat ended: the chat learns where the conversation now stands, and pi's ids
    /// for what was said, and the genie is ready.
    private void chatRunFinished(LampEvent.RunFinished finished) {
        String run = finished.run().id();
        runInProgress = Optional.empty();
        runsOfThisChat.remove(run);
        if (!runsThatAnswered.remove(run) && finished.outcome() != LampEvent.RunOutcome.FINISHED && !finished.answer().isBlank())
            showProblem(finished.answer());
        changes.accept(genie -> genie.hear(new PiEvent.Settled()));
        Optional<Lamp.Conversation> conversation = finished.conversation().flatMap(id -> Lamp.conversation(directory, id));
        if (conversation.isPresent()) {
            String file = conversation.get().file();
            String leaf = conversation.get().leaf().orElse("");
            // The user may be reading another conversation; they stay there.
            if (where.file().equals(conversationOfRuns)) where = new Conversations.Here(file, leaf);
            PiEvent.History history = LampTalk.history(conversation.get(), leaf);
            changes.accept(genie -> genie.finishedIn(file, history));
        } else {
            changes.accept(genie -> genie.withConversations(genie.conversations().withAside(Optional.empty())));
        }
        checkOutbox();
        reloadConversations();
    }

    private void showProblem(String problem) {
        changes.accept(genie -> genie.withTranscript(genie.transcript().problem(problem)));
    }

    // ─── sleeping ──────────────────────────────────────────────────────────────────────────

    /// Puts the lamp out. The genie's home, and with it the conversations, stays.
    public void sleep() {
        work.execute(() -> {
            putOutLamp();
            changes.accept(Genie::asleep);
            reloadConversations();
        });
    }

    /// Sleeps and waits until the lamp is out, as when the app closes. Once is enough: after
    /// that, and after [#leaveRunningAndWait], it does nothing.
    public void sleepAndWait(long seconds) throws InterruptedException {
        if (work.isShutdown()) return;
        sleep();
        work.shutdown();
        work.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    public boolean isAwake() { return lamp.isPresent(); }

    /// The desktop's VNC socket, while the lamp is lit.
    public Optional<Path> desktop() { return lamp.map(Lighter.Lit::desktop); }

    private void putOutLamp() {
        schedule.wentOut();
        sleeping = true;
        lamp.ifPresent(Lighter.Lit::close);
        lamp = Optional.empty();
    }

    // ─── files ─────────────────────────────────────────────────────────────────────────────

    /// Looks into the outbox, and announces what is new.
    public void checkOutbox() {
        lamp.ifPresent(lit -> Thread.ofVirtual().name("outbox").start(() -> {
            try {
                var files = Handouts.list(lit);
                changes.accept(genie -> genie.outbox(files));
            } catch (IOException | InterruptedException stillThere) {
                // Looked for again after the next answer.
            }
        }));
    }

    /// Copies the outbox file `name` to `target`, which the user chose.
    public void saveOutboxFile(String name, Path target) {
        transfer("Saved " + name + " to " + target + ".", lit -> Handouts.fetch(lit, name, target));
    }

    /// Puts `file` into the genie's inbox, and tells the genie.
    public void giveFile(Path file) {
        String name = file.getFileName().toString();
        transfer("", lit -> {
            Handouts.give(lit, file);
            changes.accept(genie -> genie.gave(name));
        });
    }

    private interface Transfer { void run(Lighter.Lit lit) throws IOException, InterruptedException; }

    private void transfer(String done, Transfer transfer) {
        Optional<Lighter.Lit> lit = lamp;
        if (lit.isEmpty()) {
            showProblem("The genie is asleep; wake it first.");
            return;
        }
        Thread.ofVirtual().name("transfer").start(() -> {
            try {
                transfer.run(lit.get());
                if (!done.isEmpty()) changes.accept(genie -> genie.withTranscript(genie.transcript().notice(done)));
            } catch (IOException | InterruptedException failed) {
                showProblem(reasonOf(failed));
            }
        });
    }

    /// The genie's home in its lamp, once the lamp was first lit.
    private Optional<Path> home() { return Lamp.agentHome(directory); }

    private static String reasonOf(Exception failed) {
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
