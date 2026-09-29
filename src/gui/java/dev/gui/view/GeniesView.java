package dev.gui.view;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import dev.gui.model.DesktopZoom;
import dev.gui.model.Entry;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Handout;
import dev.gui.model.Talk;
import dev.gui.model.Transcript;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForPanel;
import swingtree.api.Layout;
import swingtree.layout.FlowCell;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// The Genies window: the genies on the left, the conversation with the selected one in the
/// middle, and its desktop next to it when the user wants to watch.
///
/// Everything shown is a function of one [GeniesState], reached through lenses. Typing, picking
/// and toggling change that state directly; anything that starts, stops or moves something goes
/// through [Actions]. The view keeps no state of its own, except the connection of the
/// [DesktopScreen].
public final class GeniesView extends JPanel {

    private final Var<GeniesState> state;
    private final Actions actions;
    private final Look look = new Look();
    private final DesktopScreen desktop = new DesktopScreen();

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<String> draft;
    private final Var<String> name;
    private final Var<Boolean> desktopShown;
    private final Var<GeniesState.Page> page;
    private final Var<Boolean> sidebarShown;
    private final Var<Tuple<Genie>> genies;
    private final Var<Tuple<Entry>> entries;
    private final Val<Tuple<Handout>> handouts;
    private final Val<Genie.Phase> phase;
    private final Val<UUID> selected;
    private final Val<UUID> watched;
    private final Var<DesktopZoom> zoom;
    /// Loops from 0 to 1 while the genie on show thinks, for the dots in its answer.
    private final Var<Double> pulse = Var.of(0.0);
    private final ChatRows rows;
    /// Set once the conversation's scroll pane exists.
    private Optional<FollowTheEnd> follow = Optional.empty();

    public GeniesView(Var<GeniesState> state, Actions actions) {
        this.state = state;
        this.actions = actions;
        genie        = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        draft        = genie.zoomTo(Genie::draft, Genie::withDraft);
        name         = genie.zoomTo(Genie::name, Genie::withName);
        desktopShown = genie.zoomTo(Genie::desktopShown, Genie::withDesktopShown);
        page         = state.zoomTo(GeniesState::page, GeniesState::withPage);
        sidebarShown = state.zoomTo(GeniesState::sidebarShown, GeniesState::withSidebarShown);
        // Genies and entries keep their id while they change, so their rows are bound through a
        // lens onto each one: a row built from a plain value would never be redrawn.
        genies       = state.zoomTo(GeniesState::genies, GeniesState::withGenies);
        entries      = genie.zoomTo(it -> it.transcript().entries(), (it, changed) -> it.withTranscript(new Transcript(changed)));
        handouts     = genie.viewAs(Tuple.classTyped(Handout.class), Genie::handouts);
        phase        = genie.viewAs(Genie.Phase.class, Genie::phase);
        selected     = state.viewAs(UUID.class, GeniesState::selected);
        // The genie whose desktop is on screen: the selected one, while it is awake and asked
        // for; nobody otherwise.
        watched      = genie.viewAs(UUID.class, it -> it.phase().isAwake() && it.desktopShown() ? it.id() : GeniesState.NONE);
        Viewable.cast(watched).onChange(From.ALL, it -> {
            UUID id = it.currentValue().orElse(GeniesState.NONE);
            desktop.show(id.equals(GeniesState.NONE) ? Optional.empty() : actions.desktopOf(id));
        });
        zoom = state.zoomTo(GeniesState::zoom, GeniesState::withZoom);
        Viewable.cast(zoom).onChange(From.ALL, it -> desktop.zoom(it.currentValue().orElse(DesktopZoom.FIT).scale()));
        desktop.onZoomSteps(steps -> zoom.update(From.VIEW, it -> steps > 0 ? it.in(desktop.fitScale()) : it.out(desktop.fitScale())));
        Viewable.cast(phase).onChange(From.ALL, it -> {
            if (it.currentValue().orElseNull() == Genie.Phase.WORKING) breathe();
        });
        rows = new ChatRows(look, this::saveHandout, pulse, this::askInstead,
                           phase.viewAs(Boolean.class, it -> it == Genie.Phase.READY));

        UI.use(look, () ->
            of(this).group(Skin.FRAME)
            .withLayout("fill, ins 0, gap 0, hidemode 3")
            .withPrefSize(1280, 820)
            .withMinSize(0, 0)
            .onResize(it -> state.update(From.VIEW, s -> s.withViewWidth(it.getWidth())))
            .add("growy, width 250!", sidebar())
            .add("grow, push, wmin 0", main())
        );
    }

    // ─── the sidebar: every genie ──────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> sidebar() {
        return
            panel("fill, wrap 1, ins 0, gap 10, hidemode 3", "[grow]", "[][][][grow][]").group(Skin.SIDEBAR)
            .isVisibleIf(sidebarShown)
            .add("growx",
                box("fill, ins 0, gap 8", "[34!][grow]")
                .add(Parts.lamp(Val.of(Genie.Phase.READY), 34))
                .add("growx, wmin 0", label("Genies").group(Skin.BRAND)))
            .add("growx",
                button("+  New genie").group(Skin.FLAME_BUTTON)
                .withTooltip("A new genie, with a sandboxed desktop of its own")
                .onClick(it -> actions.newGenie()))
            .add("growx, gaptop 6", label("YOUR GENIES").group(Skin.SECTION))
            .add("grow, push, wmin 0",
                scrollPanels().group(Skin.PAGE_SCROLL).withMinSize(0, 0).withPrefSize(220, 400).withEmptyBorder(0)
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .addAll(genies, this::genieChip))
            .add("growx",
                box("fill, wrap 1, ins 0, gap 6, hidemode 3")
                .add("growx, wmin 0", label(state.viewAsString(it -> it.settingsProblem().orElse("")))
                        .group(Skin.PROBLEM).isVisibleIf(state.viewAs(Boolean.class, it -> it.settingsProblem().isPresent())))
                .add("growx",
                    button("⚙  Settings").group(Skin.QUIET_BUTTON)
                    .onClick(it -> page.set(From.VIEW, GeniesState.Page.SETTINGS))));
    }

    /// Built later than the constructor, so it enters the style sheet again.
    private UIForAnySwing<?, ?> genieChip(Var<Genie> shown) {
        return UI.of(UI.use(look, () -> genieChipBody(shown).get(JPanel.class)));
    }

    private UIForPanel<JPanel> genieChipBody(Var<Genie> shown) {
        UUID id = shown.get().id();
        Val<Boolean> isSelected = selected.viewAs(Boolean.class, it -> it.equals(id));
        return
            panel("fill, ins 7 8 7 8, gap 8", "[26!][grow]")
            .withStyle(isSelected, (on, it) -> it
                .backgroundColor(on ? RAISED : TRANSPARENT)
                .border(1, on ? BORDER : TRANSPARENT)
                .borderAt(UI.Edge.LEFT, on ? 3 : 0, FLAME)
                .borderRadius(10))
            .withCursor(UI.Cursor.HAND)
            .withTooltip(shown.viewAsString(it -> it.name() + " — " + it.activity()))
            .onMouseClick(it -> state.update(From.VIEW, s -> s.select(id)))
            .add("top", Parts.lamp(shown.viewAs(Genie.Phase.class, Genie::phase), 26))
            .add("growx, wmin 0, wrap",
                box("fill, wrap 1, ins 0, gap 0")
                .add("growx, wmin 0", label(shown.viewAsString(Genie::name)).withStyle(it -> it
                    .componentFont(f -> f.family(FONT).size(13).weight(2f).color(TEXT))))
                .add("growx, wmin 0", label(shown.viewAsString(Genie::activity)).group(Skin.META)))
            .add("span 2, growx, wmin 0", conversationsOf(shown));
    }

    // ─── a genie's conversations ───────────────────────────────────────────────────────────

    /// The genie's conversations, under its card: a line saying how many, which opens the tree
    /// of them. A conversation is a row, and below it are its branches, one for each question
    /// asked differently. Clicking a row goes there; the row the genie is on is selected.
    private UIForAnySwing<?, ?> conversationsOf(Var<Genie> shown) {
        UUID id = shown.get().id();
        Val<Boolean> open = shown.viewAs(Boolean.class, it -> it.conversations().shown());
        Val<Tuple<Talk>> talks = shown.viewAs(Tuple.classTyped(Talk.class), it -> it.conversations().tree());
        Val<Tuple<String>> here = shown.viewAs(Tuple.classTyped(String.class), it -> it.conversations().herePath());
        Val<Boolean> idle = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING && it.phase() != Genie.Phase.WAKING);
        Val<Boolean> canForget = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING
                && it.phase() != Genie.Phase.WAKING && it.conversations().current().isPresent());
        return
            box("fill, wrap 1, ins 0, gap 2, hidemode 3", "[grow]")
            .add("growx, wmin 0",
                label(shown.viewAsString(it -> (it.conversations().shown() ? "▾  " : "▸  ") + howMany(it.conversations().all().size())))
                .group(Skin.META).withCursor(UI.Cursor.HAND)
                .withTooltip("Show or hide this genie's conversations")
                .onMouseClick(it -> shown.update(From.VIEW, genie -> genie.withConversations(
                        genie.conversations().withShown(!genie.conversations().shown())))))
            .add("growx, wmin 0",
                UI.trees(talks, conf -> conf
                    .nodesOf(Talk.Chat.class, it -> it
                        .children(Talk.Chat::branches)
                        .text(Talk.Chat::title)
                        .toolTip(chat -> chat.title() + " — " + (chat.turns() == 0 ? "its first question was asked differently"
                                                                                  : questions(chat.turns(), chat.branches()))))
                    .nodesOf(Talk.Branch.class, it -> it
                        .children(Talk.Branch::forks)
                        .text(Talk.Branch::title)
                        .toolTip(branch -> branch.title() + " — " + questions(branch.turns(), branch.forks())))
                    .leafWhenEmpty(true))
                .isVisibleIf(open)
                .isEnabledIf(idle)
                .withSelection(here)
                .onSelection(it -> goTo(id, it.leadPath(), it.lead()))
                .withStyle(it -> it.backgroundColor(TRANSPARENT).componentFont(f -> f.family(FONT).size(12).color(TEXT))))
            .add("growx, wmin 0",
                box("ins 0, gap 4, hidemode 3")
                .isVisibleIf(open)
                .add(button("＋  New").group(Skin.QUIET_BUTTON).isEnabledIf(idle)
                     .withTooltip("Start a new conversation with this genie; the others are kept")
                     .onClick(it -> actions.startAfresh(id)))
                .add(button("Delete…").group(Skin.QUIET_BUTTON).isEnabledIf(canForget)
                     .withTooltip("Delete the conversation this genie is in, with all its branches")
                     .onClick(it -> confirmForget(id))));
    }

    private static String questions(int turns, Tuple<Talk.Branch> forks) {
        return (turns == 1 ? "one question" : turns + " questions")
             + (forks.isEmpty() ? "" : ", then asked differently " + (forks.size() == 2 ? "once" : forks.size() - 1 + " times"));
    }

    private static String howMany(int conversations) {
        return switch (conversations) {
            case 0 -> "no conversations yet";
            case 1 -> "1 conversation";
            default -> conversations + " conversations";
        };
    }

    /// The user clicked a row of a genie's tree, and goes to the row's last entry. The row the
    /// genie is on already, which the tree selects by itself, goes nowhere.
    private void goTo(UUID id, Tuple<String> path, Optional<Talk> row) {
        state.get().find(id).ifPresent(genie -> {
            if (path.isEmpty() || row.isEmpty() || path.equals(genie.conversations().herePath())) return;
            genie.conversations().find(path.first()).ifPresent(conversation ->
                actions.goTo(id, conversation.file(), row.get().leaf()));
        });
    }

    private void confirmForget(UUID id) {
        state.get().find(id).flatMap(genie -> genie.conversations().current()).ifPresent(doomed -> {
            swingtree.dialogs.ConfirmAnswer answer = UI.confirmation("Delete the conversation \"" + doomed.title()
                    + "\" for good, with all its branches?").titled("Delete a conversation")
                    .yesOption("Delete").noOption("Keep").cancelOption("").show();
            if (answer == swingtree.dialogs.ConfirmAnswer.YES) actions.forget(id, doomed.file());
        });
    }

    // ─── the main area: a conversation, or the settings ────────────────────────────────────

    private UIForAnySwing<?, ?> main() {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> onSettings = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SETTINGS);
        Val<Boolean> hasGenies = state.viewAs(Boolean.class, GeniesState::hasGenies);
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0", header(Viewable.of(Boolean.class, onChat, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", conversation(Viewable.of(Boolean.class, onChat, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", firstGenie(Viewable.of(Boolean.class, onChat, hasGenies, (a, b) -> a && !b)))
            .add("grow, push, wmin 0", SettingsPage.of(state, actions, onSettings));
    }

    private UIForAnySwing<?, ?> header(Val<Boolean> visible) {
        Val<Boolean> awake = phase.viewAs(Boolean.class, Genie.Phase::isAwake);
        Val<Boolean> working = phase.viewAs(Boolean.class, it -> it == Genie.Phase.WORKING);
        Val<Boolean> wide = state.viewAs(Boolean.class, it -> !it.narrow());
        return
            panel("fill, ins 0, gap 10, hidemode 3", "[][30!][grow][][][][][][]").group(Skin.HEADER)
            .isVisibleIf(visible)
            .add(button("☰").group(Skin.ICON_BUTTON).withTooltip("Show or hide your genies")
                 .onClick(it -> sidebarShown.update(From.VIEW, shown -> !shown)))
            .add(Parts.lamp(phase, 30))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 0")
                .add("growx, wmin 0", label(name).group(Skin.TITLE))
                .add("growx, wmin 0", label(genie.viewAsString(it -> it.activity())).group(Skin.SUBTITLE)))
            .add(label(genie.viewAsString(it -> it.tokens() == 0 ? "" : String.format("%,d tokens", it.tokens()))).group(Skin.META)
                 .isVisibleIf(wide)
                 .withTooltip("Tokens the model counted for this genie since Genies started"))
            .add(button("✎").group(Skin.ICON_BUTTON).withTooltip("Rename this genie").onClick(it -> rename()))
            .add(toggleButton(worded("▣  Desktop", "▣"), desktopShown).group(Skin.QUIET_BUTTON).isVisibleIf(awake)
                 .withTooltip("Watch the genie's desktop, and use it"))
            .add(button(worded("■  Stop", "■")).group(Skin.QUIET_BUTTON).isVisibleIf(working)
                 .withTooltip("Stop what the genie is doing")
                 .onClick(it -> actions.stop(genie.get().id())))
            .add(button(worded("☾  Sleep", "☾")).group(Skin.QUIET_BUTTON).isVisibleIf(awake)
                 .withTooltip("End the genie's sandbox. Its home and this conversation are kept.")
                 .onClick(it -> actions.sleep(genie.get().id())))
            .add(button(worded("Delete", "✕")).group(Skin.DANGER_BUTTON)
                 .withTooltip("Delete this genie and everything in its home, for good")
                 .onClick(it -> confirmDelete()));
    }

    /*
     *  The chat and the genie's desktop share one responsive grid, whose size classes are
     *  fifths of its reference width. Read the span table out loud: side by side from LARGE up,
     *  the chat taking five of twelve columns; one above the other below that.
     *
     *                    very small  small  medium  large  very large  oversize
     *      chat               12       12     12      5        5          5
     *      desktop            12       12     12      7        7          7
     *      chat, alone        12 everywhere
     *
     *  LARGE starts at three fifths of 1100, 660: GeniesState.SIDE_BY_SIDE_FROM, from which the
     *  state gives the two their heights. A grid gives a row the height its tallest cell
     *  prefers, and never stretches it to the window, so the heights are stated.
     */
    private static final int CONVERSATION_REFERENCE = 1100;
    private static final FlowCell CHAT = AUTO_SPAN(it -> it.fill(true)
            .verySmall(12).small(12).medium(12).large(5).veryLarge(5).oversize(5));
    private static final FlowCell DESKTOP = AUTO_SPAN(it -> it.fill(true)
            .verySmall(12).small(12).medium(12).large(7).veryLarge(7).oversize(7));
    private static final FlowCell WHOLE = AUTO_SPAN(it -> it.fill(true)
            .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12));
    private static final Layout WITH_DESKTOP = Layout.flow(UI.HorizontalAlignment.LEFT, 0, 0).withChildConstraints(CHAT, DESKTOP);
    private static final Layout CHAT_ALONE = Layout.flow(UI.HorizontalAlignment.LEFT, 0, 0).withChildConstraints(WHOLE, WHOLE);

    /// The chat, and with it the genie's desktop when the user watches. Nothing is rebuilt when
    /// the arrangement changes: the grid's cells are a value, and so are the heights.
    private UIForAnySwing<?, ?> conversation(Val<Boolean> visible) {
        Val<Boolean> shown = Viewable.of(Boolean.class, phase, desktopShown, (p, d) -> p.isAwake() && d);
        Val<Layout> cells = shown.viewAs(Layout.class, it -> it ? WITH_DESKTOP : CHAT_ALONE);
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            // The area measures itself: its width decides the arrangement, its height the heights.
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel(cells).withMinSize(0, 0).withPrefSize(CONVERSATION_REFERENCE, 0)
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add(
                    panel("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]", "[grow][][]")
                    .withMinSize(0, 0)
                    .withStyle(state.viewAs(Integer.class, GeniesState::chatHeight), (height, it) -> it
                        .backgroundColor(TRANSPARENT).prefHeight(height))
                    .add("grow, push, wmin 0", transcript())
                    .add("growx, wmin 0", outbox())
                    .add("growx, wmin 0", bottomBar()))
                .add(desktopPane(shown)));
    }

    /// The genie's desktop, in a scroll pane so a large desktop never blocks the layout, with
    /// the zoom above it: fit the room, or a scale at which small text is readable.
    private UIForAnySwing<?, ?> desktopPane(Val<Boolean> shown) {
        return
            panel("fill, wrap 1, ins 8 12 12 12, gap 6", "[grow]", "[][grow]")
            .isVisibleIf(shown)
            .withMinSize(0, 0)
            .withStyle(state.viewAs(Integer.class, GeniesState::desktopHeight), (height, it) -> it
                .backgroundColor(TRANSPARENT).prefHeight(height))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 2, hidemode 3", "[][][][][grow, right]")
                .add(zoomButton("⊡  Fit", "Fit the whole desktop into the room there is")
                     .onClick(it -> zoom.set(From.VIEW, DesktopZoom.FIT)))
                .add(zoomButton("−", "Smaller (or Ctrl and the mouse wheel on the desktop)")
                     .onClick(it -> zoom.update(From.VIEW, z -> z.out(desktop.fitScale()))))
                .add(label(zoom.viewAsString(it -> it.isFit() ? "fitted" : it.label())).group(Skin.META)
                     .withMinSize(46, 0).withHorizontalAlignment(UI.HorizontalAlignment.CENTER))
                .add(zoomButton("+", "Larger (or Ctrl and the mouse wheel on the desktop)")
                     .onClick(it -> zoom.update(From.VIEW, z -> z.in(desktop.fitScale()))))
                .add("wmin 0", label(state.viewAsString(it -> it.narrow() ? "" : "Click the desktop to use it"))
                     .group(Skin.META)))
            .add("grow, push, wmin 0, hmin 0",
                scrollPane().withEmptyBorder(0).withMinSize(0, 0)
                .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(10))
                .add(UI.of(desktop)));
    }

    private UIForAnySwing<?, ?> transcript() {
        Val<Boolean> empty = entries.viewAs(Boolean.class, Tuple::isEmpty);
        // Working, with nothing streaming in: the genie thinks, and a bar says so.
        Val<Boolean> waiting = genie.viewAs(Boolean.class, it -> it.phase() == Genie.Phase.WORKING
                && (it.transcript().isEmpty() || !it.transcript().entries().last().isWriting()));
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]", "[grow][]")
            .add("grow, push, wmin 0",
                scrollPanels().group(Skin.PAGE_SCROLL).withMinSize(0, 0).withPrefSize(560, 500).withEmptyBorder(0)
                .isVisibleIf(empty.viewAs(Boolean.class, it -> !it))
                .withStyle(it -> it.backgroundColor(TRANSPARENT).padding(12, 18, 12, 18))
                .addAll(entries, rows::row)
                // Follows the conversation while the user is at its end, and leaves them be
                // while they scroll back to read. Another genie's conversation starts at its end.
                .peek(pane -> follow = Optional.of(FollowTheEnd.on(pane)))
                .onView(Viewable.cast(selected), it -> follow.ifPresent(FollowTheEnd::toEnd)))
            .add("growx, wmin 0", rows.waiting(genie.viewAsString(Genie::name), waiting))
            .add("grow, push, wmin 0, align center",
                box("wrap 1, ins 30, gap 8, align center center", "[center, grow, fill]")
                .isVisibleIf(empty)
                .add("align center", Parts.lamp(phase, 96))
                .add("align center", label(genie.viewAsString(it -> it.phase().isAwake()
                        ? it.name() + " is listening." : it.name() + " is in the lamp.")).group(Skin.EMPTY_TITLE))
                .add("align center, wmin 0", label(genie.viewAsString(it -> it.phase().isAwake()
                        ? "Ask for anything. It has a desktop and a shell of its own, and hands you files it makes."
                        : "Wake it to talk. It keeps its own desktop, safely away from yours.")).group(Skin.EMPTY_TEXT)));
    }

    /// The files in the genie's outbox, any of which the user can save.
    private UIForAnySwing<?, ?> outbox() {
        return
            box("fill, ins 4 18 4 18, gap 8, hidemode 3", "[][grow]")
            .isVisibleIf(handouts.viewAs(Boolean.class, it -> !it.isEmpty()))
            .add("top, gaptop 6", label("OUTBOX").group(Skin.SECTION))
            .add("growx, wmin 0",
                box("ins 0, gap 6").withMinSize(0, 0)
                .addAll(handouts, file -> UI.of(UI.use(look, () ->
                    button("⤓ " + file.name() + "  ·  " + file.readableSize()).group(Skin.QUIET_BUTTON)
                    .withTooltip("Save " + file.name() + " somewhere on this computer")
                    .onClick(it -> saveHandout(file.name()))
                    .get(javax.swing.JButton.class)))));
    }

    /// The composer while the genie is awake; otherwise a bar that wakes it, and says how
    /// waking goes.
    private UIForAnySwing<?, ?> bottomBar() {
        Val<Boolean> awake = phase.viewAs(Boolean.class, Genie.Phase::isAwake);
        Val<Boolean> canWake = phase.viewAs(Boolean.class, it -> it == Genie.Phase.ASLEEP || it == Genie.Phase.BROKEN);
        Val<Boolean> waking = phase.viewAs(Boolean.class, it -> it == Genie.Phase.WAKING);
        Val<Boolean> canSend = genie.viewAs(Boolean.class, Genie::canSend);
        return
            box("fill, wrap 1, ins 6 18 16 18, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0",
                panel("fill, ins 0, gap 8", "[][grow][]", "[bottom]").group(Skin.COMPOSER).isVisibleIf(awake)
                .add(button("＋").group(Skin.ICON_BUTTON).withTooltip("Give the genie a file; it lands in ~/inbox")
                     .onClick(it -> giveFile()))
                .add("growx, wmin 0, hmin 36, hmax 160",
                    scrollPane().withEmptyBorder(0).withMinSize(0, 36)
                    .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                    .withStyle(it -> it.backgroundColor(TRANSPARENT))
                    .add(
                        textArea(draft).group(Skin.INPUT).peek(Parts::softWrap)
                        .withTooltip("Return sends; Shift and Return starts a new line")
                        .onKeyPress(it -> {
                            java.awt.event.KeyEvent key = it.getEvent();
                            if (key.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER && !key.isShiftDown()) {
                                key.consume();
                                actions.send();
                            }
                        })))
                .add(button("Send  ➤").group(Skin.FLAME_BUTTON).isEnabledIf(canSend)
                     .onClick(it -> actions.send())))
            .add("growx, wmin 0",
                panel("fill, ins 4 8 4 4, gap 12, hidemode 3", "[grow][][]").group(Skin.COMPOSER)
                .isVisibleIf(awake.viewAs(Boolean.class, it -> !it))
                .add("growx, wmin 0", label(genie.viewAsString(it -> switch (it.phase()) {
                        case WAKING -> "Waking " + it.name() + ": " + it.activity() + "…";
                        default -> it.name() + " is asleep. Its sandbox is off, its home and conversation are kept.";
                    })).group(Skin.SUBTITLE).isVisibleIf(phase.viewAs(Boolean.class, it -> it != Genie.Phase.BROKEN)))
                .add("growx, wmin 0", label(genie.viewAsString(it -> it.name() + " could not wake: " + it.activity()))
                     .group(Skin.PROBLEM).isVisibleIf(phase.viewAs(Boolean.class, it -> it == Genie.Phase.BROKEN)))
                .add(button(genie.viewAsString(it -> it.phase() == Genie.Phase.BROKEN ? "Try again" : "✦  Wake"))
                     .group(Skin.FLAME_BUTTON).isVisibleIf(canWake)
                     .onClick(it -> actions.wake(genie.get().id())))
                .add(label("the first wake builds the sandbox, which takes a few minutes").group(Skin.META)
                     .isVisibleIf(waking)));
    }

    private UIForAnySwing<?, ?> firstGenie(Val<Boolean> visible) {
        return
            box("fill, ins 40", "[grow, center]", "[grow, center]")
            .isVisibleIf(visible)
            .add(
                panel("wrap 1, ins 36 44 36 44, gap 10", "[center]").group(Skin.CARD)
                .add(Parts.lamp(Val.of(Genie.Phase.ASLEEP), 120))
                .add(label("Rub the lamp").group(Skin.EMPTY_TITLE))
                .add(label("Each genie is an AI agent with a Linux desktop of its own, in a sandbox.").group(Skin.EMPTY_TEXT))
                .add(label("It can use the web, run programs and make files, but never touches yours.").group(Skin.EMPTY_TEXT))
                .add("gaptop 12", button("+  Your first genie").group(Skin.FLAME_BUTTON).onClick(it -> actions.newGenie())));
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    /// The lamp, lit, for the window's icon.
    public static java.awt.Image windowIcon() {
        return swingtree.style.SvgIcon.of(Art.lamp(Genie.Phase.READY)).withIconSize(64, 64).getImage();
    }

    private static swingtree.UIForButton<javax.swing.JButton> zoomButton(String text, String tip) {
        return button(text).group(Skin.ICON_BUTTON).withTooltip(tip)
                .withStyle(it -> it.componentFont(f -> f.family(FONT).size(12).color(TEXT)));
    }

    /// A button's words while the window is wide, and just its sign while it is narrow.
    private Val<String> worded(String wide, String narrow) {
        return state.viewAsString(it -> it.narrow() ? narrow : wide);
    }

    private void rename() {
        Genie named = genie.get();
        Object answer = javax.swing.JOptionPane.showInputDialog(this, "A new name for " + named.name() + ":",
                "Rename a genie", javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, named.name());
        if (answer instanceof String text && !text.isBlank()) name.set(From.VIEW, text.strip());
    }

    private void confirmDelete() {
        Genie doomed = genie.get();
        swingtree.dialogs.ConfirmAnswer answer = UI.confirmation("Delete " + doomed.name() + " for good? Its home, everything it made "
                + "and your conversation with it are deleted.").titled("Delete a genie").yesOption("Delete").noOption("Keep").cancelOption("").show();
        if (answer == swingtree.dialogs.ConfirmAnswer.YES) actions.delete(doomed.id());
    }

    /// Lets the user change a question they asked, in a dialog holding the question as it was.
    private void askInstead(Entry question) {
        javax.swing.JTextArea text = new javax.swing.JTextArea(question.text(), 6, 48);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        int answer = javax.swing.JOptionPane.showConfirmDialog(this, new JScrollPane(text),
                "Ask " + genie.get().name() + " differently", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        String changed = text.getText().strip();
        if (answer == javax.swing.JOptionPane.OK_OPTION && !changed.isEmpty() && !changed.equals(question.text().strip()))
            actions.askInstead(question.ref(), changed);
    }

    private void giveFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Give " + genie.get().name() + " a file");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            actions.give(genie.get().id(), chooser.getSelectedFile().toPath());
    }

    private void saveHandout(String file) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save " + file);
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), file));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path target = chooser.getSelectedFile().toPath();
            actions.save(genie.get().id(), file, target);
        }
    }

    /// Moves the thinking bars for as long as the genie on show works.
    private void breathe() {
        UI.animateFor(1.2, java.util.concurrent.TimeUnit.SECONDS)
          .asLongAs(status -> genie.get().phase() == Genie.Phase.WORKING)
          .go(status -> pulse.set(status.progress()));
    }
}
