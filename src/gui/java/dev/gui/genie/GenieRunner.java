package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Genie;
import dev.gui.model.Settings;
import dev.gui.pi.PiEvent;
import dev.gui.pi.PiProtocol;
import dev.gui.pi.PiSession;

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
    /// Counted down when pi has sent the conversation so far, which is when the genie is awake.
    private volatile java.util.concurrent.CountDownLatch historyBack = new java.util.concurrent.CountDownLatch(0);

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
                PiSession session = PiSession.over(lit.exec(GeniePrompt.harness(name, settings.model())),
                        this::heard, why -> why.ifPresent(this::harnessStopped));
                harness = Optional.of(session);
                // The genie is awake only once pi has sent the conversation back. pi answers
                // commands in turn, and takes a few seconds to start: a message the user sent
                // before would be followed by the older history, which replaces the chat.
                historyBack = new java.util.concurrent.CountDownLatch(1);
                session.send(PiProtocol.askForHistory());
                if (!historyBack.await(2, TimeUnit.MINUTES))
                    throw new IOException("The genie's harness did not answer within two minutes");
                if (harnessDied) return;   // it said why, and its lamp is being put out
                Tuple<dev.gui.model.Handout> files = Handouts.list(lit);
                changes.accept(genie -> genie.withHandouts(files).awake());
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

    /// Ends the harness and the lamp. The genie's home, and with it the conversation, stays.
    public void sleep() {
        work.execute(() -> {
            putOut();
            changes.accept(Genie::asleep);
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
        changes.accept(genie -> genie.hear(event));
        if (event instanceof PiEvent.History) historyBack.countDown();
        if (event instanceof PiEvent.Settled) checkOutbox();
    }

    /// The harness ended without being asked to: its sandbox went away, or it failed. The
    /// lamp is put out too, so that waking the genie again starts from a clean state.
    private void harnessStopped(String why) {
        harnessDied = true;
        historyBack.countDown();
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
