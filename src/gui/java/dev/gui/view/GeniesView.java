package dev.gui.view;

import dev.gui.model.*;
import sprouts.*;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForButton;
import swingtree.UIForPanel;
import swingtree.api.Layout;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.layout.FlowCell;
import swingtree.style.SvgIcon;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    private final SchedulePage schedulePage;
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
        desktop.zoom(zoom.get().scale());
        desktop.onZoomSteps(steps -> zoom.update(From.VIEW, it -> steps > 0 ? it.in(desktop.fitScale()) : it.out(desktop.fitScale())));
        Viewable.cast(phase).onChange(From.ALL, it -> {
            if (it.currentValue().orElseNull() == Genie.Phase.WORKING) breathe();
        });
        rows = new ChatRows(look, this::saveHandout, pulse, this::askInstead,
                           phase.viewAs(Boolean.class, it -> it == Genie.Phase.READY));
        schedulePage = new SchedulePage(state, actions, look);

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

    /// Lets go of the desktop on show, which gets its own size back if it had the panel's. The
    /// thread doing that, for an application that is about to end and must wait for it.
    public Optional<Thread> letGoOfTheDesktop() {
        return desktop.letGo();
    }

    private UIForAnySwing<?, ?> sidebar() {
        return
            panel("fill, wrap 1, ins 0, gap 10, hidemode 3", "[grow]", "[][][][grow][]").group(Skin.SIDEBAR)
            .isVisibleIf(sidebarShown)
            .add("growx",
                box("fill, ins 0, gap 8", "[34!][grow]")
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.READY), 34))
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
                .add("growx, wmin 0", ViewPartsUtil.wrapped(state.viewAsString(it -> it.settingsProblem().orElse("")), TROUBLE,
                        state.viewAs(Boolean.class, it -> it.settingsProblem().isPresent())))
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
        // What the genie shows on its desktop, said here until the user is at its chat to see it.
        Val<Boolean> atItsChat = state.viewAs(Boolean.class, it -> it.selected().equals(id) && it.page() == GeniesState.Page.CHAT);
        Val<Boolean> showsElsewhere = Viewable.of(Boolean.class, shown, atItsChat, (it, there) -> !it.showing().isEmpty() && !there);
        return
            panel("fill, ins 7 8 7 8, gap 8", "[26!][grow]")
            .withStyle(isSelected, (on, it) -> it
                .backgroundColor(on ? RAISED : TRANSPARENT)
                .border(1, on ? BORDER : TRANSPARENT)
                // Always there, lit when selected, so selecting a genie moves nothing.
                .borderAt(UI.Edge.LEFT, 3, on ? FLAME : TRANSPARENT)
                .borderRadius(10))
            .withCursor(UI.Cursor.HAND)
            .withTooltip(shown.viewAsString(it -> it.name() + " — " + it.activity()
                    + (it.showing().isEmpty() ? "" : ". Shows you: " + it.showing()) + ". Right-click for more."))
            .onMouseClick(it -> {
                state.update(From.VIEW, s -> s.select(id));
                if (it.isRightMouseButton()) genieMenu(id).show(it.getComponent(), it.mouseX(), it.mouseY());
            })
            .add("top", ViewPartsUtil.lamp(shown.viewAs(Genie.Phase.class, Genie::phase), 26))
            .add("growx, wmin 0, wrap",
                box("fill, wrap 1, ins 0, gap 0, hidemode 3")
                .add("growx, wmin 0", label(shown.viewAsString(Genie::name)).withStyle(it -> it
                    .componentFont(f -> f.family(FONT).size(13).weight(2f).color(TEXT))))
                .add("growx, wmin 0", label(shown.viewAsString(Genie::activity)).group(Skin.META))
                .add("growx, wmin 0", label(shown.viewAsString(it -> "▣  shows you: " + it.showing()))
                     .isVisibleIf(showsElsewhere)
                     .withStyle(it -> it.componentFont(f -> f.family(FONT).size(11).color(FLAME)))))
            .add("span 2, growx, wmin 0", conversationsOf(shown));
    }

    // ─── a genie's conversations ───────────────────────────────────────────────────────────

    /// The genie's conversations, under its card, as two trees: the user's own, and those the
    /// runs of its scheduled jobs had. Each opens from a line saying how many. A conversation is
    /// a row, and below it are its branches, one for each question asked differently. Clicking a
    /// row goes there; the row the genie is on is selected.
    private UIForAnySwing<?, ?> conversationsOf(Var<Genie> shown) {
        UUID id = shown.get().id();
        Var<Conversations> conversations = shown.zoomTo(Genie::conversations, Genie::withConversations);
        Var<Conversations.Fold> chats = conversations.zoomTo(Conversations::chatsFold, Conversations::withChatsFold);
        Var<Conversations.Fold> jobs = conversations.zoomTo(Conversations::jobsFold, Conversations::withJobsFold);
        // The trees can be gone through while the genie answers; a new conversation waits for the answer.
        Val<Boolean> browsable = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WAKING);
        Val<Boolean> idle = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING && it.phase() != Genie.Phase.WAKING);
        Val<Boolean> canForget = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING
                && it.phase() != Genie.Phase.WAKING && it.conversations().current().isPresent());
        Val<Boolean> chatsOpen = chats.viewAs(Boolean.class, Conversations.Fold::shown);
        return
            box("fill, wrap 1, ins 0, gap 2, hidemode 3", "[grow]")
            .add("growx, wmin 0",
                tree(id, chats, conversations.viewAsString(it -> howMany(it.chatCount(), "conversation", "no conversations yet")),
                     "Show or hide your conversations with this genie", Val.of(true),
                     conversations.viewAs(Tuple.classTyped(Talk.class), Conversations::chats), browsable))
            .add("growx, wmin 0",
                box("ins 0, gap 4, hidemode 3")
                .isVisibleIf(chatsOpen)
                .add(button("＋  New").group(Skin.QUIET_BUTTON).isEnabledIf(idle)
                     .withTooltip("Start a new conversation with this genie; the others are kept")
                     .onClick(it -> actions.startAfresh(id)))
                .add(button("Delete…").group(Skin.QUIET_BUTTON).isEnabledIf(canForget)
                     .withTooltip("Delete the conversation this genie is in, with all its branches")
                     .onClick(it -> confirmForget(id))))
            .add("growx, wmin 0, gaptop 4",
                tree(id, jobs, conversations.viewAsString(it -> howMany(it.jobCount(), "scheduled run", "")),
                     "Show or hide the conversations the runs of this genie's scheduled jobs had",
                     conversations.viewAs(Boolean.class, it -> it.jobCount() > 0),
                     conversations.viewAs(Tuple.classTyped(Talk.class), Conversations::jobRuns), browsable));
    }

    /// One tree of conversations: the line that opens it, then the tree in an area of the fold's
    /// height, which scrolls when the tree is taller, and a grip under it that the user drags to
    /// make the area taller or shorter.
    private UIForAnySwing<?, ?> tree(UUID id, Var<Conversations.Fold> fold, Val<String> count, String tip,
                                     Val<Boolean> present, Val<Tuple<Talk>> rows, Val<Boolean> browsable) {
        Val<Boolean> open = fold.viewAs(Boolean.class, Conversations.Fold::shown);
        Val<Tuple<String>> here = rows.viewAs(Tuple.classTyped(String.class), Conversations::pathToHere);
        // Where a drag of the grip began: the pointer's height on the screen, the area's, and
        // the fold's. The area's is what it shows, which for a short tree is less than the fold's.
        int[] dragFrom = new int[3];
        JScrollPane[] area = new JScrollPane[1];
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .isVisibleIf(present)
            .add("growx, wmin 0",
                label(Viewable.of(String.class, open, count, (on, words) -> (on ? "▾  " : "▸  ") + words))
                .group(Skin.META).withCursor(UI.Cursor.HAND)
                .withTooltip(tip)
                .onMouseClick(it -> fold.update(From.VIEW, Conversations.Fold::toggled)))
            .add("growx, wmin 0, hmin 0",
                scrollPane(conf -> conf.fitWidth(true)).withEmptyBorder(0).withMinSize(0, 0)
                .peek(it -> area[0] = it)
                .isVisibleIf(open)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withVerticalScrollIncrement(16)
                // At most the fold's height; a tree of a few rows takes only what it needs.
                .withMaxHeight(fold.viewAs(Integer.class, Conversations.Fold::height))
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add(
                    // In a panel of its own: on its own, a tree asks its scroll pane for room for
                    // twenty rows, however many it has.
                    panel("fill, ins 0").withStyle(it -> it.backgroundColor(TRANSPARENT))
                    .add("grow, wmin 0",
                        UI.trees(rows, conf -> conf
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
                        .isEnabledIf(browsable)
                        .withSelection(here)
                        .onSelection(it -> goTo(id, it.leadPath(), it.lead()))
                        .withStyle(it -> it.backgroundColor(TRANSPARENT).componentFont(f -> f.family(FONT).size(12).color(TEXT))))))
            .add("growx, wmin 0, h 9!",
                panel().withCursor(UI.Cursor.RESIZE_BOTTOM)
                .isVisibleIf(open)
                .withTooltip("Drag to make this list taller or shorter")
                .withStyle(it -> {
                    int width = it.componentWidth(), height = it.componentHeight();
                    return it.backgroundColor(TRANSPARENT).painter(UI.Layer.CONTENT, g -> grip(g, width, height));
                })
                .onMousePress(it -> {
                    dragFrom[0] = it.mouseYOnScreen();
                    dragFrom[1] = area[0].getHeight();
                    dragFrom[2] = fold.get().height();
                })
                .onMouseDrag(it -> fold.update(From.VIEW, f -> f.withHeight(dragFrom[1] + it.mouseYOnScreen() - dragFrom[0])))
                .onMouseRelease(it -> fold.update(From.VIEW, f -> f.released(dragFrom[2]))));
    }

    /// The grip under a tree: a thin line across, like a split pane's divider, with a short
    /// raised handle in its middle.
    private static void grip(Graphics2D g, int width, int height) {
        int middle = height / 2;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BORDER);
        g.fillRect(0, middle, width, 1);
        g.setColor(SUBTEXT);
        g.fillRoundRect(width / 2 - 14, middle - 1, 28, 3, 3, 3);
    }

    private static String questions(int turns, Tuple<Talk.Branch> forks) {
        return (turns == 1 ? "one question" : turns + " questions")
             + (forks.isEmpty() ? "" : ", then asked differently " + (forks.size() == 2 ? "once" : forks.size() - 1 + " times"));
    }

    /// "no conversations yet", "1 conversation", "3 conversations", and so on.
    private static String howMany(int count, String one, String none) {
        return switch (count) {
            case 0 -> none;
            case 1 -> "1 " + one;
            default -> count + " " + one + "s";
        };
    }

    /// The user clicked a row of a genie's tree, and goes to the row's last entry. The row the
    /// genie is on already, which the tree selects by itself, goes nowhere.
    private void goTo(UUID id, Tuple<String> path, Optional<Talk> row) {
        state.get().find(id).ifPresent(genie -> {
            if (path.isEmpty() || row.isEmpty() || path.equals(genie.conversations().herePath())) return;
            genie.conversations().fileOf(path.first()).ifPresent(file -> actions.goTo(id, file, row.get().leaf()));
        });
    }

    private void confirmForget(UUID id) {
        state.get().find(id).flatMap(genie -> genie.conversations().current()).ifPresent(doomed -> {
            ConfirmAnswer answer = UI.confirmation("Delete the conversation \"" + doomed.title()
                    + "\" for good, with all its branches?").titled("Delete a conversation")
                    .yesOption("Delete").noOption("Keep").cancelOption("").show();
            if (answer == ConfirmAnswer.YES) actions.forget(id, doomed.file());
        });
    }

    // ─── the main area: a conversation, a schedule, or the settings ────────────────────────

    private UIForAnySwing<?, ?> main() {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> onSchedule = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SCHEDULE);
        Val<Boolean> onSettings = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SETTINGS);
        Val<Boolean> hasGenies = state.viewAs(Boolean.class, GeniesState::hasGenies);
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0", header(Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> !a && b)))
            .add("grow, push, wmin 0", conversation(Viewable.of(Boolean.class, onChat, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", schedulePage.view(Viewable.of(Boolean.class, onSchedule, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", firstGenie(Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> !a && !b)))
            .add("grow, push, wmin 0", SettingsPage.of(state, actions, onSettings));
    }

    private UIForAnySwing<?, ?> header(Val<Boolean> visible) {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> awake = Viewable.of(Boolean.class, phase, onChat, (it, chat) -> it.isAwake() && chat);
        Val<Boolean> working = phase.viewAs(Boolean.class, it -> it == Genie.Phase.WORKING);
        Val<Boolean> wide = state.viewAs(Boolean.class, GeniesState::roomForWords);
        return
            panel("fill, ins 0, gap 10, hidemode 3", "[][30!][grow][]").group(Skin.HEADER)
            .isVisibleIf(visible)
            .add(button("☰").group(Skin.ICON_BUTTON).withTooltip("Show or hide your genies")
                 .onClick(it -> sidebarShown.update(From.VIEW, shown -> !shown)))
            .add(ViewPartsUtil.lamp(phase, 30))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 0")
                .add("growx, wmin 0", label(name).group(Skin.TITLE)
                     .withTooltip("Double-click to rename")
                     .onMouseClick(it -> { if (it.clickCount() == 2) rename(genie.get().id()); }))
                .add("growx, wmin 0", label(genie.viewAsString(it -> it.activity())).group(Skin.SUBTITLE)))
            // One group on the right, so buttons that are hidden leave no gap behind.
            .add(box("ins 0, gap 10, hidemode 3, aligny center")
            .add(pages())
            .add(label(genie.viewAsString(it -> it.tokens() == 0 ? "" : String.format("%,d tokens", it.tokens()))).group(Skin.META)
                 .isVisibleIf(wide)
                 .withTooltip("Tokens the model counted for this genie since Genies started"))
            .add(toggleButton(worded("▣  Desktop", "▣"), desktopShown).group(Skin.QUIET_BUTTON).isVisibleIf(awake)
                 .withTooltip("Watch the genie's desktop, and use it"))
            .add(button(worded("■  Stop", "■")).group(Skin.QUIET_BUTTON).isVisibleIf(working)
                 .withTooltip("Stop what the genie is doing")
                 .onClick(it -> actions.stop(genie.get().id())))
            .add(button(worded("☾  Sleep", "☾")).group(Skin.QUIET_BUTTON).isVisibleIf(phase.viewAs(Boolean.class, Genie.Phase::isAwake))
                 .withTooltip("End the genie's sandbox. Its home and this conversation are kept.")
                 .onClick(it -> actions.sleep(genie.get().id())))
            .add(button("⋯").group(Skin.ICON_BUTTON)
                 .withTooltip("Rename, start a new conversation, or delete this genie")
                 .onClick(it -> ViewPartsUtil.below(genieMenu(genie.get().id()), it.getComponent()))));
    }

    /// The two pages of a genie, its chat and its schedule, as one switch of two halves. The
    /// schedule's half says how many jobs it has, once they were read.
    private UIForAnySwing<?, ?> pages() {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> onSchedule = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SCHEDULE);
        Val<String> scheduleWords = genie.viewAsString(it -> "Schedule" + (it.schedule().jobs().isEmpty() ? "" : "  " + it.schedule().jobs().size()));
        return
            box("ins 2, gap 2")
            .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(11))
            .add(half(Val.of("Chat"), onChat).withTooltip("Talk with the genie")
                 .onClick(it -> page.set(From.VIEW, GeniesState.Page.CHAT)))
            .add(half(scheduleWords, onSchedule).withTooltip("When jobs wake the genie, and what they did")
                 .onClick(it -> page.set(From.VIEW, GeniesState.Page.SCHEDULE)));
    }

    private static UIForButton<JButton> half(Val<String> text, Val<Boolean> shown) {
        return button(text).group(Skin.ICON_BUTTON)
                .withStyle(shown, (on, it) -> it.borderRadius(9).padding(4, 12, 4, 12)
                    .backgroundColor(on ? RAISED : TRANSPARENT)
                    .componentFont(f -> f.family(FONT).size(12).weight(on ? 2f : 1f).color(on ? TEXT : SUBTEXT)));
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
    /// how it is shown above it: at the size of this panel, which the desktop then takes; fitted
    /// into the panel at its own size; or at a scale at which small text is readable. Above that,
    /// what the genie said it shows there.
    private UIForAnySwing<?, ?> desktopPane(Val<Boolean> shown) {
        Val<Boolean> showing = genie.viewAs(Boolean.class, it -> !it.showing().isEmpty());
        // A recorded desktop keeps its size; at the panel's size, that needs saying, and it is
        // drawn fitted, as with Fit.
        Val<Boolean> keeps = Viewable.of(Boolean.class, zoom, desktop.keepsItsSize(), (it, kept) -> it.isPanel() && kept);
        Val<Boolean> zooms = Viewable.of(Boolean.class, zoom, keeps, (it, kept) -> !it.isPanel() || kept);
        Val<String> panelSize = Viewable.of(String.class, keeps, desktop.desktopSize(), (kept, size) -> kept ? "fitted" : size);
        Val<String> scale = Viewable.of(String.class, zoom, panelSize,
                (it, size) -> it.isPanel() ? size : it.isFit() ? "fitted" : it.label());
        Val<String> hint = Viewable.of(String.class, keeps, state.viewAs(Boolean.class, GeniesState::narrow),
                (kept, narrow) -> kept ? "Recorded, so it keeps its own size" : narrow ? "" : "Click the desktop to use it");
        return
            panel("fill, wrap 1, ins 8 12 12 12, gap 6, hidemode 3", "[grow]", "[][][grow]")
            .isVisibleIf(shown)
            .withMinSize(0, 0)
            .withStyle(state.viewAs(Integer.class, GeniesState::desktopHeight), (height, it) -> it
                .backgroundColor(TRANSPARENT).prefHeight(height))
            .add("growx, wmin 0",
                label(genie.viewAsString(it -> "✦  " + it.name() + " shows you: " + it.showing()))
                .isVisibleIf(showing)
                .withTooltip(genie.viewAsString(Genie::showing))
                .withStyle(it -> it.componentFont(f -> f.family(FONT).size(13).weight(2f).color(FLAME))))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 2, hidemode 3")
                .add(modeButton("⤢  Panel", "Give the desktop the size of this panel, so what the genie shows fills it."
                                + " It gets its own size back when you close it.", zoom.viewAs(Boolean.class, DesktopZoom::isPanel))
                     .onClick(it -> zoom.set(From.VIEW, DesktopZoom.PANEL)))
                .add(modeButton("⊡  Fit", "Show the whole desktop at its own size, shrunk into this panel",
                                zoom.viewAs(Boolean.class, DesktopZoom::isFit))
                     .onClick(it -> zoom.set(From.VIEW, DesktopZoom.FIT)))
                // At the panel's size, the desktop is drawn pixel for pixel: there is nothing to
                // zoom, and between these the desktop's size could pass for something they change.
                // A desktop that keeps its own size is drawn fitted, and zooms as from Fit.
                .add(zoomButton("−", "Smaller (or Ctrl and the mouse wheel on the desktop)").isVisibleIf(zooms)
                     .onClick(it -> zoom.update(From.VIEW, z -> z.out(desktop.fitScale()))))
                .add(label(scale).group(Skin.META)
                     .withMinSize(70, 0).withHorizontalAlignment(UI.HorizontalAlignment.CENTER))
                .add(zoomButton("+", "Larger (or Ctrl and the mouse wheel on the desktop)").isVisibleIf(zooms)
                     .onClick(it -> zoom.update(From.VIEW, z -> z.in(desktop.fitScale()))))
                .add("wmin 0, pushx, alignx right", label(hint).group(Skin.META)))
            .add("grow, push, wmin 0, hmin 0",
                scrollPane().withEmptyBorder(0).withMinSize(0, 0)
                .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(10))
                .add(UI.of(desktop)));
    }

    private UIForAnySwing<?, ?> transcript() {
        Val<Boolean> empty = entries.viewAs(Boolean.class, Tuple::isEmpty);
        // Working, with nothing streaming in: the genie thinks, and a bar says so.
        Val<Boolean> waiting = genie.viewAs(Boolean.class, it -> it.phase() == Genie.Phase.WORKING
                && it.conversations().aside().isEmpty()
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
                .add("align center", ViewPartsUtil.lamp(phase, 96))
                .add("growx, wmin 0", label(genie.viewAsString(it -> switch (it.phase()) {
                        case READY, WORKING -> it.name() + " is listening.";
                        case WAKING -> it.name() + " is waking.";
                        case ASLEEP -> it.name() + " is in the lamp.";
                        case BROKEN -> it.name() + " could not wake.";
                    })).group(Skin.EMPTY_TITLE).withHorizontalAlignment(UI.HorizontalAlignment.CENTER))
                .add("growx, wmin 0", label(genie.viewAsString(it -> switch (it.phase()) {
                        case READY, WORKING -> "Ask for anything. It has a desktop and a shell of its own, and hands you files it makes.";
                        case WAKING -> "Its sandbox is starting. The very first time, the sandbox is made, which takes several minutes.";
                        case ASLEEP -> "Wake it to talk. It keeps its own desktop, safely away from yours.";
                        case BROKEN -> "What went wrong is said below. Try again once that is sorted out.";
                    })).group(Skin.EMPTY_TEXT).withHorizontalAlignment(UI.HorizontalAlignment.CENTER)));
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
                    .get(JButton.class)))));
    }

    /// The composer while the genie is awake; otherwise a bar that wakes it, and says how
    /// waking goes.
    private UIForAnySwing<?, ?> bottomBar() {
        Val<Boolean> awake = phase.viewAs(Boolean.class, Genie.Phase::isAwake);
        Val<Boolean> canWake = phase.viewAs(Boolean.class, it -> it == Genie.Phase.ASLEEP || it == Genie.Phase.BROKEN);
        Val<Boolean> canSend = genie.viewAs(Boolean.class, Genie::canSend);
        Val<Boolean> answeringElsewhere = genie.viewAs(Boolean.class, it -> it.conversations().aside().isPresent());
        return
            box("fill, wrap 1, ins 6 18 16 18, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0, gapbottom 6",
                ViewPartsUtil.wrapped(genie.viewAsString(it -> it.name() + " is answering in another conversation. Write here once it is done."),
                              SUBTEXT, answeringElsewhere))
            .add("growx, wmin 0",
                panel("fill, ins 0, gap 8", "[][grow][]", "[bottom]").group(Skin.COMPOSER).isVisibleIf(awake)
                .add(button("＋").group(Skin.ICON_BUTTON).withTooltip("Give the genie a file; it lands in ~/inbox")
                     .onClick(it -> giveFile()))
                .add("growx, wmin 0, hmin 36, hmax 160",
                    scrollPane().withEmptyBorder(0).withMinSize(0, 36)
                    .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                    .withStyle(it -> it.backgroundColor(TRANSPARENT))
                    .add(
                        textArea(draft).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                        // The composer is the box; the text in it needs no second one.
                        .withStyle(it -> it.backgroundColor(TRANSPARENT).border(0, TRANSPARENT))
                        .withTooltip("Return sends; Shift and Return starts a new line")
                        // Swing stops laying out at the scroll pane, so the composer never learns
                        // that the text grew or shrank; told here, it grows up to its hmax, then scrolls.
                        .onTextChange(it -> SwingUtilities.getAncestorOfClass(JScrollPane.class, it.getComponent())
                                                          .getParent().revalidate())
                        .onKeyPress(it -> {
                            KeyEvent key = it.getEvent();
                            if (key.getKeyCode() != KeyEvent.VK_ENTER) return;
                            key.consume();
                            // Swing makes a new line only of a plain Return, so Shift and Return
                            // would do nothing at all; the line is put in here.
                            if (key.isShiftDown()) it.getComponent().replaceSelection("\n");
                            else actions.send();
                        })))
                .add(button("Send  ➤").group(Skin.FLAME_BUTTON).isEnabledIf(canSend)
                     // With nothing to send, it steps back rather than glowing half-lit.
                     .withStyle(canSend, (on, it) -> on ? it : it.backgroundColor(RAISED).foregroundColor(SUBTEXT)
                         .componentFont(f -> f.color(SUBTEXT)).cursor(UI.Cursor.DEFAULT))
                     .onClick(it -> actions.send())))
            .add("growx, wmin 0",
                panel("fill, ins 4 8 4 4, gap 12, hidemode 3", "[grow][][]").group(Skin.COMPOSER)
                .isVisibleIf(awake.viewAs(Boolean.class, it -> !it))
                .add("growx, wmin 0", label(genie.viewAsString(it -> switch (it.phase()) {
                        case WAKING -> "Waking " + it.name() + ": " + it.activity() + "…";
                        default -> it.name() + " is asleep. Its sandbox is off, its home and conversation are kept.";
                    })).group(Skin.SUBTITLE).isVisibleIf(phase.viewAs(Boolean.class, it -> it != Genie.Phase.BROKEN)))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(genie.viewAsString(it -> it.name() + " could not wake: " + it.activity()),
                     TROUBLE, phase.viewAs(Boolean.class, it -> it == Genie.Phase.BROKEN)))
                .add(button(genie.viewAsString(it -> it.phase() == Genie.Phase.BROKEN ? "Try again" : "✦  Wake"))
                     .group(Skin.FLAME_BUTTON).isVisibleIf(canWake)
                     .onClick(it -> actions.wake(genie.get().id()))));
    }

    private UIForAnySwing<?, ?> firstGenie(Val<Boolean> visible) {
        return
            box("fill, ins 40", "[grow, center]", "[grow, center]")
            .isVisibleIf(visible)
            .add(
                panel("wrap 1, ins 36 44 36 44, gap 10", "[center]").group(Skin.CARD)
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.ASLEEP), 120))
                .add(label("Rub the lamp").group(Skin.EMPTY_TITLE))
                .add(label("Each genie is an AI agent with a Linux desktop of its own, in a sandbox.").group(Skin.EMPTY_TEXT))
                .add(label("It can use the web, run programs and make files, but never touches yours.").group(Skin.EMPTY_TEXT))
                .add("gaptop 12", button("+  Your first genie").group(Skin.FLAME_BUTTON).onClick(it -> actions.newGenie())));
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    /// Sets up the look before any window opens: Genies' own fonts, bundled so it looks the
    /// same on every desktop, then the dark look-and-feel in the lamp's colours. Those also
    /// reach what the style sheet does not paint: dialogs, the tree of conversations, lists
    /// of choices, scroll bars.
    public static void setUpLook() {
        com.formdev.flatlaf.FlatLaf.setPreferredFontFamily(FONT);
        com.formdev.flatlaf.FlatLaf.setPreferredMonospacedFontFamily(MONO);
        com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
            Map.entry("@background", hex(CARD)),
            Map.entry("@foreground", hex(TEXT)),
            Map.entry("@accentColor", hex(FLAME)),
            Map.entry("@selectionBackground", hex(YOURS)),
            Map.entry("@selectionForeground", hex(TEXT)),
            Map.entry("@selectionInactiveBackground", hex(YOURS)),
            Map.entry("@selectionInactiveForeground", hex(TEXT)),
            Map.entry("Component.borderColor", hex(BORDER)),
            Map.entry("Component.focusColor", hex(FLAME)),
            Map.entry("Component.focusedBorderColor", hex(BRASS)),
            Map.entry("Component.arc", "10"),
            Map.entry("Button.arc", "10"),
            Map.entry("TextComponent.arc", "10"),
            Map.entry("ComboBox.background", hex(RAISED)),
            Map.entry("ComboBox.editableBackground", hex(RAISED)),
            Map.entry("ComboBox.buttonBackground", hex(RAISED)),
            Map.entry("ComboBox.buttonEditableBackground", hex(RAISED)),
            Map.entry("ComboBox.popupBackground", hex(CARD)),
            Map.entry("TextField.background", hex(RAISED)),
            Map.entry("PasswordField.background", hex(RAISED)),
            Map.entry("TextArea.background", hex(RAISED)),
            Map.entry("Tree.selectionArc", "6"),
            Map.entry("CheckBox.icon.selectedBackground", hex(FLAME)),
            Map.entry("CheckBox.icon.selectedBorderColor", hex(FLAME)),
            Map.entry("CheckBox.icon.checkmarkColor", hex(ON_FLAME)),
            Map.entry("CheckBox.icon.focusedSelectedBackground", hex(FLAME)),
            Map.entry("CheckBox.icon.hoverSelectedBackground", hex(FLAME)),
            Map.entry("ScrollBar.width", "10"),
            Map.entry("ScrollBar.showButtons", "false"),
            Map.entry("ScrollBar.track", hex(NIGHT)),
            Map.entry("ScrollBar.thumb", hex(BORDER)),
            Map.entry("ScrollBar.hoverThumbColor", hex(SUBTEXT)),
            Map.entry("ScrollBar.pressedThumbColor", hex(BRASS)),
            Map.entry("ScrollBar.thumbArc", "999"),
            Map.entry("ScrollBar.thumbInsets", "2,2,2,2"),
            Map.entry("ToolTip.background", hex(RAISED)),
            Map.entry("ToolTip.foreground", hex(TEXT))));
        com.formdev.flatlaf.FlatDarkLaf.setup();
        // The size of the window's own text, rather than the desktop's, for menus and dialogs too.
        // Derived from FlatLaf's font, which falls back to other fonts for signs Inter lacks.
        Font base = UIManager.getFont("defaultFont");
        if (base != null) UIManager.put("defaultFont", new FontUIResource(base.deriveFont(13f)));
    }

    private static String hex(Color colour) {
        return String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
    }

    /// The lamp, lit, for the window's icon.
    public static Image windowIcon() {
        return SvgIcon.of(LampSvgUtil.lamp(Genie.Phase.READY)).withIconSize(64, 64).getImage();
    }

    /// One of the ways to show the desktop, lit while it is the one in use.
    private static UIForButton<JButton> modeButton(String text, String tip, Val<Boolean> on) {
        return button(text).group(Skin.ICON_BUTTON).withTooltip(tip)
                .withStyle(on, (lit, it) -> it.borderRadius(9).padding(3, 10, 3, 10)
                    .backgroundColor(lit ? RAISED : TRANSPARENT)
                    .componentFont(f -> f.family(FONT).size(12).weight(lit ? 2f : 1f).color(lit ? TEXT : SUBTEXT)));
    }

    private static UIForButton<JButton> zoomButton(String text, String tip) {
        return button(text).group(Skin.ICON_BUTTON).withTooltip(tip)
                .withStyle(it -> it.componentFont(f -> f.family(FONT).size(12).color(TEXT)));
    }

    /// A button's words while the header has room for them, and just its sign otherwise.
    private Val<String> worded(String wide, String narrow) {
        return state.viewAsString(it -> it.roomForWords() ? wide : narrow);
    }

    /// What can be done with a genie beyond its everyday buttons: behind "⋯" in the header,
    /// and a right-click on its card. Deleting it is last, away from the rest.
    private JPopupMenu genieMenu(UUID id) {
        return UI.popupMenu().applyIfPresent(state.get().find(id).map( shown -> ui -> {
                Genie.Phase now = shown.phase();
                boolean idle = now != Genie.Phase.WORKING && now != Genie.Phase.WAKING;
                ui.add(ViewPartsUtil.item("Rename…", true, () -> rename(id)))
                .add(ViewPartsUtil.item("New conversation", idle, () -> actions.startAfresh(id)))
                .peek(JPopupMenu::addSeparator)
                .applyIf(now.isAwake(), ui1 -> ui1
                    .add(ViewPartsUtil.item("Sleep", true, () -> actions.sleep(id)))
                )
                .applyIf(!now.isAwake(), ui1 -> ui1
                    .add(ViewPartsUtil.item(now == Genie.Phase.BROKEN ? "Try waking again" : "Wake", now != Genie.Phase.WAKING, () -> actions.wake(id)))
                )
                .peek(JPopupMenu::addSeparator)
                .add(
                    UI.of(ViewPartsUtil.item("Delete " + shown.name() + "…", true, () -> confirmDelete(id)))
                    .withForeground(TROUBLE)
                );
            }))
            .get(JPopupMenu.class);
    }

    private void rename(UUID id) {
        state.get().find(id).ifPresent(named -> {
            Object answer = JOptionPane.showInputDialog(this, "A new name for " + named.name() + ":",
                    "Rename a genie", JOptionPane.PLAIN_MESSAGE, null, null, named.name());
            if (answer instanceof String text && !text.isBlank())
                state.update(From.VIEW, it -> it.update(id, genie -> genie.withName(text.strip())));
        });
    }

    private void confirmDelete(UUID id) {
        state.get().find(id).ifPresent(doomed -> {
            ConfirmAnswer answer = UI.confirmation("Delete " + doomed.name() + " for good? Its home, everything it made "
                    + "and your conversations with it are deleted.").titled("Delete a genie").yesOption("Delete").noOption("Keep").cancelOption("").show();
            if (answer == ConfirmAnswer.YES) actions.delete(id);
        });
    }

    /// Lets the user change a question they asked, in a dialog holding the question as it was.
    private void askInstead(Entry question) {
        JTextArea text = new JTextArea(question.text(), 6, 48);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(text),
                "Ask " + genie.get().name() + " differently", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        String changed = text.getText().strip();
        if (answer == JOptionPane.OK_OPTION && !changed.isEmpty() && !changed.equals(question.text().strip()))
            actions.askInstead(question.ref(), changed);
    }

    private void giveFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Give " + genie.get().name() + " a file");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            actions.giveFile(genie.get().id(), chooser.getSelectedFile().toPath());
    }

    private void saveHandout(String file) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save " + file);
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), file));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path target = chooser.getSelectedFile().toPath();
            actions.saveOutboxFile(genie.get().id(), file, target);
        }
    }

    /// Moves the thinking bars for as long as the genie on show works.
    private void breathe() {
        UI.animateFor(1.2, TimeUnit.SECONDS)
          .asLongAs(status -> genie.get().phase() == Genie.Phase.WORKING)
          .go(status -> pulse.set(status.progress()));
    }
}
