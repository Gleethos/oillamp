package dev.gui;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;


import dev.gui.genie.GenieRunner;
import dev.gui.genie.LampLighter;
import dev.gui.genie.Lighter;
import dev.gui.genie.ModelCatalog;
import dev.gui.genie.Shelf;
import dev.gui.model.Conversation;
import dev.gui.model.Conversations;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Settings;
import dev.gui.view.Actions;
import dev.gui.view.GeniesView;
import dev.lamp.ExitStatus;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;

/// Genies: a chat app whose every conversation partner is an AI agent with a sandboxed Linux
/// desktop of its own, a "genie" living in an oillamp lamp.
///
/// It is also what oillamp's embedding API, [Lamp], was made for, used the way any application
/// would use it: this package imports `dev.lamp` and never `dev.oillamp`.
///
/// This class holds the app together. The window ([GeniesView]) draws one [GeniesState] and
/// asks for everything else through [Actions]; each genie's [GenieRunner] reports what happens
/// as changes to its genie, which are applied here, on Swing's event thread, one at a time.
public final class Genies implements Actions {

    private final Var<GeniesState> state;
    private final Shelf shelf;
    private final Lighter lighter;
    private final Map<UUID, GenieRunner> runners = new ConcurrentHashMap<>();
    /// Each genie's id and name. Kept as a field: when it changes, the genies are put on the
    /// shelf, and a view nobody holds would be forgotten.
    private final Val<Tuple<String>> names;
    /// The page on show. Kept as a field for the same reason: leaving the settings keeps them.
    private final Val<GeniesState.Page> page;

    Genies(Var<GeniesState> state, Shelf shelf, Lighter lighter) {
        this.state = state;
        this.shelf = shelf;
        this.lighter = lighter;
        this.names = state.viewAs(Tuple.classTyped(String.class),
                it -> it.genies().mapTo(String.class, genie -> genie.id() + " " + genie.name()));
        Viewable.cast(names).onChange(From.ALL, it -> keepGenies());
        // The settings are kept however the user leaves them: with Done, or by clicking a genie.
        this.page = state.viewAs(GeniesState.Page.class, GeniesState::page);
        Viewable.cast(page).onChange(From.ALL, it -> {
            if (it.currentValue().orElseNull() != GeniesState.Page.SETTINGS) keepSettings();
        });
    }

    public static void main(String[] args) {
        GeniesView.setUpLook();
        SwingUtilities.invokeLater(Genies::open);
    }

    private static void open() {
        Shelf shelf = Shelf.standard(name -> Optional.ofNullable(System.getenv(name)));
        Optional<String> environmentKey = Optional.ofNullable(System.getenv(Settings.KEY_VARIABLE)).filter(key -> !key.isBlank());
        Genies app = new Genies(Var.of(GeniesState.of(shelf.genies(), shelf.settings(), environmentKey)),
                                shelf, new LampLighter());
        JFrame frame = new JFrame("Genies");
        frame.setContentPane(new GeniesView(app.state, app));
        frame.setIconImage(GeniesView.windowIcon());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { app.quit(frame); }
        });
        // The tree of conversations under each genie is there before any genie wakes.
        app.state.get().genies().forEach(genie -> app.runner(genie.id()).lookAtConversations());
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ─── actions ───────────────────────────────────────────────────────────────────────────

    /// A new genie wakes at once, as a new chat is ready at once elsewhere.
    @Override public void newGenie() {
        Genie genie = Genie.named(state.get().freshName());
        state.update(From.VIEW, it -> it.add(genie));
        wake(genie.id());
    }

    @Override public void wake(UUID id) {
        GeniesState now = state.get();
        Optional<String> problem = now.settingsProblem();
        Optional<String> key = now.settings().keyFrom(now.environmentKey());
        if (problem.isPresent() || key.isEmpty()) {
            String why = problem.orElse("There is no model key.");
            state.update(From.VIEW, it -> it.update(id, genie -> genie.withTranscript(genie.transcript().problem(why)))
                                             .withPage(GeniesState.Page.SETTINGS));
            return;
        }
        // Where the genie was, if the user moved since it last woke; otherwise its last conversation.
        now.find(id).ifPresent(genie -> runner(id).wake(genie.name(), now.settings(), key.get(),
                genie.conversations().current().map(Conversation::file).orElse(""),
                genie.conversations().current().isPresent() ? genie.conversations().here().leaf() : ""));
    }

    @Override public void goTo(UUID id, String conversation, String leaf) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id));
        switch (genie.get().phase()) {
            case READY -> runner(id).goTo(conversation, leaf);
            case ASLEEP, BROKEN -> {
                // Wakes into that conversation, and moves within it once awake.
                state.update(From.VIEW, it -> it.update(id, sleeping -> sleeping.withConversations(
                        sleeping.conversations().withHere(new Conversations.Here(conversation, leaf)))));
                wake(id);
            }
            case WAKING, WORKING -> { }   // The tree does not move while the genie is busy.
        }
    }

    @Override public void startAfresh(UUID id) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id));
        if (genie.get().phase() == Genie.Phase.ASLEEP || genie.get().phase() == Genie.Phase.BROKEN) wake(id);
        // Waking and this take turns on the runner's thread, so this waits until the genie is awake.
        if (genie.get().phase() != Genie.Phase.WORKING) runner(id).startAfresh();
    }

    @Override public void forget(UUID id, String conversation) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        // A sleeping genie that was in it wakes in its newest conversation instead; an awake one
        // is moved to a new conversation by its runner first.
        if (!genie.get().phase().isAwake() && genie.get().conversations().here().file().equals(conversation))
            state.update(From.VIEW, it -> it.update(id, sleeping -> sleeping.withConversations(
                    sleeping.conversations().withHere(Conversations.Here.UNKNOWN))));
        runner(id).forget(conversation);
    }

    @Override public void askInstead(String question, String text) {
        Genie genie = state.get().genie();
        if (text.isBlank() || genie.phase() != Genie.Phase.READY) return;
        GenieRunner runner = runner(genie.id());
        if (!runner.canMove()) {
            state.update(From.VIEW, it -> it.update(genie.id(), g -> g.withTranscript(g.transcript().problem(GenieRunner.CANNOT_MOVE))));
            return;
        }
        state.update(From.VIEW, it -> it.update(genie.id(), g -> g.askInstead(question, text)));
        runner.askInstead(question, text.strip());
    }

    @Override public void sleep(UUID id) {
        Optional.ofNullable(runners.get(id)).ifPresent(GenieRunner::sleep);
    }

    /// Waits one turn of Swing's event thread first: a text field hands its latest text to the
    /// draft on the next turn, so a message sent in the same turn as the last keystroke or a
    /// paste would lose it.
    @Override public void send() {
        SwingUtilities.invokeLater(this::sendDraft);
    }

    private void sendDraft() {
        Genie genie = state.get().genie();
        if (!genie.canSend()) return;
        String text = genie.draft().strip();
        boolean busy = genie.phase() == Genie.Phase.WORKING;
        state.update(From.VIEW, it -> it.update(genie.id(), Genie::send));
        runner(genie.id()).say(text, busy);
    }

    @Override public void stop(UUID id) {
        Optional.ofNullable(runners.get(id)).ifPresent(GenieRunner::stop);
    }

    /// The genie leaves the window at once; its lamp is put out and removed in the background.
    @Override public void delete(UUID id) {
        GenieRunner runner = runners.remove(id);
        state.update(From.VIEW, it -> it.remove(id));
        Path lamp = shelf.lampOf(id);
        Thread.ofVirtual().name("delete genie").start(() -> {
            try {
                if (runner != null) runner.sleepAndWait(180);
                if (!Files.exists(lamp)) return;
                StringBuilder why = new StringBuilder();
                ExitStatus removed = Lamp.at(lamp).onEvent(event -> {
                    if (event instanceof LampEvent.Failure failure) why.append(failure.problem().whatHappened());
                }).remove();
                if (removed != ExitStatus.SUCCESS)
                    System.err.println("genies: could not remove the lamp at " + lamp + ": " + why);
            } catch (IOException | InterruptedException failed) {
                System.err.println("genies: could not remove the lamp at " + lamp + ": " + failed.getMessage());
            }
        });
    }

    @Override public void give(UUID id, Path file) { runner(id).give(file); }

    @Override public void save(UUID id, String name, Path target) { runner(id).save(name, target); }

    /// Leaving the page keeps the settings.
    @Override public void settingsDone() {
        state.update(From.VIEW, it -> it.withPage(GeniesState.Page.CHAT));
    }

    /// Asks the model server on this computer for its models, off Swing's event thread.
    @Override public void lookUpModels() {
        String address = state.get().settings().local().address();
        state.update(From.VIEW, it -> it.withLookUp(new GeniesState.ModelLookUp(it.lookUp().models(), "Asking the model server…")));
        Thread.ofVirtual().name("look up models").start(() -> {
            try {
                Tuple<String> found = ModelCatalog.ofServerAt(address);
                SwingUtilities.invokeLater(() -> state.update(it -> it.modelsFound(found)));
            } catch (IOException | IllegalArgumentException failed) {
                String why = Optional.ofNullable(failed.getMessage()).orElse(failed.toString());
                SwingUtilities.invokeLater(() -> state.update(it -> it.modelsNotFound(why)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override public Optional<Path> desktopOf(UUID id) {
        return Optional.ofNullable(runners.get(id)).flatMap(GenieRunner::desktop);
    }

    // ─── the rest ──────────────────────────────────────────────────────────────────────────

    private GenieRunner runner(UUID id) {
        return runners.computeIfAbsent(id, genie -> new GenieRunner(shelf.lampOf(genie), lighter,
                change -> SwingUtilities.invokeLater(() -> state.update(it -> it.update(genie, change)))));
    }

    private void keepSettings() {
        try {
            shelf.keep(state.get().settings());
        } catch (IOException failed) {
            System.err.println("genies: could not keep the settings in " + shelf.root() + ": " + failed.getMessage());
        }
    }

    private void keepGenies() {
        try {
            shelf.keep(state.get().genies());
        } catch (IOException failed) {
            System.err.println("genies: could not keep the genies in " + shelf.root() + ": " + failed.getMessage());
        }
    }

    /// Puts every genie to sleep before the window goes, so no sandbox is left running. (If
    /// Genies is killed instead, the lamps end by themselves: each notices its application is
    /// gone.)
    private void quit(JFrame frame) {
        frame.setTitle("Genies — putting the genies to sleep…");
        keepGenies();
        keepSettings();
        Thread.ofVirtual().name("quit").start(() -> {
            for (GenieRunner runner : runners.values()) {
                try {
                    runner.sleepAndWait(180);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            SwingUtilities.invokeLater(() -> {
                frame.dispose();
                System.exit(0);
            });
        });
    }
}
