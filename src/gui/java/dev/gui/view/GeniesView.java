package dev.gui.view;

import dev.gui.model.*;
import sprouts.*;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForButton;
import swingtree.UIForPanel;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.api.IconDeclaration;
import swingtree.api.Layout;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.input.Keyboard;
import swingtree.layout.FlowCell;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;
import swingtree.layout.Size;
import swingtree.style.SvgIcon;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// The Genies window: the genies on the left, the conversation with the selected one in the
/// middle, and its desktop next to it when the user wants to watch. In a narrow window, as on a
/// phone, everything is one column: the genies on top, the selected one below.
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
    private final Var<Fold> genieList;
    private final Var<Boolean> settingsShown;
    private final Var<Tuple<Genie>> genies;
    private final Var<Tuple<Entry>> entries;
    private final Val<Tuple<Handout>> handouts;
    private final Val<Genie.Phase> phase;
    private final Val<UUID> selected;
    private final Val<UUID> watched;
    private final Var<DesktopZoom> zoom;
    /// Loops from 0 to 1 while any genie works: it moves the thinking bars, and the genies
    /// that think or work.
    private final Var<Double> pulse = Var.of(0.0);
    /// Whether the pulse loops, so that only one loop ever sets it.
    private boolean breathing = false;
    private final ChatRows rows;
    private final SchedulePage schedulePage;
    private final HistoryPage historyPage;
    /// Whether the draft is setting the composer's text, which then is not written back into it.
    private boolean settingDraft = false;
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
        genieList    = state.zoomTo(GeniesState::genieList, GeniesState::withGenieList);
        // Leaving the settings goes back to the chat, as their Done does.
        settingsShown = page.zoomTo(it -> it == GeniesState.Page.SETTINGS, (it, shown) -> shown ? GeniesState.Page.SETTINGS
                                                                       : it == GeniesState.Page.SETTINGS ? GeniesState.Page.CHAT : it);
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
        Viewable.cast(genies).onChange(From.ALL, it -> {
            if (anyWorks()) breathe();
        });
        rows = new ChatRows(look, genie, this::saveHandout, pulse, this::askInstead,
                           phase.viewAs(Boolean.class, it -> it == Genie.Phase.READY));
        schedulePage = new SchedulePage(state, actions, look);
        historyPage = new HistoryPage(state, actions, look);

        UI.use(look, () ->
            of(this).group(Skin.FRAME)
            .withLayout(state.viewAs(Layout.class, it -> it.narrow() ? ONE_COLUMN : SIDE_BY_SIDE))
            .withPrefSize(1280, 820)
            .withMinSize(0, 0)
            .onResize(it -> state.update(From.VIEW, s -> s.withViewWidth(it.getWidth())))
            .add(sidebar())
            .add(main())
        );
    }

    /// The genies beside the selected one, or, in a narrow window, above it. The genie keeps
    /// some height whatever the list's, so the list cannot push it out of the window.
    private static final Layout SIDE_BY_SIDE = Layout.mig("fill, ins 0, gap 0, hidemode 3",
            MigAddConstraint.of("growy, width 250!"), MigAddConstraint.of("grow, push, wmin 0"));
    private static final Layout ONE_COLUMN = Layout.mig("fill, wrap 1, ins 0, gap 0, hidemode 3",
            MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("grow, push, wmin 0, hmin 160"));

    // ─── the sidebar: every genie ──────────────────────────────────────────────────────────

    /// Lets go of the desktop on show, which gets its own size back if it had the panel's. The
    /// thread doing that, for an application that is about to end and must wait for it.
    public Optional<Thread> letGoOfTheDesktop() {
        return desktop.letGo();
    }

    private static String troubleWords(int troubles) {
        return troubles == 1 ? "⚠  Something went wrong" : "⚠  " + troubles + " things went wrong";
    }

    /*
     *  The list of genies has two arrangements of the same parts, chosen by the window's width:
     *
     *      beside the genie                    above it, in a narrow window
     *
     *      ┌──────────────────┐                ┌──────────────────────────────────────┐
     *      │ ☰ (lamp) Genies  │                │ ☰ (lamp) Genies        [+ New]  [⚙]  │
     *      │ [+ New genie]    │                │ ┌──────────────────────────────────┐ │
     *      │ YOUR GENIES      │                │ │ the genies' cards, scrolling,    │ │
     *      │ ┌──────────────┐ │                │ │ as tall as the user dragged      │ │
     *      │ │ the genies'  │ │                │ └──────────────────────────────────┘ │
     *      │ │ cards        │ │                │ ────────────── grip ──────────────── │
     *      │ └──────────────┘ │                │ what went wrong, if anything         │
     *      │ what went wrong  │                └──────────────────────────────────────┘
     *      │ [⚙ Settings]     │
     *      └──────────────────┘
     *
     *  Each part is placed in a cell of the grid, so the parts are made once, and moving between
     *  the two is a change of the layout's value. The height above is the list's fold: dragging
     *  the grip to the top folds the list away, as does ☰.
     *
     *  ☰ shows and hides the list. It is in the top left corner of the window either way: here
     *  while the list is shown, in the genie's header while it is not. So it stays under the
     *  pointer, and a second press undoes the first.
     */
    private static final Layout BESIDE_THE_GENIE =
            Layout.mig("fill, ins 0, gap 10, hidemode 3",
                MigAddConstraint.of("cell 0 0, growx, wmin 0"),             // ☰, the lamp and "Genies"
                MigAddConstraint.of("cell 0 1, growx"),                     // a new genie
                MigAddConstraint.of("cell 0 6, growx"),                     // the settings
                MigAddConstraint.of("cell 0 2, growx, gaptop 6"),           // "YOUR GENIES"
                MigAddConstraint.of("cell 0 3, grow, push, wmin 0, hmin 0"),// the cards
                MigAddConstraint.of("cell 0 4"),                            // the grip, hidden
                MigAddConstraint.of("cell 0 5, growx, wmin 0"));            // what went wrong

    /// The same parts, in the same order, as [#BESIDE_THE_GENIE].
    ///
    /// @param height the most the cards' area takes: the list's fold
    private static Layout aboveTheGenie(int height) {
        return Layout.mig(LayoutConstraint.of("fill, ins 0, gap 8 6, hidemode 3"), LayoutConstraint.of("[grow][][]"), LayoutConstraint.of(""))
            .withChildConstraints(
                MigAddConstraint.of("cell 0 0, growx, wmin 0"),
                MigAddConstraint.of("cell 1 0"),
                MigAddConstraint.of("cell 2 0"),
                MigAddConstraint.of("cell 0 4"),
                MigAddConstraint.of("cell 0 1, span 3, growx, wmin 0, h 0:pref:" + height),
                MigAddConstraint.of("cell 0 2, span 3, growx, h 9!"),
                MigAddConstraint.of("cell 0 3, span 3, growx, wmin 0"));
    }

    private UIForAnySwing<?, ?> sidebar() {
        Val<Boolean> narrow = state.viewAs(Boolean.class, GeniesState::narrow);
        Val<Boolean> wide = narrow.viewAs(Boolean.class, it -> !it);
        JScrollPane[] cards = new JScrollPane[1];
        return
            panel().group(Skin.SIDEBAR)
            .withLayout(state.viewAs(Layout.class, it -> it.narrow() ? aboveTheGenie(it.genieList().height()) : BESIDE_THE_GENIE))
            .withStyle(narrow, (on, it) -> on ? it.borderAt(UI.Edge.RIGHT, 0, TRANSPARENT).borderAt(UI.Edge.BOTTOM, 1, BORDER).padding(12, 12, 2, 12) : it)
            .isVisibleIf(genieList.viewAs(Boolean.class, Fold::shown))
            .add(
                // Inset so that ☰ is where the genie's header has it.
                box("fill, ins 0 4 0 0, gap 8", "[][34!][grow]")
                .add(
                    button("").group(Skin.ICON_BUTTON)
                    .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.GENIES, SUBTEXT)))
                    .withTooltip("Hide your genies")
                    .onClick(it -> genieList.update(From.VIEW, Fold::toggled)))
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.READY), 34))
                .add("growx, wmin 0", label("Genies").group(Skin.BRAND)))
            .add(
                button(narrow.viewAsString(it -> it ? "New" : "New genie")).group(Skin.FLAME_BUTTON).withIconTextGap(6)
                .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.NEW, ON_FLAME)))
                .withTooltip("A new genie, with a sandboxed desktop of its own")
                .onClick(it -> actions.newGenie()))
            .add(
                toggleButton(narrow.viewAsString(it -> it ? "" : "Settings"), settingsShown).group(Skin.QUIET_BUTTON).withIconTextGap(6)
                .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.SETTINGS, TEXT)))
                .withTooltip("Settings: the model every genie uses. Pressed again, back to the genie"))
            .add(label("YOUR GENIES").group(Skin.SECTION).isVisibleIf(wide))
            .add(
                // As tall as the cards, so that above the genie a few of them take only their room.
                scrollPanels(conf -> conf.fitWidth(true).unitIncrement(16).prefSize(Size.of(conf.view().getPreferredSize())))
                .group(Skin.PAGE_SCROLL).withMinSize(0, 0).withEmptyBorder(0)
                .peek(it -> cards[0] = it)
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .addAll(genies, this::genieChip))
            .add(grip(genieList, () -> cards[0].getHeight(), narrow, "Drag to see more of your genies, or up to fold them away"))
            .add(
                box("fill, wrap 1, ins 0, gap 6, hidemode 3")
                .add("growx",
                    button(state.viewAsString(it -> troubleWords(it.troubles().size()))).group(Skin.QUIET_BUTTON)
                    .withStyle(it -> it.backgroundColor(TROUBLE_WASH).border(1, TROUBLE).componentFont(f -> f.color(TROUBLE)))
                    .isVisibleIf(state.viewAs(Boolean.class, it -> !it.troubles().isEmpty()))
                    .withTooltip("Genies carried on. Click to see what happened")
                    .onClick(it -> showTroubles()))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(state.viewAsString(it -> it.settingsProblem().orElse("")), TROUBLE,
                        state.viewAs(Boolean.class, it -> it.settingsProblem().isPresent()))));
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
        // Awake, the genie is out of its lamp, in the card's top right corner beside its name,
        // twice its twenty pixels. Asleep, waking or broken, it is in the lamp, so not shown.
        Val<Boolean> out = shown.viewAs(Boolean.class, it -> it.phase().isAwake());
        Val<IconDeclaration> picture = Viewable.of(IconDeclaration.class, shown, pulse, (it, progress) -> {
            if (!it.phase().isAwake()) return GenieSvgUtil.NONE;
            GenieSvgUtil.Pose pose = GenieSvgUtil.poseOf(it);
            return GenieSvgUtil.genie(GenieSvgUtil.appearanceOf(it.id()), pose, GenieSvgUtil.frameAt(pose, progress));
        });
        return
            panel("fill, ins 7 8 7 8, gap 8", "[26!][grow]")
            .withStyle(isSelected, (on, it) -> it
                .backgroundColor(on ? RAISED : TRANSPARENT)
                .border(1, on ? BORDER : TRANSPARENT)
                // Always there, lit when selected, so selecting a genie moves nothing.
                .borderAt(UI.Edge.LEFT, 3, on ? FLAME : TRANSPARENT)
                .borderRadius(10))
            // Painted on the card rather than added to it, so it moves none of the card's parts.
            .withStyle(picture, (icon, it) -> it
                .image(img -> img.image(icon).placement(UI.Placement.TOP_RIGHT).size(46, 46).padding(5, 6, 1, 0)))
            .withCursor(UI.Cursor.HAND)
            .withTooltip(shown.viewAsString(it -> it.name() + " — " + it.status()
                    + (it.showing().isEmpty() ? "" : ". Shows you: " + it.showing()) + ". Right-click for more."))
            .onMousePress(it -> {
                state.update(From.VIEW, s -> s.select(id));
                if (it.isRightMouseButton()) genieMenu(id).show(it.getComponent(), it.mouseX(), it.mouseY());
            })
            .add("top", ViewPartsUtil.lamp(shown.viewAs(Genie.Phase.class, Genie::phase), 26))
            .add("growx, wmin 0, wrap",
                box("fill, wrap 1, ins 0, gap 0, hidemode 3")
                // Room for the genie, while it is out, so a long name ends before it.
                .withStyle(out, (room, it) -> it.padding(0, room ? 42 : 0, 0, 0))
                .add("growx, wmin 0", label(shown.viewAsString(Genie::name)).withStyle(it -> it
                    .componentFont(f -> f.family(FONT).size(13).weight(2f).color(TEXT))))
                .add("growx, wmin 0", label(shown.viewAsString(Genie::status)).group(Skin.META))
                .add("growx, wmin 0", label(shown.viewAsString(it -> "▣  shows you: " + it.showing()))
                     .isVisibleIf(showsElsewhere)
                     .withStyle(it -> it.componentFont(f -> f.family(FONT).size(11).color(FLAME)))))
            .add("span 2, growx, wmin 0", conversationsOf(shown));
    }

    // ─── a genie's conversations ───────────────────────────────────────────────────────────

    /// The genie's conversations, under its card, as two trees: those the runs of its scheduled
    /// jobs had, and the user's own, with ＋ on its line for a new one. Each opens from a line
    /// saying how many. A conversation is a row, and below it are its branches, one for each
    /// question asked differently. Clicking a row goes there; the row the genie is on is
    /// selected. Under both trees, since it can be in either, the one it is on can be deleted.
    private UIForAnySwing<?, ?> conversationsOf(Var<Genie> shown) {
        UUID id = shown.get().id();
        Var<Conversations> conversations = shown.zoomTo(Genie::conversations, Genie::withConversations);
        Var<Fold> chats = conversations.zoomTo(Conversations::chatsFold, Conversations::withChatsFold);
        Var<Fold> jobs = conversations.zoomTo(Conversations::jobsFold, Conversations::withJobsFold);
        // The trees can be gone through while the genie answers; a new conversation waits for the answer.
        Val<Boolean> browsable = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WAKING);
        Val<Boolean> idle = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING && it.phase() != Genie.Phase.WAKING);
        Val<Boolean> canForget = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING
                && it.phase() != Genie.Phase.WAKING && it.conversations().current().isPresent());
        Val<Boolean> eitherOpen = Viewable.of(Boolean.class, chats, jobs, (c, j) -> c.shown() || j.shown());
        return
            box("fill, wrap 1, ins 0, gap 2, hidemode 3", "[grow]")
            .add("growx, wmin 0",
                tree(id, jobs, conversations.viewAsString(it -> howMany(it.jobCount(), "scheduled run", "")),
                     "Show or hide the conversations the runs of this genie's scheduled jobs had",
                     conversations.viewAs(Boolean.class, it -> it.jobCount() > 0),
                     conversations.viewAs(Tuple.classTyped(Talk.class), Conversations::jobRuns), browsable, Optional.empty()))
            .add("growx, wmin 0, gaptop 4",
                tree(id, chats, conversations.viewAsString(it -> howMany(it.chatCount(), "conversation", "no conversations yet")),
                     "Show or hide your conversations with this genie", Val.of(true),
                     conversations.viewAs(Tuple.classTyped(Talk.class), Conversations::chats), browsable,
                     Optional.of(button("").group(Skin.ICON_BUTTON).isEnabledIf(idle)
                         .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.NEW, SUBTEXT)).padding(1, 6, 1, 6))
                         .withTooltip("Start a new conversation with this genie; the others are kept")
                         .onClick(it -> actions.startAfresh(id)))))
            .add("left, gaptop 2", button("Delete…").group(Skin.QUIET_BUTTON).isEnabledIf(canForget)
                 .isVisibleIf(eitherOpen)
                 .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.DELETE, SUBTEXT)))
                 .withTooltip("Delete the conversation this genie is in, with all its branches")
                 .onClick(it -> confirmForget(id)));
    }

    /// One tree of conversations: the line that opens it, then the tree in an area of the fold's
    /// height, which scrolls when the tree is taller, and a grip under it that the user drags to
    /// make the area taller or shorter.
    ///
    /// @param beside at the end of the line that opens it, such as a button that adds a row
    private UIForAnySwing<?, ?> tree(UUID id, Var<Fold> fold, Val<String> count, String tip,
                                     Val<Boolean> present, Val<Tuple<Talk>> rows, Val<Boolean> browsable,
                                     Optional<UIForAnySwing<?, ?>> beside) {
        Val<Boolean> open = fold.viewAs(Boolean.class, Fold::shown);
        Val<Tuple<String>> here = rows.viewAs(Tuple.classTyped(String.class), Conversations::pathToHere);
        JScrollPane[] area = new JScrollPane[1];
        // A scroll pane lays out only what is inside it: when the tree grows, shrinks or is shown
        // or hidden, Swing marks the card as needing a new layout but never gives it one. A later
        // layout may then still reserve the tree's old room. Told here, the card is laid out anew.
        ComponentAdapter relayout = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { area[0].getParent().revalidate(); }
            @Override public void componentShown(ComponentEvent event)   { area[0].getParent().revalidate(); }
            @Override public void componentHidden(ComponentEvent event)  { area[0].getParent().revalidate(); }
        };
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .isVisibleIf(present)
            .add("growx, wmin 0",
                box("fill, ins 0, gap 0", "[grow][]")
                .add("growx, wmin 0",
                    label(Viewable.of(String.class, open, count, (on, words) -> (on ? "▾  " : "▸  ") + words))
                    .group(Skin.META).withCursor(UI.Cursor.HAND)
                    .withTooltip(tip)
                    .onMouseClick(it -> fold.update(From.VIEW, Fold::toggled)))
                .applyIfPresent(beside.map(it -> line -> line.add(it))))
            .add("growx, wmin 0, hmin 0",
                scrollPane(conf -> conf.fitWidth(true)).withEmptyBorder(0).withMinSize(0, 0)
                .peek(it -> area[0] = it)
                .peek(it -> it.addComponentListener(relayout))
                .isVisibleIf(open)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withVerticalScrollIncrement(16)
                // At most the fold's height; a tree of a few rows takes only what it needs.
                .withMaxHeight(fold.viewAs(Integer.class, Fold::height))
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add(
                    // In a panel of its own: on its own, a tree asks its scroll pane for room for
                    // twenty rows, however many it has.
                    panel("fill, ins 0").withStyle(it -> it.backgroundColor(TRANSPARENT))
                    .peek(it -> it.addComponentListener(relayout))
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
            .add("growx, wmin 0, h 9!", grip(fold, () -> area[0].getHeight(), open, "Drag to make this list taller or shorter"));
    }

    /// The grip under a list that folds: the user drags it to make the list's area taller or
    /// shorter, and all the way up to fold the list away.
    ///
    /// @param areaHeight how tall the list's area is now, which for a short list is less than
    ///                   the fold's height
    private static UIForAnySwing<?, ?> grip(Var<Fold> fold, IntSupplier areaHeight, Val<Boolean> shown, String tip) {
        // Where a drag began: the pointer's height on the screen, the area's, and the fold's.
        int[] dragFrom = new int[3];
        return
            panel().withCursor(UI.Cursor.RESIZE_BOTTOM)
            .isVisibleIf(shown)
            .withTooltip(tip)
            .withStyle(it -> {
                int width = it.componentWidth(), height = it.componentHeight();
                return it.backgroundColor(TRANSPARENT).painter(UI.Layer.CONTENT, g -> grip(g, width, height));
            })
            .onMousePress(it -> {
                dragFrom[0] = it.mouseYOnScreen();
                dragFrom[1] = areaHeight.getAsInt();
                dragFrom[2] = fold.get().height();
            })
            .onMouseDrag(it -> fold.update(From.VIEW, f -> f.withHeight(dragFrom[1] + it.mouseYOnScreen() - dragFrom[0])))
            .onMouseRelease(it -> fold.update(From.VIEW, f -> f.released(dragFrom[2])));
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

    // ─── the main area: a conversation, a schedule, a history, or the settings ─────────────

    private UIForAnySwing<?, ?> main() {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> onSchedule = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SCHEDULE);
        Val<Boolean> onHistory = page.viewAs(Boolean.class, it -> it == GeniesState.Page.HISTORY);
        Val<Boolean> onSettings = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SETTINGS);
        Val<Boolean> hasGenies = state.viewAs(Boolean.class, GeniesState::hasGenies);
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0", header(Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> !a && b)))
            .add("grow, push, wmin 0", conversation(Viewable.of(Boolean.class, onChat, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", schedulePage.view(Viewable.of(Boolean.class, onSchedule, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", historyPage.view(Viewable.of(Boolean.class, onHistory, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", firstGenie(Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> !a && !b)))
            .add("grow, push, wmin 0", SettingsPage.of(state, actions, onSettings));
    }

    private UIForAnySwing<?, ?> header(Val<Boolean> visible) {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> awake = Viewable.of(Boolean.class, phase, onChat, (it, chat) -> it.isAwake() && chat);
        Val<Boolean> working = phase.viewAs(Boolean.class, it -> it == Genie.Phase.WORKING);
        Val<Boolean> wide = state.viewAs(Boolean.class, GeniesState::roomForWords);
        Val<Boolean> roomForPages = state.viewAs(Boolean.class, GeniesState::roomForPages);
        // The list of genies says when something went wrong; while it is hidden, the header does.
        Val<Boolean> hiddenTroubles = state.viewAs(Boolean.class, it -> !it.genieList().shown() && !it.troubles().isEmpty());
        return
            panel("fill, ins 0, gap 10, hidemode 3", "[]0[]0[30!][grow][]").group(Skin.HEADER)
            .isVisibleIf(visible)
            // While the list is shown, ☰ is in its top row instead, in the same corner. Each part
            // has its cell, and the hidden ones' gaps go with them, so the others keep their places.
            .add("cell 0 0, gapright 10", button("").group(Skin.ICON_BUTTON)
                 .isVisibleIf(genieList.viewAs(Boolean.class, it -> !it.shown()))
                 .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.GENIES, SUBTEXT)))
                 .withTooltip("Show your genies")
                 .onClick(it -> genieList.update(From.VIEW, Fold::toggled)))
            // Not this genie's: what went wrong anywhere in Genies, so it stays beside ☰.
            .add("cell 1 0, gapright 10", button(state.viewAsString(it -> "⚠ " + it.troubles().size())).group(Skin.QUIET_BUTTON)
                 .isVisibleIf(hiddenTroubles)
                 .withStyle(it -> it.backgroundColor(TROUBLE_WASH).border(1, TROUBLE).padding(3, 8, 3, 8).componentFont(f -> f.color(TROUBLE)))
                 .withTooltip("Something went wrong in Genies, not only in this genie. Click to see what happened")
                 .onClick(it -> showTroubles()))
            .add("cell 2 0", ViewPartsUtil.lamp(phase, 30))
            .add("cell 3 0, growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 0")
                .add("growx, wmin 0", label(name).group(Skin.TITLE)
                     .withTooltip("Double-click to rename")
                     .onMouseClick(it -> { if (it.clickCount() == 2) rename(genie.get().id()); }))
                .add("growx, wmin 0", label(genie.viewAsString(Genie::status)).group(Skin.SUBTITLE)))
            // One group on the right, so buttons that are hidden leave no gap behind.
            .add("cell 4 0", box("ins 0, gap 10, hidemode 3, aligny center")
            .add(pages())
            .add(label(genie.viewAsString(it -> it.tokens() == 0 ? "" : String.format("%,d tokens", it.tokens()))).group(Skin.META)
                 .isVisibleIf(wide)
                 .withTooltip("Tokens the model counted for this genie since Genies started"))
            .add(toggleButton(worded("Desktop"), desktopShown).group(Skin.QUIET_BUTTON).isVisibleIf(awake)
                 .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.DESKTOP, TEXT)))
                 .withTooltip("Desktop: watch the genie's desktop, and use it"))
            .add(button(worded("Stop")).group(Skin.QUIET_BUTTON).isVisibleIf(working)
                 .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.STOP, TEXT)))
                 .withTooltip("Stop what the genie is doing")
                 .onClick(it -> actions.stop(genie.get().id())))
            .add(button(worded("Sleep")).group(Skin.QUIET_BUTTON).isVisibleIf(phase.viewAs(Boolean.class, Genie.Phase::isAwake))
                 .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.SLEEP, TEXT)))
                 .withTooltip("Sleep: end the genie's sandbox. Its home and this conversation are kept.")
                 .onClick(it -> actions.sleep(genie.get().id())))
            .add(button("").group(Skin.ICON_BUTTON)
                 .withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.MORE, SUBTEXT)))
                 .withTooltip(roomForPages.viewAsString(it -> (it ? "" : "Chat, schedule or history; ")
                         + "rename, save, wake or sleep, or delete this genie"))
                 .onMousePress(it -> ViewPartsUtil.toggleBelow(it.getEvent(), () -> genieMenu(genie.get().id())))
                 .onPressed(Keyboard.Key.SPACE, it -> ViewPartsUtil.toggleBelow(it.getEvent(), () -> genieMenu(genie.get().id())))));
    }

    /// The pages of a genie, its chat, its schedule and its history, as one switch of three
    /// parts. The schedule's part says how many jobs it has, once they were read. The header
    /// makes room in steps as it narrows: first the parts drop their words and keep their signs,
    /// as the header's other buttons do; then the switch steps aside for the menu behind "⋯",
    /// which has the pages too.
    private UIForAnySwing<?, ?> pages() {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> onSchedule = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SCHEDULE);
        Val<Boolean> onHistory = page.viewAs(Boolean.class, it -> it == GeniesState.Page.HISTORY);
        Val<Boolean> wide = state.viewAs(Boolean.class, GeniesState::roomForWords);
        Val<String> scheduleWords = Viewable.of(String.class, genie, wide, (shown, words) -> {
            int jobs = shown.schedule().jobs().size();
            return (words ? "Schedule  " : "") + (jobs == 0 ? "" : jobs);
        });
        return
            box("ins 2, gap 2")
            .isVisibleIf(state.viewAs(Boolean.class, GeniesState::roomForPages))
            .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(11))
            .add(half(worded("Chat"), SignSvgUtil.CHAT, onChat).withTooltip("Chat: talk with the genie")
                 .onClick(it -> page.set(From.VIEW, GeniesState.Page.CHAT)))
            .add(half(scheduleWords, SignSvgUtil.SCHEDULE, onSchedule).withTooltip("Schedule: when jobs wake the genie, and what they did")
                 .onClick(it -> page.set(From.VIEW, GeniesState.Page.SCHEDULE)))
            .add(half(worded("History"), SignSvgUtil.HISTORY, onHistory).withTooltip("History: the moments the genie's home was saved at, and going back to one")
                 .onClick(it -> page.set(From.VIEW, GeniesState.Page.HISTORY)));
    }

    /// One part of the switch between pages: its sign, and its words while there is room.
    private static UIForButton<JButton> half(Val<String> text, String sign, Val<Boolean> shown) {
        return button(text).group(Skin.ICON_BUTTON).withIconTextGap(6)
                .withStyle(shown, (on, it) -> it.borderRadius(9).padding(4, 10, 4, 10)
                    .icon(SignSvgUtil.sign(sign, on ? TEXT : SUBTEXT))
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
            // No row constraints: the line on what the genie shows is hidden at times, which moves
            // the rows under it up. Only the desktop's own "push" makes its row grow.
            panel("fill, wrap 1, ins 8 12 12 12, gap 6, hidemode 3", "[grow]")
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
                scrollPane()
                .withEmptyBorder(0).withMinSize(0, 0)
                .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(10))
                .add(UI.of(desktop)));
    }

    private UIForAnySwing<?, ?> transcript() {
        Val<Boolean> empty = entries.viewAs(Boolean.class, Tuple::isEmpty);
        // Working, with nothing streaming in: a bar says what the user waits for. The pulse, which
        // moves while the genie works, also moves on the time a job has run.
        Val<Boolean> waiting = genie.viewAs(Boolean.class, it -> it.phase() == Genie.Phase.WORKING
                && it.conversations().aside().isEmpty()
                && (it.transcript().isEmpty() || !it.transcript().entries().last().isWriting()));
        Val<Genie.Waiting> waitingOn = Viewable.of(Genie.Waiting.class, genie, pulse, (it, ignored) -> it.waitingOn(Instant.now()));
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
            .add("growx, wmin 0", rows.waiting(waitingOn, waiting))
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
                        // Bound here rather than with textArea(draft): when the draft changes,
                        // SwingTree 1.0.0 sets the text with Swing's own listeners taken off the
                        // text, so the caret and the drawn lines keep the old text, and letters typed
                        // after sending are not drawn. Here, Swing's listeners stay; only the
                        // writing back into the draft is held off while the draft sets the text.
                        textArea(draft.get()).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                        .peek(area -> Viewable.cast(draft).onChange(From.ALL, it -> {
                            String text = it.currentValue().orElse("");
                            if (area.getText().equals(text)) return;
                            settingDraft = true;
                            area.setText(text);
                            settingDraft = false;
                        }))
                        .onTextChange(it -> {
                            if (!settingDraft) draft.set(From.VIEW, it.getComponent().getText());
                        })
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
            // Halfway between the subtext and the borders, so what cannot be used now steps back.
            Map.entry("@disabledForeground", "#716769"),
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
            // Behind the sign of the page the user is on, in a genie's menu, as in the header's switch.
            Map.entry("MenuItem.checkBackground", hex(BORDER)),
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

    /// A button's words while the header has room for them; its sign alone says it otherwise.
    private Val<String> worded(String words) {
        return state.viewAsString(it -> it.roomForWords() ? words : "");
    }

    /// What can be done with a genie beyond its everyday buttons: behind "⋯" in the header,
    /// and a right-click on its card, which selects the genie first. While the header has no
    /// room for the switch between its pages, they come first; deleting it is last, away from
    /// the rest.
    private JPopupMenu genieMenu(UUID id) {
        GeniesState.Page on = state.get().page();
        return UI.popupMenu().applyIfPresent(state.get().find(id).map( shown -> ui -> {
                Genie.Phase now = shown.phase();
                boolean idle = now != Genie.Phase.WORKING && now != Genie.Phase.WAKING;
                ui.applyIf(!state.get().roomForPages(), pages -> pages
                .add(ViewPartsUtil.choice("Chat", SignSvgUtil.sign(SignSvgUtil.CHAT, on == GeniesState.Page.CHAT ? TEXT : SUBTEXT), on == GeniesState.Page.CHAT, () -> page.set(From.VIEW, GeniesState.Page.CHAT)))
                .add(ViewPartsUtil.choice("Schedule", SignSvgUtil.sign(SignSvgUtil.SCHEDULE, on == GeniesState.Page.SCHEDULE ? TEXT : SUBTEXT), on == GeniesState.Page.SCHEDULE, () -> page.set(From.VIEW, GeniesState.Page.SCHEDULE)))
                .add(ViewPartsUtil.choice("History", SignSvgUtil.sign(SignSvgUtil.HISTORY, on == GeniesState.Page.HISTORY ? TEXT : SUBTEXT), on == GeniesState.Page.HISTORY, () -> page.set(From.VIEW, GeniesState.Page.HISTORY)))
                .peek(JPopupMenu::addSeparator))
                .add(ViewPartsUtil.item("Rename…", true, () -> rename(id)))
                .add(ViewPartsUtil.item("New conversation", idle, () -> actions.startAfresh(id)))
                .add(ViewPartsUtil.item("Save now…", shown.history().busy().isEmpty(), () -> historyPage.save(this)))
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

    /// The troubles, newest first, each with its stack trace, which the user can select and copy.
    /// Once seen, they are off the window; the error log keeps them.
    private void showTroubles() {
        Tuple<Trouble> seen = state.get().troubles();
        if (seen.isEmpty()) return;
        StringBuilder details = new StringBuilder();
        for (int i = seen.size() - 1; i >= 0; i--) details.append(seen.get(i).details()).append('\n');
        String what = seen.size() == 1 ? "Something went wrong that Genies did not expect."
                                       : seen.size() + " things went wrong that Genies did not expect.";
        UI.dialog(SwingUtilities.getWindowAncestor(this), "What went wrong")
            .withOnCloseOperation(UI.OnWindowClose.DISPOSE)
            .onClosed(it -> state.update(From.VIEW, now -> now.withoutTroubles(seen)))
            .add(UI.use(look, () ->
                panel("fill, wrap 1, ins 16 18 16 18, gap 10", "[grow]", "[][][grow][]").group(Skin.FRAME)
                .add("growx, wmin 0", label(what).group(Skin.TITLE))
                .add("growx, wmin 0", label("Genies carried on. All of it is kept in " + actions.errorLog()
                                            + ", which helps whoever fixes it.").group(Skin.META))
                .add("grow, push, w 760, h 380",
                    scrollPane().add(textArea(details.toString()).group(Skin.INPUT)
                        .withStyle(it -> it.componentFont(f -> f.family(MONO).size(12)))
                        .peek(area -> { area.setEditable(false); area.setCaretPosition(0); })))
                .add("right",
                    button("Close").group(Skin.QUIET_BUTTON)
                    .onClick(it -> SwingUtilities.getWindowAncestor(it.getComponent()).dispose()))
                .get(JPanel.class)))
            .show();
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

    /// Loops the pulse for as long as any genie works.
    private void breathe() {
        if (breathing) return;
        breathing = true;
        UI.animateFor(1.2, TimeUnit.SECONDS)
          .asLongAs(status -> anyWorks())
          .go(new Animation() {
              @Override public void run(AnimationStatus status) { pulse.set(status.progress()); }
              @Override public void finish(AnimationStatus status) { breathing = false; }
          });
    }

    private boolean anyWorks() {
        return genies.get().stream().anyMatch(it -> it.phase() == Genie.Phase.WORKING);
    }
}
