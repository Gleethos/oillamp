package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
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
    /// Reads the genie's conversations, one reading after the other, so that an older reading
    /// never arrives after a newer one.
    private final ExecutorService looks = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("conversations").factory());
    private volatile Optional<Lighter.Lit> lamp = Optional.empty();
    private volatile boolean sleeping;

    /// Where the next message goes: the conversation's file, relative to the genie's home, and
    /// the entry it continues after. Both empty for a new conversation.
    private volatile Conversations.Here where = Conversations.Here.UNKNOWN;
    /// The runs that answer this chat's messages. Other runs, such as a scheduled job's, change
    /// only the tree of conversations.
    private final Set<String> mine = ConcurrentHashMap.newKeySet();
    /// The runs of `mine` that reported an answer, even a failed one.
    private final Set<String> answered = ConcurrentHashMap.newKeySet();
    /// The message being handed over, until [Lamp#send] returns. The run's first events can come
    /// before that, and are recognised by it.
    private volatile Optional<String> sending = Optional.empty();
    /// The run of `mine` in progress, which the stop button stops.
    private volatile Optional<String> working = Optional.empty();

    public GenieRunner(Path directory, Lighter lighter, Consumer<UnaryOperator<Genie>> changes) {
        this.directory = directory;
        this.lighter = lighter;
        this.changes = changes;
    }

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
                        what -> changes.accept(genie -> genie.lampSays(what)), this::heard);
                lamp = Optional.of(lit);
                changes.accept(genie -> genie.lampSays("waking the genie"));
                Handouts.makeDirectories(lit);
                GeniePrompt.prepare(home().orElseThrow(() -> new IOException("the genie's lamp has no home")),
                        name, settings.model());
                List<Lamp.Conversation> all = Lamp.conversations(directory);
                String file = !conversation.isEmpty() ? conversation : all.isEmpty() ? "" : all.getFirst().file();
                show(file, leaf);
                var files = Handouts.list(lit);
                changes.accept(genie -> genie.withHandouts(files).awake());
                lookAtConversations();
            } catch (IOException | RuntimeException failed) {
                putOut();
                changes.accept(genie -> genie.broken(reason(failed)));
            } catch (InterruptedException interrupted) {
                putOut();
                changes.accept(Genie::asleep);
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Sends the user's message, to where the chat is. While the genie still works on another
    /// run, it waits its turn.
    public void say(String text, boolean busy) {
        send(question(text));
    }

    /// Stops the run that answers this chat. What the genie did until then stays, and is saved.
    public void stop() {
        Optional<Lighter.Lit> lit = lamp;
        Optional<String> run = working;
        if (lit.isEmpty() || run.isEmpty()) return;
        Thread.ofVirtual().name("stop").start(() -> {
            try {
                lit.get().cancel(run.get());
            } catch (IOException | Lamp.Failed failed) {
                said("The genie could not be stopped: " + reason(failed));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ─── its conversations ─────────────────────────────────────────────────────────────────

    /// Shows a conversation, from its start to the entry `leaf`. The next message continues from
    /// there. Works while the genie sleeps.
    ///
    /// @param conversation its file relative to the genie's home
    /// @param leaf         the entry to continue after, or empty for where the conversation stands
    public void goTo(String conversation, String leaf) {
        work.execute(() -> show(conversation, leaf));
    }

    /// Starts a new conversation: the chat empties, and the next message begins it. The others stay.
    public void startAfresh() {
        work.execute(() -> show("", ""));
    }

    /// Asks `text` instead of the user's question `id`. The question and what followed it stay
    /// in the conversation, as a branch of their own.
    public void askInstead(String id, String text) {
        Optional<Lamp.Conversation> conversation = conversationIn(where.file());
        if (conversation.isEmpty()) {
            said("The conversation of that question is not there any more.");
            return;
        }
        send(Lamp.Question.insteadOf(conversation.get().id(), id, text));
    }

    /// Deletes a conversation for good. Deleting the one the chat shows starts a new one.
    ///
    /// @param conversation its file relative to the genie's home
    public void forget(String conversation) {
        work.execute(() -> {
            try {
                Optional<Lamp.Conversation> doomed = conversationIn(conversation);
                if (doomed.isEmpty()) throw new IOException("it is not there any more");
                if (where.file().equals(conversation)) show("", "");
                Lamp.forget(directory, doomed.get().id());
            } catch (IOException failed) {
                said("The conversation could not be deleted: " + reason(failed));
            }
            lookAtConversations();
        });
    }

    /// Reads the genie's conversations from its lamp again, awake or asleep.
    public void lookAtConversations() {
        looks.execute(() -> {
            Tuple<Conversation> all = Tuple.of(Conversation.class);
            for (Lamp.Conversation conversation : Lamp.conversations(directory)) all = all.add(LampTalk.conversation(conversation));
            Tuple<Conversation> read = all;
            changes.accept(genie -> genie.withConversations(genie.conversations().withAll(read)));
        });
    }

    /// Makes the chat show `file` up to `leaf`, and the next message go there. Empty `file` for a
    /// new conversation.
    private void show(String file, String leaf) {
        Optional<Lamp.Conversation> conversation = conversationIn(file);
        if (conversation.isEmpty()) {
            where = Conversations.Here.UNKNOWN;
            changes.accept(genie -> {
                Genie emptied = genie.hear(new PiEvent.History(Tuple.of(PiEvent.History.Line.class), ""));
                return emptied.withConversations(emptied.conversations().withHere(Conversations.Here.UNKNOWN));
            });
            return;
        }
        String at = conversation.get().entry(leaf).isPresent() ? leaf : conversation.get().leaf().orElse("");
        where = new Conversations.Here(file, at);
        PiEvent.History history = LampTalk.history(conversation.get(), at);
        changes.accept(genie -> genie.hear(new PiEvent.Opened(Conversations.HOME + file)).hear(history));
    }

    /// The conversation kept in `file`, relative to the genie's home.
    private Optional<Lamp.Conversation> conversationIn(String file) {
        if (file.isEmpty()) return Optional.empty();
        return Lamp.conversations(directory).stream().filter(c -> c.file().equals(file)).findFirst();
    }

    /// The next message's place: after the entry the chat shows, in the conversation it shows.
    private Lamp.Question question(String text) {
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

    /// Hands a question to the lamp's session, on a thread of its own: it starts a short-lived
    /// engine process, which takes a moment.
    private void send(Lamp.Question question) {
        Optional<Lighter.Lit> lit = lamp;
        if (lit.isEmpty()) {
            said("The genie is asleep; wake it first.");
            return;
        }
        sending = Optional.of(question.prompt());
        Thread.ofVirtual().name("send").start(() -> {
            try {
                mine.add(lit.get().send(question).id());
            } catch (IOException | Lamp.Failed failed) {
                changes.accept(genie -> genie.hear(new PiEvent.Refused("prompt", reason(failed))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                sending = Optional.empty();
            }
        });
    }

    // ─── what the lamp reports ─────────────────────────────────────────────────────────────

    /// Every event of the lamp's session, on the thread that reads them.
    private void heard(LampEvent event) {
        switch (event) {
            case LampEvent.RunStarted started -> {
                LampEvent.Run run = started.run();
                if (run.job().isEmpty() && sending.filter(run.prompt()::equals).isPresent()) mine.add(run.id());
                if (mine.contains(run.id())) working = Optional.of(run.id());
            }
            case LampEvent.RunProgress progress when mine.contains(progress.run()) -> {
                if (progress.progress() instanceof LampEvent.Progress.Answered) answered.add(progress.run());
                LampTalk.heard(progress.progress()).ifPresent(heard -> changes.accept(genie -> genie.hear(heard)));
            }
            case LampEvent.RunFinished finished when mine.contains(finished.run().id()) -> finished(finished);
            // Another run, such as a scheduled job's, may have added to the conversations.
            case LampEvent.RunFinished ignored -> lookAtConversations();
            case LampEvent.SessionStateChanged changed when changed.status().state().equals("stopped") && !sleeping -> {
                lamp = Optional.empty();
                changes.accept(genie -> genie.broken("The genie's sandbox stopped: " + changed.status().detail()));
            }
            default -> { }
        }
    }

    /// A run of this chat ended: the chat learns where the conversation now stands, and pi's ids
    /// for what was said, and the genie is ready.
    private void finished(LampEvent.RunFinished finished) {
        String run = finished.run().id();
        working = Optional.empty();
        mine.remove(run);
        if (!answered.remove(run) && finished.outcome() != LampEvent.RunOutcome.FINISHED && !finished.answer().isBlank())
            said(finished.answer());
        changes.accept(genie -> genie.hear(new PiEvent.Settled()));
        Optional<Lamp.Conversation> conversation = finished.conversation().flatMap(id -> Lamp.conversation(directory, id));
        if (conversation.isPresent()) {
            String file = conversation.get().file();
            String leaf = conversation.get().leaf().orElse("");
            where = new Conversations.Here(file, leaf);
            PiEvent.History history = LampTalk.history(conversation.get(), leaf);
            changes.accept(genie -> genie.hear(new PiEvent.Opened(Conversations.HOME + file)).learn(history));
        }
        checkOutbox();
        lookAtConversations();
    }

    private void said(String problem) {
        changes.accept(genie -> genie.withTranscript(genie.transcript().problem(problem)));
    }

    // ─── sleeping ──────────────────────────────────────────────────────────────────────────

    /// Puts the lamp out. The genie's home, and with it the conversations, stays.
    public void sleep() {
        work.execute(() -> {
            putOut();
            changes.accept(Genie::asleep);
            lookAtConversations();
        });
    }

    /// Sleeps and waits until the lamp is out, as when the app closes.
    public void sleepAndWait(long seconds) throws InterruptedException {
        sleep();
        work.shutdown();
        work.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    public boolean isAwake() { return lamp.isPresent(); }

    /// The desktop's VNC socket, while the lamp is lit.
    public Optional<Path> desktop() { return lamp.map(Lighter.Lit::desktop); }

    private void putOut() {
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
    public void save(String name, Path target) {
        transfer("Saved " + name + " to " + target + ".", lit -> Handouts.fetch(lit, name, target));
    }

    /// Puts `file` into the genie's inbox, and tells the genie.
    public void give(Path file) {
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
            said("The genie is asleep; wake it first.");
            return;
        }
        Thread.ofVirtual().name("transfer").start(() -> {
            try {
                transfer.run(lit.get());
                if (!done.isEmpty()) changes.accept(genie -> genie.withTranscript(genie.transcript().notice(done)));
            } catch (IOException | InterruptedException failed) {
                said(reason(failed));
            }
        });
    }

    /// The genie's home in its lamp, once the lamp was first lit.
    private Optional<Path> home() { return Lamp.agentHome(directory); }

    private static String reason(Exception failed) {
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
