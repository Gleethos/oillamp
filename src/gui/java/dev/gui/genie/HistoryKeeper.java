package dev.gui.genie;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Genie;
import dev.gui.model.History;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

/// Reads a genie's history from its lamp, and saves its home there as a new moment, awake or
/// asleep. Going back to a moment is [GenieRunner#goBackTo], which puts the lamp out first.
///
/// Each call starts an engine process that ends by itself, which takes a moment, so the work is
/// done one piece after the other on a thread of its own, and the calls return at once.
public final class HistoryKeeper {

    private final Lamp.Starting lamp;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("history").factory());
    /// Whether a reading waits its turn already; one reading then covers every change before it.
    private final AtomicBoolean readingQueued = new AtomicBoolean();
    /// Whether the history was ever asked for. Until then, nothing shows it, and news from the
    /// lamp does not make the keeper read it.
    private volatile boolean watched;

    /// @param lamp the genie's lamp, unlit: [Lighter#unlit]
    public HistoryKeeper(Lamp.Starting lamp, Consumer<UnaryOperator<Genie>> changes) {
        this.lamp = lamp;
        this.changes = changes;
    }

    public void read() {
        watched = true;
        if (!readingQueued.compareAndSet(false, true)) return;
        work.execute(() -> {
            readingQueued.set(false);
            try {
                var moments = LampApiConversionUtil.moments(lamp.history());
                change(it -> it.readAs(moments));
            } catch (IOException | Lamp.Failed failed) {
                change(it -> it.withProblem("The history could not be read: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Saves the genie's home as a new moment, which is then picked. When nothing changed since
    /// the newest moment, oillamp saves nothing; the newest moment is picked, and the note says why.
    /// The chat says what came of it too, since the user may have saved from there.
    ///
    /// @param message what to remember the moment by; may be empty
    public void save(String message) {
        change(it -> it.withBusy("Saving…").withNote("").withProblem(""));
        work.execute(() -> {
            try {
                Optional<LampEvent.Snapshot> saved = lamp.save(message.strip());
                String nothingChanged = "Nothing changed since the newest moment, so it holds how the genie is now already.";
                change(it -> saved.isPresent() ? it.withBusy("").withPicked(saved.get().id())
                        : it.withBusy("").withPicked(it.moments().isEmpty() ? "" : it.moments().first().id()).withNote(nothingChanged));
                String told = saved.isEmpty() ? nothingChanged
                        : "Saved as a moment of the history" + (message.isBlank() ? "" : ": " + message.strip())
                          + ". The history page brings the genie back to it.";
                changes.accept(genie -> genie.withTranscript(genie.transcript().notice(told)));
            } catch (IOException | Lamp.Failed failed) {
                change(it -> it.withBusy("").withProblem("oillamp could not save: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        read();
    }

    /// While the genie is awake: a save, or a run's end, which saves too, adds a moment.
    void onLampEvent(LampEvent event) {
        switch (event) {
            case LampEvent.Saved ignored -> { if (watched) read(); }
            case LampEvent.RunFinished ignored -> { if (watched) read(); }
            default -> { }
        }
    }

    /// The genie's lamp went out, and saved its home as it did.
    void wentOut() {
        if (watched) read();
    }

    private void change(UnaryOperator<History> change) {
        changes.accept(genie -> genie.withHistory(change.apply(genie.history())));
    }

    private static String reason(Exception failed) {
        if (failed instanceof Lamp.Failed refused) return refused.problem().whatHappened();
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
