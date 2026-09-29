package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Conversation;
import dev.gui.model.Conversations;
import dev.gui.model.Genie;
import dev.gui.model.Settings;
import dev.gui.pi.PiEvent;
import dev.gui.pi.PiProtocol;
import dev.gui.pi.PiSession;
import dev.lamp.Lamp;

import sprouts.Tuple;

/// Keeps one genie alive: lights its lamp, runs its harness in the sandbox, and passes files.
///
/// It decides nothing about what the window shows. Everything that happens becomes a change to
/// the genie, `UnaryOperator<Genie>`, handed to `changes`; the app applies it to its state. So
/// the runner can be followed in a scenario by applying the changes to a plain [Genie].
///
/// Waking and sleeping take long, so they run one after the other on a thread of the runner's
/// own; the calls return at once.
public final class GenieRunner {

    private final Path directory;
    private final Lighter lighter;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("genie").factory());
    private volatile Optional<Lighter.Lit> lamp = Optional.empty();
    private volatile Optional<PiSession> harness = Optional.empty();
    private volatile boolean harnessDied;
    /// pi's answers to what the runner asked it, while it waits for one. pi works on commands
    /// side by side, so a move is a chain of questions, each asked once the last was answered.
    private final BlockingQueue<PiEvent> answers = new LinkedBlockingQueue<>();
    private volatile boolean asking;
    /// Whether pi runs with Genies' extension, and so can move within a conversation.
    private volatile boolean canMove;
    /// The conversation pi has open, relative to the genie's home, as it last said.
    private volatile String opened = "";
    /// Reads the genie's conversations from its home, one reading after the other, so that an
    /// older reading never arrives after a newer one.
    private final ExecutorService looks = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("conversations").factory());

    /// How long pi has to answer a question about its conversations.
    private static final Duration ANSWER_LIMIT = Duration.ofSeconds(30);

    /// Said in the chat when the genie's sandbox has no extension to move with.
    public static final String CANNOT_MOVE = "This genie's sandbox is older than Genies, and cannot go to another branch "
            + "yet. Put the genie to sleep and wake it: its sandbox is then rebuilt, which takes a few minutes.";

    public GenieRunner(Path directory, Lighter lighter, Consumer<UnaryOperator<Genie>> changes) {
        this.directory = directory;
        this.lighter = lighter;
        this.changes = changes;
    }

    /// The genie's lamp directory.
    public Path directory() { return directory; }

    /// Lights the lamp and starts the genie's harness in it. The genie is awake once that is
    /// done, and has its conversation back.
    ///
    /// @param key the model key, from the settings or the environment; the lamp's engine keeps it
    public void wake(String name, Settings settings, String key) {
        wake(name, settings, key, "", "");
    }

    /// Wakes the genie in one of its conversations, where it left it.
    ///
    /// @param conversation the conversation's session file relative to the genie's home, or
    ///                     nothing to continue the one pi wrote to last
    /// @param leaf         the entry to continue after, or nothing to continue at its end
    public void wake(String name, Settings settings, String key, String conversation, String leaf) {
        work.execute(() -> {
            if (lamp.isPresent()) return;
            harnessDied = false;
            changes.accept(Genie::waking);
            try {
                Lighter.Lit lit = lighter.light(directory, settings, key,
                        what -> changes.accept(genie -> genie.lampSays(what)));
                lamp = Optional.of(lit);
                changes.accept(genie -> genie.lampSays("waking the genie"));
                Handouts.makeDirectories(lit);
                String opening = conversation.isEmpty() ? "" : Conversations.HOME + conversation;
                PiSession session = PiSession.over(lit.exec(GeniePrompt.harness(name, settings.model(), opening)),
                        this::heard, why -> why.ifPresent(this::harnessStopped));
                harness = Optional.of(session);
                // The genie is awake only once pi has sent the conversation back. pi takes a
                // few seconds to start: a message the user sent before would be followed by the
                // older history, which replaces the chat.
                Optional<PiEvent> history = ask(PiProtocol.askForHistory(), PiEvent.History.class, Duration.ofMinutes(2));
                if (harnessDied) return;   // it said why, and its lamp is being put out
                if (history.isEmpty()) throw new IOException("The genie's harness did not answer within two minutes");
                canMove = ask(PiProtocol.askWhatItCanDo(), PiEvent.CanMove.class, ANSWER_LIMIT)
                        .map(answer -> answer instanceof PiEvent.CanMove can && can.yes()).orElse(false);
                ask(PiProtocol.askWhere(), PiEvent.Opened.class, ANSWER_LIMIT);
                if (!leaf.isEmpty() && history.get() instanceof PiEvent.History at && !at.leaf().equals(leaf) && canMove) {
                    moveWithin(leaf);
                    ask(PiProtocol.askForHistory(), PiEvent.History.class, ANSWER_LIMIT);
                }
                Tuple<dev.gui.model.Handout> files = Handouts.list(lit);
                changes.accept(genie -> genie.withHandouts(files).awake());
                lookAtConversations();
            } catch (IOException | RuntimeException failed) {
                putOut();
                // A harness that died while the genie woke has said why already, and the
                // failures after it are only its consequences.
                if (!harnessDied) changes.accept(genie -> genie.broken(reason(failed)));
            } catch (InterruptedException interrupted) {
                putOut();
                changes.accept(Genie::asleep);
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Sends the user's message. While the genie still works on the last one, it waits.
    public void say(String text, boolean busy) {
        harness.ifPresentOrElse(session -> session.send(PiProtocol.prompt(text, busy)),
                () -> changes.accept(genie -> genie.withTranscript(genie.transcript().problem("The genie is asleep; wake it first."))));
    }

    /// Stops what the genie is doing. Its conversation stays.
    public void stop() {
        harness.ifPresent(session -> session.send(PiProtocol.abort()));
    }

    // ─── its conversations ─────────────────────────────────────────────────────────────────

    /// Whether the genie can go to another branch of a conversation, or ask a question
    /// differently: it runs with Genies' extension to pi.
    public boolean canMove() { return canMove; }

    /// Goes to a conversation, and within it to the entry `leaf`, and the chat shows it.
    ///
    /// @param conversation its session file relative to the genie's home
    /// @param leaf         the entry to continue after, or nothing for where pi left it
    public void goTo(String conversation, String leaf) {
        work.execute(() -> {
            if (!isAwake()) return;   // it did not wake, and has said why
            try {
                if (!conversation.equals(opened)
                        && !(ask(PiProtocol.open(Conversations.HOME + conversation), PiEvent.Switched.class, ANSWER_LIMIT)
                             .orElse(null) instanceof PiEvent.Switched)) {
                    said("The genie did not open that conversation.");
                    return;
                }
                if (!leaf.isEmpty()) {
                    if (canMove) moveWithin(leaf);
                    else said(CANNOT_MOVE);
                }
                showWherePiIs();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Starts a new conversation. The others stay, for the user to go back to.
    public void startAfresh() {
        work.execute(() -> {
            if (!isAwake()) return;   // it did not wake, and has said why
            try {
                if (ask(PiProtocol.startAfresh(), PiEvent.Switched.class, ANSWER_LIMIT).orElse(null) instanceof PiEvent.Switched)
                    showWherePiIs();
                else said("The genie did not start a new conversation.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Asks `text` instead of the user's question `id`. The question and what followed it stay
    /// in the conversation, as a branch of their own.
    public void askInstead(String id, String text) {
        harness.ifPresentOrElse(session -> session.send(PiProtocol.askInstead(id, text)),
                () -> said("The genie is asleep; wake it first."));
    }

    /// Deletes a conversation for good. Deleting the one the genie is in starts a new one first.
    ///
    /// @param conversation its session file relative to the genie's home
    public void forget(String conversation) {
        work.execute(() -> {
            try {
                if (isAwake() && conversation.equals(opened)) {
                    if (!(ask(PiProtocol.startAfresh(), PiEvent.Switched.class, ANSWER_LIMIT).orElse(null) instanceof PiEvent.Switched)) {
                        said("The genie is still in that conversation, so it was kept.");
                        return;
                    }
                    showWherePiIs();
                }
                SessionFiles.delete(home().orElseThrow(() -> new IOException("the genie has no home yet")), conversation);
            } catch (IOException failed) {
                said("The conversation could not be deleted: " + reason(failed));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            lookAtConversations();
        });
    }

    /// Reads the genie's conversations from its home again, awake or asleep.
    public void lookAtConversations() {
        looks.execute(() -> {
            Tuple<Conversation> all = home().map(SessionFiles::read).orElseGet(() -> Tuple.of(Conversation.class));
            changes.accept(genie -> genie.withConversations(genie.conversations().withAll(all)));
        });
    }

    /// The genie's home in its lamp, once the lamp was first lit.
    private Optional<Path> home() { return Lamp.agentHome(directory); }

    /// Moves pi to the entry `leaf` of the conversation it has open. Only with the extension.
    private void moveWithin(String leaf) throws InterruptedException {
        if (ask(PiProtocol.goTo(leaf), PiEvent.Moved.class, ANSWER_LIMIT).isEmpty())
            said("The genie did not go there in time.");
    }

    /// Asks pi where it is now and what was said on the way there, for the chat and the tree.
    private void showWherePiIs() throws InterruptedException {
        ask(PiProtocol.askWhere(), PiEvent.Opened.class, ANSWER_LIMIT);
        ask(PiProtocol.askForHistory(), PiEvent.History.class, ANSWER_LIMIT);
        lookAtConversations();
    }

    /// Sends `command` and waits for pi's answer, of the kind `answer`, or for it to refuse.
    /// Nothing, if it did not answer within `limit` or its harness is gone.
    private Optional<PiEvent> ask(String command, Class<? extends PiEvent> answer, Duration limit) throws InterruptedException {
        Optional<PiSession> session = harness;
        if (session.isEmpty() || harnessDied) return Optional.empty();
        answers.clear();
        asking = true;
        try {
            session.get().send(command);
            long deadline = System.nanoTime() + limit.toNanos();
            while (!harnessDied) {
                PiEvent heard = answers.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (heard == null) return Optional.empty();
                if (answer.isInstance(heard) || heard instanceof PiEvent.Refused) return Optional.of(heard);
            }
            return Optional.empty();
        } finally {
            asking = false;
        }
    }

    private void said(String problem) {
        changes.accept(genie -> genie.withTranscript(genie.transcript().problem(problem)));
    }

    /// Ends the harness and the lamp. The genie's home, and with it the conversation, stays.
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

    public boolean isAwake() { return lamp.isPresent() && harness.isPresent(); }

    /// The desktop's VNC socket, while the lamp is lit.
    public Optional<Path> desktop() { return lamp.map(Lighter.Lit::desktop); }

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
            changes.accept(genie -> genie.withTranscript(genie.transcript().problem("The genie is asleep; wake it first.")));
            return;
        }
        Thread.ofVirtual().name("transfer").start(() -> {
            try {
                transfer.run(lit.get());
                if (!done.isEmpty()) changes.accept(genie -> genie.withTranscript(genie.transcript().notice(done)));
            } catch (IOException | InterruptedException failed) {
                changes.accept(genie -> genie.withTranscript(genie.transcript().problem(reason(failed))));
            }
        });
    }

    private void heard(PiEvent event) {
        // The conversation pi sends unasked, after an answer, is only learnt from: the chat
        // shows more of that answer than pi sends.
        if (event instanceof PiEvent.History history && !asking) changes.accept(genie -> genie.learn(history));
        else changes.accept(genie -> genie.hear(event));
        if (event instanceof PiEvent.Opened open) opened = Conversations.inHome(open.file());
        if (asking) answers.offer(event);
        if (event instanceof PiEvent.Settled) {
            checkOutbox();
            // pi's ids for the questions just asked, and the tree, which has grown.
            harness.ifPresent(session -> session.send(PiProtocol.askForHistory()));
            lookAtConversations();
        }
    }

    /// The harness ended without being asked to: its sandbox went away, or it failed. The
    /// lamp is put out too, so that waking the genie again starts from a clean state.
    private void harnessStopped(String why) {
        harnessDied = true;
        answers.offer(new PiEvent.Refused("harness", why));
        harness = Optional.empty();
        changes.accept(genie -> genie.broken(why));
        work.execute(this::putOut);
    }

    private void putOut() {
        harness.ifPresent(PiSession::close);
        harness = Optional.empty();
        lamp.ifPresent(Lighter.Lit::close);
        lamp = Optional.empty();
    }

    private static String reason(Exception failed) {
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
