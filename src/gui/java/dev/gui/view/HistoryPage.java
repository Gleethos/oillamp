package dev.gui.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import dev.gui.model.DateWordingUtil;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.History;
import dev.gui.model.Recurrence;
import dev.gui.model.Schedule;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForPanel;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.api.Painter;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.layout.FlowCell;
import swingtree.style.StyledString;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// A genie's history: the moments oillamp saved its home at, newest first on a rail as the
/// schedule's timeline draws its week, and the way back to any of them.
///
/// Like the rest of the window, a function of the [GeniesState]: a lens onto the selected genie's
/// [History], and [Actions] for saving and going back.
final class HistoryPage {

    /*
     *  A responsive grid, like the schedule's: from LARGE up the moments and what a moment holds
     *  stand side by side; below that, the second follows the first.
     *
     *                        very small  small  medium  large  very large  oversize
     *      what is said on top    12       12     12      8        8          8
     *      its buttons            12       12     12      4        4          4
     *      the moments            12       12     12      7        7          7
     *      what a moment holds    12       12     12      5        5          5
     */
    private static final int REFERENCE = 1100;
    private static final FlowCell WHOLE = AUTO_SPAN(it -> it
            .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12));
    private static final FlowCell WIDE_SIDE = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(7).veryLarge(7).oversize(7));
    private static final FlowCell NARROW_SIDE = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(5).veryLarge(5).oversize(5));
    private static final FlowCell SAYING = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(8).veryLarge(8).oversize(8));
    private static final FlowCell BUTTONS = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(4).veryLarge(4).oversize(4));

    /// The rail's columns and where a node sits, as on the schedule's timeline.
    private static final int CLOCK = 52;
    private static final int RAIL = 24;
    private static final int NODE_Y = 15;

    private final Var<GeniesState> state;
    private final Actions actions;
    private final Look look;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<History> history;
    private final Val<Boolean> wide;
    /// Why the genie cannot go back now, in a few words; empty when it can.
    private final Val<String> notNow;

    HistoryPage(Var<GeniesState> state, Actions actions, Look look) {
        this.state = state;
        this.actions = actions;
        this.look = look;
        genie   = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        history = genie.zoomTo(Genie::history, Genie::withHistory);
        wide    = state.viewAs(Boolean.class, GeniesState::sideBySide);
        notNow  = genie.viewAsString(it -> !it.history().busy().isEmpty() ? it.history().busy()
                : it.phase() == Genie.Phase.WAKING ? it.name() + " is waking; go back once it is awake"
                : it.phase() == Genie.Phase.WORKING ? it.name() + " is working; go back once it is done"
                : it.schedule().running().isPresent() ? "A job is running; go back once it is done"
                : "");
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 18)
                .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
                .withStyle(it -> it.backgroundColor(TRANSPARENT).padding(20, 22, 28, 22))
                .add(WHOLE, top())
                .add(WHOLE, undo())
                .add(WIDE_SIDE, moments())
                .add(NARROW_SIDE, about()));
    }

    // ─── on top: what is saved when, and saving now ────────────────────────────────────────

    /// Whether the genie can go back now, as a coloured dot and a sentence.
    private record Status(Color colour, String words) {}

    private static Status status(Genie genie) {
        if (!genie.history().busy().isEmpty()) return new Status(BRASS, genie.history().busy());
        return switch (genie.phase()) {
            case READY -> new Status(CONTENT, "Saved whenever " + genie.name() + " wakes, answers or sleeps. "
                    + "Going back keeps how it is now, so you can always return.");
            case WORKING -> new Status(BRASS, genie.name() + " is working. You can go back once it is done.");
            case WAKING -> new Status(BRASS, genie.name() + " is waking. You can go back once it is awake.");
            case ASLEEP, BROKEN -> new Status(SUBTEXT, "Saved whenever " + genie.name() + " wakes, answers or sleeps. "
                    + "Going back keeps how it is now, so you can always return.");
        };
    }

    private UIForAnySwing<?, ?> top() {
        Val<Status> status = genie.viewAs(Status.class, HistoryPage::status);
        Val<String> problem = history.viewAsString(History::problem);
        Val<String> note = history.viewAsString(History::note);
        Val<Boolean> idle = history.viewAs(Boolean.class, it -> it.busy().isEmpty());
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 10)
            .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add(SAYING,
                box("fill, wrap 1, ins 0, gap 6, hidemode 3", "[grow]")
                .add(label("History").group(Skin.EMPTY_TITLE))
                .add("growx, wmin 0",
                    box("fill, ins 0, gap 8", "[10!][grow]")
                    .add("top, gaptop 5", ViewPartsUtil.dot(status.viewAs(Color.class, Status::colour), 8))
                    .add("growx, wmin 0", ViewPartsUtil.wrapped(status.viewAsString(Status::words), SUBTEXT, Val.of(true))))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(note, SUBTEXT, note.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(problem, TROUBLE, problem.viewAs(Boolean.class, it -> !it.isEmpty()))))
            .add(BUTTONS,
                // Right, beside what is said; left, under it, when there is no room beside it.
                panel(wide.viewAs(Layout.class, isWide -> Layout.flow(isWide ? UI.HorizontalAlignment.RIGHT : UI.HorizontalAlignment.LEFT, 8, 4)))
                .withStyle(wide, (isWide, it) -> it.backgroundColor(TRANSPARENT).padding(isWide ? 4 : 0, 0, 0, 0))
                .add(button("＋  Save now…").group(Skin.FLAME_BUTTON).isEnabledIf(idle)
                     .withTooltip("Keep how the genie is now as a moment to come back to, such as before a big change")
                     .onClick(it -> save(it.getComponent()))));
    }

    /// Right after going back: what happened, and the way to undo it.
    private UIForAnySwing<?, ?> undo() {
        // The newest going back: a genie woken again after it may have saved a moment since.
        Val<String> wentBack = Viewable.of(String.class, genie, state, (it, s) -> it.history().moments().stream()
                .filter(moment -> moment.kind() == History.Kind.WENT_BACK).findFirst()
                .map(moment -> it.history().title(moment, it, s.localNow())).orElse(""));
        Val<Boolean> shown = history.viewAs(Boolean.class, it -> it.busy().isEmpty() && it.undo().isPresent());
        return
            panel("fill, ins 10 14 10 14, gap 12, hidemode 3", "[][grow][]")
            .isVisibleIf(shown)
            .withStyle(it -> it.backgroundColor(YOURS).borderRadius(12).borderAt(UI.Edge.LEFT, 3, FLAME))
            .add("top", label("↺").withStyle(it -> it.componentFont(f -> f.family(FONT).size(16).color(FLAME))))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 2", "[grow]")
                .add("growx, wmin 0", ViewPartsUtil.wrapped(wentBack.viewAsString(it -> it + "."), TEXT, Val.of(true)))
                .add("growx, wmin 0", ViewPartsUtil.note("How it was before is kept, so you can return to it.", Val.of(true))))
            .add("top", button("↶  Undo").group(Skin.QUIET_BUTTON).isEnabledIf(notNow.viewAs(Boolean.class, String::isEmpty))
                 .withStyle(notNow, (why, it) -> why.isEmpty() ? it : it.backgroundColor(SMOKE).cursor(UI.Cursor.DEFAULT))
                 .withTooltip(notNow.viewAsString(it -> it.isEmpty() ? "Go back to how it was before" : it))
                 .onClick(it -> history.get().undo().ifPresent(before -> actions.goBack(selected(), before.id()))));
    }

    // ─── the moments ───────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> moments() {
        Val<Tuple<History.Day>> days = Viewable.of(Tuple.classTyped(History.Day.class), history, genie,
                (it, g) -> it.days(g.schedule().zone()));
        Val<Integer> hidden = history.viewAs(Integer.class, History::hidden);
        Val<Boolean> earlier = history.viewAs(Boolean.class, History::earlier);
        Val<Boolean> reading = history.viewAs(Boolean.class, it -> !it.read());
        Val<Boolean> none = history.viewAs(Boolean.class, it -> it.read() && it.moments().isEmpty());
        return
            box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            .withStyle(wide, (isWide, it) -> it.padding(0, isWide ? 14 : 0, 0, 0))
            .add("growx, wmin 0", label("MOMENTS").group(Skin.SECTION))
            .add("growx, wmin 0", label("Reading the history…").group(Skin.META).isVisibleIf(reading))
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 22 18 22 18, gap 8", "[grow, center]").group(Skin.TILE)
                .isVisibleIf(none)
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.ASLEEP), 56))
                .add("growx, wmin 0", ViewPartsUtil.words("Nothing saved yet", 15, 2f, TEXT).withStyle(it -> it.text(t -> t.placement(UI.Placement.TOP))))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(genie.viewAsString(it -> it.name() + "'s home is saved the first time "
                        + "it wakes, and from then on whenever something changed. Each save is a moment you can bring it back to."),
                        SUBTEXT, Val.of(true))))
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 0, gap 0", "[grow]")
                .withMinSize(0, 0)
                .isVisibleIf(history.viewAs(Boolean.class, it -> !it.moments().isEmpty()))
                // One rail down the whole history; each moment puts its node on it.
                .withStyle(it -> it.backgroundColor(TRANSPARENT).painter(UI.Layer.BACKGROUND,
                        Painter.of(it.componentHeight(), rail(it.componentHeight()))))
                .add("growx, wmin 0", nowRow())
                // In a panel of their own: the days replace whatever is in the panel they go to.
                .add("growx, wmin 0",
                    panel("fill, wrap 1, ins 0, gap 0", "[grow]").withStyle(it -> it.backgroundColor(TRANSPARENT))
                    .addAll("growx, wmin 0", days, day -> UI.of(UI.use(look, () -> dayView(day).get(JPanel.class))))))
            .add("left",
                button(Viewable.of(String.class, hidden, earlier, (more, shown) -> shown ? "Show only the newest moments"
                                    : "Show " + more + " earlier " + (more == 1 ? "moment" : "moments")))
                .group(Skin.ICON_BUTTON).isVisibleIf(Viewable.of(Boolean.class, hidden, earlier, (more, shown) -> more > 0 || shown))
                .withStyle(it -> it.componentFont(f -> f.family(FONT).size(12).color(BRASS)))
                .onClick(it -> history.update(From.VIEW, h -> h.withEarlier(!h.earlier()))));
    }

    private static Painter rail(int height) {
        return g -> {
            g.setColor(BORDER);
            g.fillRect(CLOCK + RAIL / 2 - 1, 10, 2, Math.max(0, height - 20));
        };
    }

    /// Now, at the top: where going back starts from.
    private UIForPanel<JPanel> nowRow() {
        return
            panel("fill, ins 2 0 2 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("top, gaptop 7, right", label("now").group(Skin.CLOCK).withStyle(it -> it.componentFont(f -> f.color(FLAME).weight(2f))))
            .add("top, growy, w " + RAIL + "!",
                box().withPrefSize(RAIL, 28)
                .withStyle(it -> it.backgroundColor(TRANSPARENT).painter(UI.Layer.BACKGROUND, Painter.of("now", g -> {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    double x = RAIL / 2.0;
                    g.setColor(ViewPartsUtil.withAlpha(FLAME, 60));
                    g.fill(new Ellipse2D.Double(x - 9, NODE_Y - 9, 18, 18));
                    g.setColor(FLAME);
                    g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
                }))))
            .add("growx, wmin 0, gaptop 6, gapleft 10",
                label(genie.viewAsString(it -> "How " + it.name() + " is now")).group(Skin.META));
    }

    private UIForPanel<JPanel> dayView(History.Day day) {
        LocalDate today = state.get().localNow().toLocalDate();
        String label = DateWordingUtil.day(day.date(), today);
        boolean near = label.equals("Today") || label.equals("Yesterday");
        var view =
            panel("fill, wrap 1, ins 0, gap 0", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("growx, wmin 0",
                panel("fill, ins 12 0 4 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add("skip 1, center", ViewPartsUtil.dot(Val.of(day.date().equals(today) ? FLAME : SUBTEXT), 5))
                .add("growx, wmin 0, gapleft 8",
                    ViewPartsUtil.words(label + (near ? "  ·  " + DateWordingUtil.shortDay(day.date()) : ""), 13, 2f, TEXT)));
        for (History.Moment moment : day.moments()) view = view.add("growx, wmin 0", momentView(moment));
        return view;
    }

    private UIForPanel<JPanel> momentView(History.Moment moment) {
        ZoneId zone = genie.get().schedule().zone();
        Val<Boolean> picked = history.viewAs(Boolean.class, it -> it.picked().equals(moment.id()));
        return
            panel("fill, ins 2 0 2 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("top, gaptop 7, right", label(Recurrence.clock(LocalDateTime.ofInstant(moment.at(), zone).toLocalTime())).group(Skin.CLOCK))
            .add("top, growy, w " + RAIL + "!",
                box().withPrefSize(RAIL, 28)
                .withStyle(picked, (isPicked, it) -> it.backgroundColor(TRANSPARENT).painter(UI.Layer.BACKGROUND,
                        Painter.of(moment.kind().name() + moment.outcome() + isPicked, node(moment, isPicked)))))
            .add("growx, wmin 0", card(moment, picked));
    }

    /// The node on the rail: the flame for a save by hand, the colour of how it ended for an
    /// answer or a job, brass for going back, and a quiet ring for waking and sleeping.
    private static Painter node(History.Moment moment, boolean picked) {
        return g -> {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double x = RAIL / 2.0;
            if (picked) {
                g.setColor(ViewPartsUtil.withAlpha(FLAME, 60));
                g.fill(new Ellipse2D.Double(x - 9, NODE_Y - 9, 18, 18));
            }
            g.setColor(NIGHT);
            g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
            switch (moment.kind()) {
                case SAVED -> {
                    g.setColor(FLAME);
                    g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
                }
                case RAN -> {
                    g.setColor(moment.outcome().map(outcome -> switch (outcome) {
                        case FINISHED -> CONTENT;
                        case FAILED, TIMED_OUT -> TROUBLE;
                        case STOPPED -> SUBTEXT;
                    }).orElse(SUBTEXT));
                    g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
                }
                case BEFORE_GOING_BACK, WENT_BACK -> {
                    g.setColor(BRASS);
                    g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
                }
                case WOKE, SLEPT -> {
                    g.setColor(SUBTEXT);
                    g.setStroke(new BasicStroke(1.6f));
                    g.draw(new Ellipse2D.Double(x - 4, NODE_Y - 4, 8, 8));
                }
                case BEFORE_RUN -> {
                    g.setColor(SUBTEXT);
                    g.fill(new Ellipse2D.Double(x - 2.5, NODE_Y - 2.5, 5, 5));
                }
            }
        };
    }

    /// A moment as a card: what it was, and more in a line below. Clicking it picks it, which
    /// shows the way back to it, and to its conversation.
    private UIForPanel<JPanel> card(History.Moment moment, Val<Boolean> picked) {
        Var<Boolean> hovered = Var.of(false);
        boolean quiet = moment.kind() == History.Kind.WOKE || moment.kind() == History.Kind.SLEPT || moment.kind() == History.Kind.BEFORE_RUN;
        boolean failed = moment.outcome().filter(it -> it == Schedule.Outcome.FAILED || it == Schedule.Outcome.TIMED_OUT).isPresent();
        String detail = moment.detail();
        Val<String> title = Viewable.of(String.class, genie, state, (it, s) -> it.history().title(moment, it, s.localNow()));
        Val<Boolean> canOpen = genie.viewAs(Boolean.class, it -> !moment.conversation().isEmpty()
                && it.conversations().find(moment.conversation()).isPresent());
        Val<Boolean> hot = Viewable.of(Boolean.class, picked, hovered, (isPicked, isHovered) -> isPicked || isHovered);
        return
            panel("fill, wrap 1, ins 6 10 8 10, gap 2, hidemode 3", "[grow]")
            .withMinSize(0, 0)
            .withCursor(UI.Cursor.HAND)
            .withTooltip(picked.viewAsString(it -> it ? "Click again to close" : "Click to go back to this moment, or to its conversation"))
            .withTransitionalStyle(hot, LifeTime.of(0.14, TimeUnit.SECONDS), (status, it) -> it
                .backgroundColor(picked.get() ? ViewPartsUtil.withAlpha(FLAME, 26 + (int) (20 * status.progress()))
                                              : ViewPartsUtil.withAlpha(RAISED, (int) (200 * status.progress())))
                .borderRadius(10)
                .borderAt(UI.Edge.LEFT, 2, picked.get() ? FLAME : TRANSPARENT))
            .onMouseEnter(it -> hovered.set(true))
            .onMouseExit(it -> hovered.set(false))
            .onMouseClick(it -> history.update(From.VIEW, h -> h.pick(moment.id())))
            .add("growx, wmin 0",
                box().withMinSize(0, 0)
                .withStyle(title, (words, it) -> it.padding(1, 0, 1, 0).text(t -> t
                    .content(StyledString.of(f -> f.family(FONT).size(13)
                        .weight(moment.kind() == History.Kind.SAVED || moment.kind() == History.Kind.WENT_BACK ? 2f : 1.5f)
                        .color(quiet ? SUBTEXT : TEXT), words))
                    .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))))
            .add("growx, wmin 0, hidemode 3", ViewPartsUtil.words(detail, 12, 1f, failed ? TROUBLE : SUBTEXT)
                 .isVisibleIf(!detail.isEmpty()))
            .add("left, gaptop 4, hidemode 3",
                box("ins 0, gap 10, hidemode 3")
                .isVisibleIf(picked)
                .add(button("↺  Go back to this moment…").group(Skin.QUIET_BUTTON)
                     .isEnabledIf(notNow.viewAs(Boolean.class, String::isEmpty))
                     // Not now, it steps back, as the Send button does with nothing to send; its
                     // words take the look's colour for what cannot be used.
                     .withStyle(notNow, (why, it) -> why.isEmpty() ? it : it.backgroundColor(SMOKE).cursor(UI.Cursor.DEFAULT))
                     .withTooltip(notNow.viewAsString(it -> it.isEmpty() ? "Bring its files and conversations back to how they were then" : it))
                     .onClick(it -> confirmGoBack(moment)))
                .add(ViewPartsUtil.link("Open the conversation  →").isVisibleIf(canOpen)
                     .onClick(it -> actions.openConversation(selected(), moment.conversation()))));
    }

    // ─── what a moment holds ───────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> about() {
        return
            box("fillx, wrap 1, ins 0, gap 10, hidemode 3", "[grow]")
            .withStyle(wide, (isWide, it) -> it.padding(isWide ? 0 : 12, 0, 0, isWide ? 14 : 0))
            .add("growx, wmin 0", label("WHAT A MOMENT HOLDS").group(Skin.SECTION))
            .add("growx, wmin 0",
                panel("fill, wrap 1, gap 8", "[grow]").group(Skin.TILE)
                .add("growx, wmin 0", ViewPartsUtil.wrapped(genie.viewAsString(it -> it.name() + "'s home: the files it made, "
                        + "its notes and its conversations, and the settings of its lamp. Going back brings all of these back together."),
                        TEXT, Val.of(true)))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(Val.of("Its schedule stays as it is, and so does everything "
                        + "on this computer outside the genie's lamp."), SUBTEXT, Val.of(true)))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(Val.of("Save by hand before something you might want to undo, "
                        + "such as a big change to a project."), SUBTEXT, Val.of(true))));
    }

    // ─── dialogs ───────────────────────────────────────────────────────────────────────────

    /// Asks what to remember the moment by, then saves; leaving the words out is fine.
    void save(Component parent) {
        Genie shown = genie.get();
        Object answer = JOptionPane.showInputDialog(parent, "What should this moment be remembered by? You may leave it empty.",
                "Save " + shown.name() + " as it is now", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (answer instanceof String words) actions.save(shown.id(), words.strip());
    }

    private void confirmGoBack(History.Moment moment) {
        Genie shown = genie.get();
        String when = History.when(moment.at(), shown.schedule().zone(), state.get().localNow());
        ConfirmAnswer answer = UI.confirmation("Bring " + shown.name() + " back to how it was " + when + "?\n"
                + "Its files and conversations become what they were then. How it is now is kept, so you can return."
                + (shown.phase().isAwake() ? "\n" + shown.name() + " sleeps while it goes back, and wakes again after." : ""))
                .titled("Go back in time").yesOption("Go back").noOption("Stay").cancelOption("").show();
        if (answer == ConfirmAnswer.YES) actions.goBack(shown.id(), moment.id());
    }

    private UUID selected() { return state.get().selected(); }
}
