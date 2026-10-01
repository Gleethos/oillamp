package dev.gui;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.gui.genie.GenieRunner;
import dev.gui.genie.LampLighter;
import dev.gui.genie.Lighter;
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

import swingtree.UI;
import swingtree.dialogs.ConfirmAnswer;

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

    /// How long closing or deleting waits for a genie's lamp to go out or be let go.
    private static final long SECONDS_TO_LET_GO_OF_A_LAMP = 180;

    private final Var<GeniesState> state;
    private final Shelf shelf;
    private final Lighter lighter;
    private final Map<UUID, GenieRunner> runners = new ConcurrentHashMap<>();
    /// Each genie's id and name. Kept as a field: when it changes, the genies are put on the
    /// shelf, and a view nobody holds would be forgotten.
    private final Val<Tuple<String>> names;
    /// The page on show. Kept as a field for the same reason: leaving the settings keeps them.
    private final Val<GeniesState.Page> page;
    /// The place the settings on show name, or nothing while they are not on show. Kept as a
    /// field for the same reason: opening the settings, or choosing another place, asks that
    /// place for its models.
    private final Val<String> placeShown;
    /// The genie whose schedule is on show, or [GeniesState#NONE]. Kept as a field for the same
    /// reason: a schedule is read from its lamp when it comes on show.
    private final Val<UUID> scheduleShown;

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
        this.placeShown = state.viewAsString(it -> it.page() == GeniesState.Page.SETTINGS ? it.settings().place().name() : "");
        Viewable.cast(placeShown).onChange(From.ALL, it -> {
            if (!it.currentValue().orElse("").isEmpty()) lookUpModels();
        });
        this.scheduleShown = state.viewAs(UUID.class, it -> it.page() == GeniesState.Page.SCHEDULE ? it.selected() : GeniesState.NONE);
        Viewable.cast(scheduleShown).onChange(From.ALL, it -> {
            UUID shown = it.currentValue().orElse(GeniesState.NONE);
            if (!shown.equals(GeniesState.NONE)) runner(shown).schedule().read();
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
        // The tree of conversations under each genie is there before any genie wakes, and a genie
        // left running when Genies last closed is awake again.
        app.state.get().genies().forEach(genie -> {
            app.runner(genie.id()).reloadConversations();
            app.rejoin(genie.id());
        });
        // The timeline, and anything else said relative to now, moves on with the clock.
        new Timer(30_000, tick -> app.state.update(it -> it.withNow(Instant.now()))).start();
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /// What the user is asked on closing Genies with `awake`, the names of the genies awake. On two
    /// lines, since a dialog does not wrap its text.
    static String awakeQuestion(List<String> awake) {
        boolean one = awake.size() == 1;
        String names = one ? awake.getFirst()
                : String.join(", ", awake.subList(0, awake.size() - 1)) + " and " + awake.getLast();
        String them = one ? "it" : "them";
        return names + (one ? " is" : " are") + " awake. Put " + them + " to sleep, or keep " + them
                + " running in the background?\nKept running, "
                + (one ? "it finishes what it is doing and keeps to its" : "they finish what they are doing and keep to their")
                + " schedule, and Genies finds " + them + " again when it opens.";
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
        now.find(id).ifPresent(genie -> runner(id).wake(genie.name(), now.settings(), key.get(),
                conversationShown(genie), leafShown(genie)));
    }

    @Override public void goTo(UUID id, String conversation, String leaf) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id));
        switch (genie.get().phase()) {
            // Conversations are read from the lamp, so a sleeping genie's can be read too, and a
            // working genie's answer is kept aside while the user reads another.
            case READY, WORKING, ASLEEP, BROKEN -> runner(id).goToConversation(conversation, leaf);
            case WAKING -> { }   // The tree does not move while the genie wakes.
        }
    }

    @Override public void startAfresh(UUID id) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id));
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
        runner(id).forgetConversation(conversation);
    }

    @Override public void askInstead(String question, String text) {
        Genie genie = state.get().genie();
        if (text.isBlank() || genie.phase() != Genie.Phase.READY) return;
        state.update(From.VIEW, it -> it.update(genie.id(), g -> g.askInstead(question, text)));
        runner(genie.id()).askInstead(question, text.strip());
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
        state.update(From.VIEW, it -> it.update(genie.id(), Genie::send));
        runner(genie.id()).sendMessage(text);
    }

    @Override public void stop(UUID id) {
        Optional.ofNullable(runners.get(id)).ifPresent(GenieRunner::stopAnswering);
    }

    /// The genie leaves the window at once; its lamp is put out and removed in the background.
    @Override public void delete(UUID id) {
        GenieRunner runner = runners.remove(id);
        state.update(From.VIEW, it -> it.remove(id));
        Path lamp = shelf.lampOf(id);
        Thread.ofVirtual().name("delete genie").start(() -> {
            try {
                if (runner != null) runner.sleepAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
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

    @Override public void give(UUID id, Path file) { runner(id).giveFile(file); }

    @Override public void save(UUID id, String name, Path target) { runner(id).saveOutboxFile(name, target); }

    // ─── the schedule ──────────────────────────────────────────────────────────────────────

    @Override public void saveJob(UUID id) {
        state.get().find(id).ifPresent(genie -> genie.schedule().draft().ifPresent(draft -> {
            ZoneId zone = genie.schedule().zone();
            runner(id).schedule().save(draft, LocalDateTime.now(zone), zone);
        }));
    }

    @Override public void removeJob(UUID id, String job) { runner(id).schedule().remove(job); }

    @Override public void switchJob(UUID id, String job, boolean on) { runner(id).schedule().switchJob(job, on); }

    @Override public void pauseSchedule(UUID id, boolean paused) { runner(id).schedule().pause(paused); }

    @Override public void stopRun(UUID id, String run) {
        Optional.ofNullable(runners.get(id)).ifPresent(runner -> runner.stopRun(run));
    }

    /// While the genie wakes, the chat stays where it is, as the tree does.
    @Override public void openConversation(UUID id, String conversation) {
        Optional<Genie> genie = state.get().find(id);
        if (genie.isEmpty()) return;
        state.update(From.VIEW, it -> it.select(id).withPage(GeniesState.Page.CHAT));
        if (genie.get().phase() != Genie.Phase.WAKING) runner(id).openConversation(conversation);
    }

    /// Leaving the page keeps the settings.
    @Override public void settingsDone() {
        state.update(From.VIEW, it -> it.withPage(GeniesState.Page.CHAT));
    }

    /// Asks the service the settings name for its models, off Swing's event thread, the way the
    /// genies' lamps will ask it, with the same key.
    @Override public void lookUpModels() {
        Settings settings = state.get().settings();
        Settings.Place place = settings.place();
        String service = settings.service();
        Optional<String> key = settings.listingKeyFrom(state.get().environmentKey());
        state.update(From.VIEW, GeniesState::askingForModels);
        Thread.ofVirtual().name("look up models").start(() -> {
            try {
                List<String> found = key.isPresent() ? Lamp.models(URI.create(service), key.get())
                                                     : Lamp.models(URI.create(service));
                SwingUtilities.invokeLater(() -> state.update(it -> it.modelsFound(place, service, Tuple.of(String.class, found))));
            } catch (IOException | IllegalArgumentException failed) {
                String why = Optional.ofNullable(failed.getMessage()).orElse(failed.toString());
                SwingUtilities.invokeLater(() -> state.update(it -> it.modelsNotFound(place, service, why)));
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

    /// Joins the genie's lamp if it was left running, and shows it where it was.
    private void rejoin(UUID id) {
        state.get().find(id).ifPresent(genie -> runner(id).rejoin(conversationShown(genie), leafShown(genie)));
    }

    /// The file of the conversation the genie was last in, or empty for its most recent one.
    private static String conversationShown(Genie genie) {
        return genie.conversations().current().map(Conversation::file).orElse("");
    }

    /// The entry the genie's chat was last at, or empty for where its conversation stands.
    private static String leafShown(Genie genie) {
        return genie.conversations().current().isPresent() ? genie.conversations().here().leaf() : "";
    }

    /// Asks the user what becomes of the genies that are awake, then puts them to sleep or leaves
    /// them running, before the window goes. Without an answer, nothing closes. (If Genies is
    /// killed instead, the lamps end by themselves: each notices its application is gone.)
    private void quit(JFrame frame) {
        List<String> awake = new ArrayList<>();
        for (Genie genie : state.get().genies())
            if (genie.phase().isAwake() || genie.phase() == Genie.Phase.WAKING) awake.add(genie.name());
        boolean leaveRunning = false;
        if (!awake.isEmpty()) {
            ConfirmAnswer answer = UI.confirmation(awakeQuestion(awake))
                    .titled("Genies are awake").yesOption("Put to sleep").noOption("Keep running")
                    .cancelOption("Cancel").parent(frame).show();
            if (answer.isCancelOrClose()) return;
            leaveRunning = answer.isNo();
        }
        boolean keep = leaveRunning;
        frame.setTitle(keep ? "Genies — leaving the genies running…" : "Genies — putting the genies to sleep…");
        keepGenies();
        keepSettings();
        Thread.ofVirtual().name("quit").start(() -> {
            for (GenieRunner runner : runners.values()) {
                try {
                    if (keep) runner.leaveRunningAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
                    else runner.sleepAndWait(SECONDS_TO_LET_GO_OF_A_LAMP);
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
