package dev.gui.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import dev.gui.model.Dates;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.JobDraft;
import dev.gui.model.Recurrence;
import dev.gui.model.Schedule;
import dev.gui.model.Timeline;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.UIForButton;
import swingtree.UIForLabel;
import swingtree.UIForPanel;
import swingtree.components.JBox;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.api.Painter;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.layout.FlowCell;
import swingtree.style.StyledString;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// A genie's schedule: when its jobs wake it, drawn as a timeline of the week around now, the
/// jobs themselves as tiles beside it, and an editor with room to write a job's task.
///
/// Like the rest of the window, a function of the [GeniesState]: every field is a lens onto the
/// selected genie's [Schedule], and anything that changes the lamp goes through [Actions].
final class SchedulePage {

    /*
     *  The page is a responsive grid, like the chat's. From LARGE up (three fifths of the
     *  reference width, the same 660 at which the chat puts the desktop beside itself) the
     *  timeline and the jobs stand side by side; below that, the jobs follow the timeline. The
     *  editor, in its own card, puts the task beside when it runs the same way.
     *
     *                        very small  small  medium  large  very large  oversize
     *      timeline, task         12       12     12      7        7          7
     *      jobs, when             12       12     12      5        5          5
     *      what is said on top    12       12     12      8        8          8
     *      its buttons            12       12     12      4        4          4
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

    /// The timeline's columns: the time of day, and the rail with a node for each moment.
    private static final int CLOCK = 52;
    private static final int RAIL = 24;
    /// Where a node sits in its row: level with the middle of the row's first line.
    private static final int NODE_Y = 15;

    private final Var<GeniesState> state;
    private final Actions actions;
    private final Look look;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<Schedule> schedule;
    private final Var<Tuple<Schedule.Job>> jobs;
    private final Var<JobDraft> draft;
    private final Var<String> prompt;
    private final Var<Boolean> repeats;
    private final Var<String> time;
    private final Var<JobDraft.Repeat> repeat;
    private final Var<String> minute;
    private final Var<String> cron;
    private final Val<Timeline> timeline;
    private final Val<Boolean> editing;
    private final Val<Boolean> overview;
    private final Val<Boolean> wide;

    SchedulePage(Var<GeniesState> state, Actions actions, Look look) {
        this.state = state;
        this.actions = actions;
        this.look = look;
        genie    = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        schedule = genie.zoomTo(Genie::schedule, Genie::withSchedule);
        jobs     = schedule.zoomTo(Schedule::jobs, Schedule::withJobs);
        // The editor shows only while there is a draft, so the stand-in is never on screen.
        JobDraft standIn = JobDraft.fresh(LocalDateTime.of(2000, 1, 1, 0, 0));
        draft    = schedule.zoomTo(it -> it.draft().orElse(standIn),
                                   (it, changed) -> it.draft().isPresent() ? it.withDraft(Optional.of(changed)) : it);
        prompt   = draft.zoomTo(JobDraft::prompt, JobDraft::withPrompt);
        repeats  = draft.zoomTo(JobDraft::repeats, JobDraft::withRepeats);
        time     = draft.zoomTo(JobDraft::time, JobDraft::withTime);
        repeat   = draft.zoomTo(JobDraft::repeat, JobDraft::withRepeat);
        minute   = draft.zoomTo(JobDraft::minute, JobDraft::withMinute);
        cron     = draft.zoomTo(JobDraft::cron, JobDraft::withCron);
        timeline = state.viewAs(Timeline.class, GeniesState::timeline);
        editing  = schedule.viewAs(Boolean.class, it -> it.draft().isPresent());
        overview = editing.viewAs(Boolean.class, it -> !it);
        wide     = state.viewAs(Boolean.class, GeniesState::sideBySide);
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
                .add(WIDE_SIDE, timelineColumn())
                .add(NARROW_SIDE, jobsColumn())
                .add(WHOLE, editor()));
    }

    // ─── on top: whether jobs run, and what can be done ────────────────────────────────────

    /// Whether the schedule runs, as a coloured dot and a sentence.
    private record Status(Color colour, String words) {}

    private static Status status(Genie genie) {
        Schedule schedule = genie.schedule();
        if (schedule.paused())
            return new Status(BRASS, "Paused. No job runs until you resume the schedule.");
        return switch (genie.phase()) {
            case READY, WORKING -> new Status(CONTENT, "On. Jobs wake " + genie.name() + " at their times, one after the other.");
            case WAKING -> new Status(BRASS, genie.name() + " is waking. Jobs run once it is awake.");
            case ASLEEP, BROKEN -> new Status(SUBTEXT, genie.name() + " is asleep. Jobs run only while it is awake; "
                    + "one whose time came meanwhile runs as soon as it wakes.");
        };
    }

    private UIForAnySwing<?, ?> top() {
        Val<Status> status = genie.viewAs(Status.class, SchedulePage::status);
        Val<Boolean> paused = schedule.viewAs(Boolean.class, Schedule::paused);
        Val<Boolean> asleep = genie.viewAs(Boolean.class, it -> it.phase() == Genie.Phase.ASLEEP || it.phase() == Genie.Phase.BROKEN);
        Val<String> problem = schedule.viewAsString(it -> it.draft().isPresent() ? "" : it.problem());
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 10)
            .isVisibleIf(overview)
            .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add(SAYING,
                box("fill, wrap 1, ins 0, gap 6, hidemode 3", "[grow]")
                .add(label("Schedule").group(Skin.EMPTY_TITLE))
                .add("growx, wmin 0",
                    box("fill, ins 0, gap 8", "[10!][grow]")
                    .add("top, gaptop 5", dot(status.viewAs(Color.class, Status::colour), 8))
                    .add("growx, wmin 0", ViewPartsUtil.wrapped(status.viewAsString(Status::words), SUBTEXT, Val.of(true))))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(problem, TROUBLE, problem.viewAs(Boolean.class, it -> !it.isEmpty()))))
            .add(BUTTONS,
                // Right, beside what is said; left, under it, when there is no room beside it.
                panel(wide.viewAs(Layout.class, isWide -> Layout.flow(isWide ? UI.HorizontalAlignment.RIGHT : UI.HorizontalAlignment.LEFT, 8, 4)))
                .withStyle(wide, (isWide, it) -> it.backgroundColor(TRANSPARENT).padding(isWide ? 4 : 0, 0, 0, 0))
                .add(button("✦  Wake").group(Skin.QUIET_BUTTON).isVisibleIf(asleep)
                     .withTooltip("Jobs run only while the genie is awake")
                     .onClick(it -> actions.wake(selected())))
                .add(button(paused.viewAsString(it -> it ? "▶  Resume" : "❚❚  Pause")).group(Skin.QUIET_BUTTON)
                     .isVisibleIf(schedule.viewAs(Boolean.class, it -> it.paused() || !it.jobs().isEmpty()))
                     .withTooltip(paused.viewAsString(it -> it ? "Let the jobs run again" : "Hold every job until you resume; nothing is lost"))
                     .onClick(it -> actions.pauseSchedule(selected(), !schedule.get().paused())))
                .add(button("＋  New job").group(Skin.FLAME_BUTTON)
                     .onClick(it -> schedule.update(From.VIEW, s -> s.writeNew(state.get().localNow())))));
    }

    // ─── the timeline ──────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> timelineColumn() {
        Val<Tuple<Timeline.Day>> days = timeline.viewAs(Tuple.classTyped(Timeline.Day.class), Timeline::days);
        Val<Integer> hidden = timeline.viewAs(Integer.class, Timeline::hiddenRuns);
        Val<Boolean> earlier = schedule.viewAs(Boolean.class, Schedule::earlier);
        Val<Boolean> canFold = Viewable.of(Boolean.class, hidden, earlier, (more, shown) -> more > 0 || shown);
        Val<Boolean> reading = schedule.viewAs(Boolean.class, it -> !it.read());
        return
            box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            .isVisibleIf(overview)
            .withStyle(wide, (isWide, it) -> it.padding(0, isWide ? 14 : 0, 0, 0))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[grow][]")
                .add("wmin 0", label("THE WEEK").group(Skin.SECTION))
                .add(button(Viewable.of(String.class, hidden, earlier, (more, shown) -> shown ? "Show only the last runs"
                                         : "Show " + more + " earlier " + (more == 1 ? "run" : "runs")))
                     .group(Skin.ICON_BUTTON).isVisibleIf(canFold)
                     .withStyle(it -> it.componentFont(f -> f.family(FONT).size(12).color(BRASS)))
                     .onClick(it -> schedule.update(From.VIEW, s -> s.withEarlier(!s.earlier())))))
            .add("growx, wmin 0", label("Reading the schedule…").group(Skin.META).isVisibleIf(reading))
            .add("growx, wmin 0", label("Nothing ran this week, and nothing is planned.").group(Skin.META)
                 .isVisibleIf(Viewable.of(Boolean.class, timeline, reading, (it, isReading) -> !isReading
                         && it.days().stream().allMatch(day -> day.moments().stream().allMatch(m -> m.kind() == Timeline.Kind.NOW)))))
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 0, gap 0", "[grow]")
                .withMinSize(0, 0)
                // One rail down the whole week; each moment puts its node on it.
                .withStyle(it -> it.backgroundColor(TRANSPARENT).painter(UI.Layer.BACKGROUND,
                        Painter.of(it.componentHeight(), rail(it.componentHeight()))))
                .addAll("growx, wmin 0", days, day -> UI.of(UI.use(look, () -> dayView(day).get(JPanel.class)))));
    }

    private static Painter rail(int height) {
        return g -> {
            g.setColor(BORDER);
            g.fillRect(CLOCK + RAIL / 2 - 1, 10, 2, Math.max(0, height - 20));
        };
    }

    private UIForPanel<JPanel> dayView(Timeline.Day day) {
        LocalDate today = state.get().localNow().toLocalDate();
        boolean near = day.label().equals("Today") || day.label().equals("Tomorrow") || day.label().equals("Yesterday");
        var view =
            panel("fill, wrap 1, ins 0, gap 0", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("growx, wmin 0",
                panel("fill, ins 12 0 4 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add("skip 1, center", dot(Val.of(day.date().equals(today) ? FLAME : SUBTEXT), 5))
                .add("growx, wmin 0, gapleft 8",
                    words((day.label() + (near ? "  ·  " + Dates.shortDay(day.date()) : "")), 13, 2f,
                          day.date().isBefore(today) ? SUBTEXT : TEXT)));
        for (Timeline.Moment moment : day.moments()) view = view.add("growx, wmin 0", momentView(moment));
        return view;
    }

    private UIForPanel<JPanel> momentView(Timeline.Moment moment) {
        boolean now = moment.kind() == Timeline.Kind.NOW || moment.kind() == Timeline.Kind.WORKING;
        return
            panel("fill, ins 2 0 2 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("top, gaptop 7, right", label(moment.clock()).group(Skin.CLOCK)
                 .withStyle(it -> now ? it.componentFont(f -> f.color(FLAME).weight(2f))
                                : moment.emphasis() == Timeline.Emphasis.FADED ? it.componentFont(f -> f.color(dim(SUBTEXT))) : it))
            .add("top, growy, w " + RAIL + "!",
                box().withPrefSize(RAIL, 28)
                .withStyle(it -> it.backgroundColor(TRANSPARENT).painter(UI.Layer.BACKGROUND,
                        Painter.of(moment.kind().name() + moment.outcome() + moment.emphasis(), node(moment)))))
            .add("growx, wmin 0", moment.kind() == Timeline.Kind.NOW ? nowLine() : card(moment));
    }

    /// The node on the rail: hollow for what is still to come, filled for what ran, in the
    /// colour of how it ended, and the flame for now.
    private static Painter node(Timeline.Moment moment) {
        return g -> {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double x = RAIL / 2.0;
            boolean faded = moment.emphasis() == Timeline.Emphasis.FADED;
            boolean picked = moment.emphasis() == Timeline.Emphasis.PICKED;
            switch (moment.kind()) {
                case NOW, WORKING -> {
                    g.setColor(new Color(FLAME.getRed(), FLAME.getGreen(), FLAME.getBlue(), 60));
                    g.fill(new Ellipse2D.Double(x - 9, NODE_Y - 9, 18, 18));
                    g.setColor(FLAME);
                    g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
                }
                case RAN -> {
                    Color colour = moment.outcome().map(outcome -> switch (outcome) {
                        case FINISHED -> CONTENT;
                        case FAILED, TIMED_OUT -> TROUBLE;
                        case STOPPED -> SUBTEXT;
                    }).orElse(SUBTEXT);
                    g.setColor(NIGHT);
                    g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
                    g.setColor(faded ? dim(colour) : colour);
                    g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
                }
                case DUE -> {
                    g.setColor(NIGHT);
                    g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
                    g.setColor(faded ? dim(BRASS) : BRASS);
                    g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
                }
                case PLANNED, REPEATING -> {
                    Color colour = picked ? FLAME : faded ? dim(BRASS) : BRASS;
                    g.setColor(NIGHT);
                    g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
                    g.setColor(colour);
                    g.setStroke(new BasicStroke(picked ? 2.4f : 1.8f));
                    g.draw(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
                    // Several times on one day: a dot in the ring.
                    if (moment.kind() == Timeline.Kind.REPEATING)
                        g.fill(new Ellipse2D.Double(x - 2, NODE_Y - 2, 4, 4));
                }
            }
        };
    }

    /// Now, while no job runs: the flame's line across the week.
    private static UIForBox<JBox> nowLine() {
        return
            box().withPrefSize(10, 28).withMinSize(0, 28)
            .withStyle(it -> it.backgroundColor(TRANSPARENT).painter(UI.Layer.BACKGROUND,
                    Painter.of(it.componentWidth(), g -> {
                        int width = it.componentWidth();
                        g.setPaint(new GradientPaint(0, 0, FLAME, Math.max(1, width), 0, TRANSPARENT));
                        g.fillRect(0, NODE_Y - 1, width, 2);
                    })));
    }

    /// A moment of the timeline as a card: what it is about, and more in a line below. Clicking
    /// it picks its job, so the job's other times stand out and its tile says more.
    private UIForPanel<JPanel> card(Timeline.Moment moment) {
        Var<Boolean> hovered = Var.of(false);
        boolean picked = moment.emphasis() == Timeline.Emphasis.PICKED;
        boolean faded = moment.emphasis() == Timeline.Emphasis.FADED;
        boolean working = moment.kind() == Timeline.Kind.WORKING;
        Color detail = switch (moment.kind()) {
            case DUE -> BRASS;
            case RAN -> moment.outcome().filter(it -> it == Schedule.Outcome.FAILED || it == Schedule.Outcome.TIMED_OUT)
                              .isPresent() ? TROUBLE : SUBTEXT;
            case WORKING -> FLAME;
            default -> SUBTEXT;
        };
        return
            panel("fill, wrap 1, ins 6 10 8 10, gap 2, hidemode 3", "[grow]")
            .withMinSize(0, 0)
            .withCursor(UI.Cursor.HAND)
            .withTooltip(moment.job().isEmpty() ? "" : picked ? "Click again to see every job" : "Click to bring this job's times forward")
            .withTransitionalStyle(hovered, LifeTime.of(0.14, TimeUnit.SECONDS), (status, it) -> it
                .backgroundColor(working ? YOURS : picked ? withAlpha(FLAME, 26 + (int) (20 * status.progress()))
                                 : withAlpha(RAISED, (int) (200 * status.progress())))
                .borderRadius(10)
                .borderAt(UI.Edge.LEFT, 2, picked || working ? FLAME : TRANSPARENT))
            .onMouseEnter(it -> hovered.set(true))
            .onMouseExit(it -> hovered.set(false))
            .onMouseClick(it -> {
                if (!moment.job().isEmpty()) schedule.update(From.VIEW, s -> s.pick(moment.job()));
            })
            .add("growx, wmin 0", words(moment.title(), 13, moment.kind() == Timeline.Kind.RAN ? 1f : 1.5f,
                                        faded ? SUBTEXT : TEXT))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[grow][]")
                .add("growx, wmin 0", words(moment.detail(), 12, 1f, faded ? dim(detail) : detail))
                .add("top",
                    tag(state.viewAsString( it -> "added by " + it.genie().name()))
                    .isVisibleIf(moment.byGenie())
                )
            )
            .add("left, hidemode 3",
                box("ins 0, gap 6, hidemode 3")
                .isVisibleIf(moment.conversation().isPresent() || working)
                .add(link("Open the conversation  →").isVisibleIf(moment.conversation().isPresent())
                     .onClick(it -> moment.conversation().ifPresent(id -> actions.openConversation(selected(), id))))
                .add(button("■  Stop").group(Skin.QUIET_BUTTON).isVisibleIf(working)
                     .withTooltip("Stop this run; what the genie did so far is kept, and saved")
                     .onClick(it -> actions.stopRun(selected(), moment.run()))));
    }

    // ─── the jobs ──────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> jobsColumn() {
        Val<Boolean> none = schedule.viewAs(Boolean.class, it -> it.read() && it.jobs().isEmpty());
        return
            box("fillx, wrap 1, ins 0, gap 10, hidemode 3", "[grow]")
            .isVisibleIf(overview)
            .withStyle(wide, (isWide, it) -> it.padding(isWide ? 0 : 12, 0, 0, isWide ? 14 : 0))
            .add("growx, wmin 0", label(jobs.viewAsString(it -> it.isEmpty() ? "JOBS" : "JOBS  ·  " + it.size())).group(Skin.SECTION))
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 22 18 22 18, gap 8", "[grow, center]").group(Skin.TILE)
                .isVisibleIf(none)
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.ASLEEP), 56))
                .add("growx, wmin 0", words("Nothing on the schedule yet", 15, 2f, TEXT).withStyle(it -> it.text(t -> t.placement(UI.Placement.TOP))))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(genie.viewAsString(it -> "Give " + it.name() + " a task and a time, "
                        + "once or again and again: a morning check of the build, a weekly report, a reminder "
                        + "to tidy up. It works on it in a conversation of its own, and you can read it here afterwards."),
                        SUBTEXT, Val.of(true)))
                .add("gaptop 6", button("＋  New job").group(Skin.FLAME_BUTTON)
                     .onClick(it -> schedule.update(From.VIEW, s -> s.writeNew(state.get().localNow())))))
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 0, gap 10", "[grow]")
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .addAll("growx, wmin 0", jobs, (Var<Schedule.Job> job) -> UI.of(UI.use(look, () -> tile(job).get(JPanel.class)))));
    }

    /// One job as a tile: how often it runs, its task, when it runs next, and a switch.
    /// Clicking the tile picks the job, as clicking one of its times does.
    private UIForPanel<JPanel> tile(Var<Schedule.Job> job) {
        String id = job.get().id();
        Val<Boolean> picked = schedule.viewAs(Boolean.class, it -> it.picked().equals(id));
        Val<Boolean> on = job.viewAs(Boolean.class, Schedule.Job::enabled);
        Val<String> when = Viewable.of(String.class, job, state, (it, s) -> it.when(s.localNow(), s.genie().schedule().zone()));
        Val<String> next = Viewable.of(String.class, job, state, SchedulePage::nextWords);
        return
            panel("fill, wrap 1, gap 6, hidemode 3", "[grow]").group(Skin.TILE)
            .withMinSize(0, 0)
            .withCursor(UI.Cursor.HAND)
            .withStyle(picked, (isPicked, it) -> isPicked ? it.border(1, FLAME).borderAt(UI.Edge.LEFT, 3, FLAME) : it)
            .onMouseClick(it -> {
                if (it.clickCount() == 2) schedule.update(From.VIEW, s -> s.change(id, state.get().localNow()));
                else schedule.update(From.VIEW, s -> s.pick(id));
            })
            .add("growx, wmin 0",
                box("fill, ins 0, gap 10", "[][grow][]")
                .add("top, gaptop 1", onOff(on, () -> actions.switchJob(selected(), id, !job.get().enabled())))
                .add("growx, wmin 0", words(Viewable.of(Words.class, when, on, (text, isOn) -> new Words(text, isOn ? TEXT : SUBTEXT)), 13, 2f))
                .add("top", button("⋯").group(Skin.ICON_BUTTON).withTooltip("Change, switch off or remove this job")
                     .onClick(it -> ViewPartsUtil.below(menu(id), it.getComponent()))))
            .add("growx, wmin 0", ViewPartsUtil.wrapped(job.viewAsString(it -> shortened(it.prompt(), 280)), SUBTEXT, Val.of(true)))
            .add("growx, wmin 0",
                box("ins 0, gap 6, hidemode 3")
                .add(tag(next).isVisibleIf(next.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add(tag(job.viewAsString(it -> it.expires().map(end -> "until " + Dates.shortDay(
                        end.minusSeconds(1).atZone(schedule.get().zone()).toLocalDate())).orElse("")))
                     .isVisibleIf(job.viewAs(Boolean.class, it -> it.expires().isPresent())))
                .add(tag(genie.viewAsString(it -> "added by " + it.name())).isVisibleIf(job.viewAs(Boolean.class, Schedule.Job::byGenie))));
    }

    /// When a job runs next, in a few words, for its tile.
    /// Empty for a job that runs once, whose time its heading says already.
    private static String nextWords(Schedule.Job job, GeniesState state) {
        if (!job.enabled()) return "switched off";
        if (job.next().isEmpty()) return "will not run again";
        Instant next = job.next().get();
        if (!next.isAfter(state.now())) return "due now";
        if (job.repeats().isEmpty()) return "";
        LocalDateTime at = LocalDateTime.ofInstant(next, state.genie().schedule().zone());
        LocalDate today = state.localNow().toLocalDate();
        String day = at.toLocalDate().equals(today) ? "today" : at.toLocalDate().equals(today.plusDays(1)) ? "tomorrow"
                   : Dates.shortDay(at.toLocalDate());
        return "next: " + day + ", " + Recurrence.clock(at.toLocalTime());
    }

    private JPopupMenu menu(String id) {
        JPopupMenu menu = new JPopupMenu();
        schedule.get().job(id).ifPresent(job -> {
            menu.add(ViewPartsUtil.item("Change…", true, () -> schedule.update(From.VIEW, s -> s.change(id, state.get().localNow()))));
            menu.add(ViewPartsUtil.item(job.enabled() ? "Switch off" : "Switch on", true, () -> actions.switchJob(selected(), id, !job.enabled())));
            menu.addSeparator();
            JMenuItem remove = ViewPartsUtil.item("Remove…", true, () -> confirmRemove(job));
            remove.setForeground(TROUBLE);
            menu.add(remove);
        });
        return menu;
    }

    private void confirmRemove(Schedule.Job job) {
        ConfirmAnswer answer = UI.confirmation("Take \"" + job.title() + "\" off the schedule? "
                + "What its runs did stays in the history.").titled("Remove a job")
                .yesOption("Remove").noOption("Keep").cancelOption("").show();
        if (answer == ConfirmAnswer.YES) actions.removeJob(selected(), job.id());
    }

    // ─── the editor ────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> editor() {
        Val<Boolean> changing = draft.viewAs(Boolean.class, it -> it.replaces().isPresent());
        Val<String> blocked = Viewable.of(String.class, draft, state, (it, s) -> it.problem(s.localNow()).orElse(""));
        Val<Boolean> busy = schedule.viewAs(Boolean.class, Schedule::busy);
        Val<Boolean> canSave = Viewable.of(Boolean.class, blocked, busy, (problem, isBusy) -> problem.isEmpty() && !isBusy);
        Val<String> refused = schedule.viewAsString(Schedule::problem);
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 16).group(Skin.CARD)
            .isVisibleIf(editing)
            .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
            .add(WHOLE,
                box("fill, ins 0, gap 12", "[grow][]")
                .add("growx, wmin 0",
                    box("fill, wrap 1, ins 0, gap 4")
                    .add("growx, wmin 0", label(Viewable.of(String.class, changing, genie, (isChange, it) ->
                            isChange ? "Change a job" : "A new job for " + it.name())).group(Skin.EMPTY_TITLE))
                    .add("growx, wmin 0", ViewPartsUtil.note("It gets the task at the time you choose, in a conversation of its own, "
                            + "and while it is awake: a job whose time came while it slept runs as soon as it wakes.", Val.of(true))))
                .add("top", button("✕").group(Skin.ICON_BUTTON).withTooltip("Close without saving")
                     .onClick(it -> schedule.update(From.VIEW, Schedule::closeEditor))))
            .add(WIDE_SIDE, task())
            .add(NARROW_SIDE, when())
            .add(WHOLE,
                box("fill, ins 0, gap 12, hidemode 3", "[grow][][]")
                .add("growx, wmin 0", ViewPartsUtil.wrapped(refused, TROUBLE, refused.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add("skip 0", button("Cancel").group(Skin.QUIET_BUTTON)
                     .onClick(it -> schedule.update(From.VIEW, Schedule::closeEditor)))
                .add(button(Viewable.of(String.class, changing, busy, (isChange, isBusy) ->
                            isBusy ? "Saving…" : isChange ? "Save the job" : "Add to the schedule"))
                     .group(Skin.FLAME_BUTTON).isEnabledIf(canSave)
                     .withTooltip(blocked.viewAsString(it -> it.isEmpty() ? "Ctrl and Return save too" : it))
                     .withStyle(canSave, (can, it) -> can ? it : it.backgroundColor(RAISED).foregroundColor(SUBTEXT)
                         .componentFont(f -> f.color(SUBTEXT)).cursor(UI.Cursor.DEFAULT))
                     .onClick(it -> actions.saveJob(selected()))));
    }

    /// The task, with room to write a proper brief.
    private UIForAnySwing<?, ?> task() {
        Val<Integer> length = prompt.viewAs(Integer.class, it -> it.strip().length());
        return
            box("fill, wrap 1, ins 0, gap 8", "[grow]", "[][grow][]")
            .withStyle(wide, (isWide, it) -> it.padding(0, isWide ? 18 : 0, 0, 0))
            .add("growx", label("THE TASK").group(Skin.SECTION))
            .add("grow, push, wmin 0, hmin 220",
                scrollPane().withEmptyBorder(0).withMinSize(0, 220).withPrefSize(520, 380)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withStyle(it -> it.backgroundColor(RAISED).border(1, BORDER).borderRadius(12))
                .add(
                    textArea(prompt).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                    .withStyle(it -> it.backgroundColor(TRANSPARENT).border(0, TRANSPARENT).padding(10, 12, 10, 12)
                        .componentFont(f -> f.family(FONT).size(14).color(TEXT)))
                    .onKeyPress(it -> {
                        KeyEvent key = it.getEvent();
                        if (key.getKeyCode() == KeyEvent.VK_ENTER && key.isControlDown()) {
                            key.consume();
                            actions.saveJob(selected());
                        }
                    })))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 12", "[grow][]")
                .add("growx, wmin 0", ViewPartsUtil.note("Brief it as you would a colleague: what to do, where things are, and "
                        + "what done looks like. Each run starts a fresh conversation; between runs, the genie "
                        + "keeps notes in ~/workspace/NOTES.md.", Val.of(true)))
                .add("top", label(length.viewAsString(it -> String.format("%,d / %,d", it, JobDraft.MOST_PROMPT))).group(Skin.META)
                     .withStyle(length, (count, it) -> count > JobDraft.MOST_PROMPT ? it.componentFont(f -> f.color(TROUBLE)) : it)));
    }

    /// When it runs: once, on a day and at a time, or again and again.
    private UIForAnySwing<?, ?> when() {
        Val<Boolean> once = repeats.viewAs(Boolean.class, it -> !it);
        Val<Boolean> clocked = Viewable.of(Boolean.class, repeats, repeat, (isRepeating, how) ->
                !isRepeating || how == JobDraft.Repeat.DAILY || how == JobDraft.Repeat.WEEKDAYS || how == JobDraft.Repeat.WEEKLY);
        Val<Boolean> calendarShown = draft.viewAs(Boolean.class, it -> !it.repeats() || it.endsOn().isPresent());
        Val<String> summary = Viewable.of(String.class, draft, state, (it, s) -> it.summary(s.localNow()));
        Val<String> timing = Viewable.of(String.class, draft, state, (it, s) -> it.timingProblem(s.localNow()).orElse(""));
        return
            box("fill, wrap 1, ins 0, gap 10, hidemode 3", "[grow]")
            .withStyle(wide, (isWide, it) -> it.padding(isWide ? 0 : 6, 0, 0, 0))
            .add("growx", label("WHEN").group(Skin.SECTION))
            .add(
                box("ins 0, gap 6")
                .add(chip("Once", once, () -> repeats.set(From.VIEW, false)))
                .add(chip("Again and again", repeats, () -> repeats.set(From.VIEW, true))))

            // ── once: quick picks, then a day and a time ──
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 6, 6).isVisibleIf(once)
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add(quickPick("In an hour", now -> now.plusHours(1).withSecond(0).withNano(0)
                        .withMinute((now.getMinute() / 5) * 5)))
                .add(quickPick("This evening", now -> now.toLocalDate().atTime(now.getHour() >= 18 ? 21 : 18, 0)))
                .add(quickPick("Tomorrow morning", now -> now.toLocalDate().plusDays(1).atTime(9, 0)))
                .add(quickPick("Monday morning", now -> now.toLocalDate()
                        .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0))))

            // ── again and again: how often ──
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 6, 6).isVisibleIf(repeats)
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add(chip(JobDraft.Repeat.HOURLY)).add(chip(JobDraft.Repeat.DAILY)).add(chip(JobDraft.Repeat.WEEKDAYS))
                .add(chip(JobDraft.Repeat.WEEKLY)).add(chip(JobDraft.Repeat.CUSTOM)))
            .add("growx, wmin 0", weekdays().isVisibleIf(repeat.viewAs(Boolean.class, it -> it == JobDraft.Repeat.WEEKLY)
                    .viewAs(Boolean.class, it -> it && repeats.get())))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[][90!][grow]")
                .isVisibleIf(Viewable.of(Boolean.class, repeats, repeat, (isRepeating, how) -> isRepeating && how == JobDraft.Repeat.HOURLY))
                .add(label("At"))
                .add("growx", textField(minute).group(Skin.INPUT).withTooltip("A number from 0 to 59"))
                .add("wmin 0", label("minutes past each hour").group(Skin.META)))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 4, hidemode 3")
                .isVisibleIf(Viewable.of(Boolean.class, repeats, repeat, (isRepeating, how) -> isRepeating && how == JobDraft.Repeat.CUSTOM))
                .add("growx, wmin 0", textField(cron).group(Skin.INPUT)
                     .withStyle(it -> it.componentFont(f -> f.family(MONO).size(13).color(TEXT))))
                .add("growx, wmin 0", ViewPartsUtil.note("Five fields, as cron has them: minute, hour, day of the month, month, "
                        + "day of the week. 0 9 1 * * is nine on the first of each month; */30 * * * * every half hour.", Val.of(true))))

            // ── the time of day ──
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[][90!][grow]")
                .isVisibleIf(clocked)
                .add(label("At"))
                .add("growx", textField(time).group(Skin.INPUT).withTooltip("Such as 09:00, or 9.30")
                     .withStyle(draft.viewAs(Boolean.class, it -> it.clock().isPresent()), (readable, it) -> readable ? it : it.border(1, TROUBLE)))
                .add("growx, wmin 0",
                    box("ins 0, gap 4")
                    .add(timeChip("09:00")).add(timeChip("13:00")).add(timeChip("18:00"))))

            // ── until when ──
            .add("growx, wmin 0",
                box("ins 0, gap 6, hidemode 3").isVisibleIf(repeats)
                .add(label("Ends").withStyle(it -> it.padding(0, 0, 0, 0)))
                .add(chip("Never", draft.viewAs(Boolean.class, it -> it.endsOn().isEmpty()),
                          () -> draft.update(From.VIEW, it -> it.withEndsOn(Optional.empty()))))
                .add(chip("On a day", draft.viewAs(Boolean.class, it -> it.endsOn().isPresent()),
                          () -> draft.update(From.VIEW, it -> it.endsOn().isPresent() ? it
                                  : it.withEndsOn(Optional.of(state.get().localNow().toLocalDate().plusWeeks(2)))
                                      .withMonth(YearMonth.from(state.get().localNow().plusWeeks(2)))))))

            .add("growx, wmin 0",
                panel("ins 0, gap 0, hidemode 3", "[grow]").isVisibleIf(calendarShown)
                .withStyle(it -> it.backgroundColor(TRANSPARENT))
                .add("growx, wmin 0", state.viewAs(Month.class, SchedulePage::month), month ->
                    UI.of(UI.use(look, () -> calendar(month).get(JPanel.class)))))

            // ── what it comes to ──
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 10 12 10 12, gap 4, hidemode 3", "[grow]")
                .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(12))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(summary, TEXT, summary.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(timing, TROUBLE, timing.viewAs(Boolean.class, it -> !it.isEmpty()))));
    }

    /// What the calendar shows: a month, the day picked in it, and from which day on days can be
    /// picked. For a repeating job, the days up to its last one are marked as a stretch.
    private record Month(YearMonth month, Optional<LocalDate> picked, LocalDate today, boolean stretch) {}

    private static Month month(GeniesState state) {
        Optional<JobDraft> draft = state.genie().schedule().draft();
        LocalDate today = state.localNow().toLocalDate();
        if (draft.isEmpty()) return new Month(YearMonth.from(today), Optional.empty(), today, false);
        JobDraft it = draft.get();
        return new Month(it.month(), it.repeats() ? it.endsOn() : Optional.of(it.day()), today, it.repeats());
    }

    /// A month of days, seven to a row, Monday first.
    private UIForPanel<JPanel> calendar(Month month) {
        var grid =
            panel("wrap 7, ins 10 10 8 10, gap 3 3", "[34!][34!][34!][34!][34!][34!][34!]")
            .withStyle(it -> it.backgroundColor(SMOKE).border(1, BORDER).borderRadius(12))
            .add("span 7, growx",
                box("fill, ins 0 2 4 0, gap 2", "[grow][][]")
                .add("growx, wmin 0", words(month.month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " "
                        + month.month().getYear(), 13, 2f, TEXT))
                .add(button("‹").group(Skin.ICON_BUTTON).withTooltip("The month before")
                     .isEnabledIf(month.month().isAfter(YearMonth.from(month.today())))
                     .onClick(it -> draft.update(From.VIEW, d -> d.withMonth(d.month().minusMonths(1)))))
                .add(button("›").group(Skin.ICON_BUTTON).withTooltip("The month after")
                     .onClick(it -> draft.update(From.VIEW, d -> d.withMonth(d.month().plusMonths(1))))));
        for (DayOfWeek day : DayOfWeek.values())
            grid = grid.add("center", label(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2)).group(Skin.META));
        int blank = month.month().atDay(1).getDayOfWeek().getValue() - 1;
        for (int i = 0; i < blank; i++) grid = grid.add(box());
        for (int number = 1; number <= month.month().lengthOfMonth(); number++)
            grid = grid.add("w 34!, h 30!", dayButton(month, month.month().atDay(number)));
        return grid;
    }

    private UIForButton<JButton> dayButton(Month month, LocalDate day) {
        boolean picked = month.picked().filter(day::equals).isPresent();
        boolean past = day.isBefore(month.today());
        boolean inStretch = month.stretch() && month.picked().isPresent()
                && !day.isBefore(month.today()) && day.isBefore(month.picked().get());
        boolean today = day.equals(month.today());
        return
            button(String.valueOf(day.getDayOfMonth())).group(Skin.DAY)
            .isEnabledIf(!past)
            .withTooltip(Dates.day(day, month.today()))
            .withStyle(it -> it
                .backgroundColor(picked ? FLAME : inStretch ? YOURS : TRANSPARENT)
                .border(1, today && !picked ? BRASS : TRANSPARENT)
                .componentFont(f -> f.color(picked ? ON_FLAME : past ? dim(SUBTEXT) : TEXT).weight(picked || today ? 2f : 1f)))
            .onClick(it -> draft.update(From.VIEW, d -> d.pick(day)));
    }

    /// The seven days of the week, each a round toggle, for a job that runs on some of them.
    private UIForBox<JBox> weekdays() {
        var row = box("ins 0, gap 6");
        for (DayOfWeek day : DayOfWeek.values()) {
            Val<Boolean> on = draft.viewAs(Boolean.class, it -> it.days().contains(day));
            row = row.add("w 36!, h 36!",
                button(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2)).group(Skin.DAY)
                .withTooltip(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .withStyle(on, (isOn, it) -> it.borderRadius(18)
                    .backgroundColor(isOn ? FLAME : TRANSPARENT).border(1, isOn ? FLAME : BORDER)
                    .componentFont(f -> f.color(isOn ? ON_FLAME : TEXT).weight(isOn ? 2f : 1f)))
                .onClick(it -> draft.update(From.VIEW, d -> d.toggle(day))));
        }
        return row;
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    private UUID selected() { return state.get().selected(); }

    /// A choice among several, as a rounded chip that is lit while chosen.
    private static UIForButton<JButton> chip(String text, Val<Boolean> chosen, Runnable choose) {
        return
            button(text).group(Skin.CHIP)
            .withStyle(chosen, (on, it) -> on ? it.backgroundColor(YOURS).border(1, FLAME).componentFont(f -> f.color(TEXT).weight(2f)) : it)
            .onClick(it -> choose.run());
    }

    private UIForButton<JButton> chip(JobDraft.Repeat how) {
        return chip(how.label(), repeat.viewAs(Boolean.class, it -> it == how), () -> repeat.set(From.VIEW, how));
    }

    /// A chip that sets the day and the time of a job that runs once, from now.
    private UIForButton<JButton> quickPick(String text, UnaryOperator<LocalDateTime> from) {
        return
            button(text).group(Skin.CHIP)
            .onClick(it -> {
                LocalDateTime at = from.apply(state.get().localNow());
                draft.update(From.VIEW, d -> d.withDay(at.toLocalDate()).withTime(Recurrence.clock(at.toLocalTime()))
                                              .withMonth(YearMonth.from(at)));
            });
    }

    private UIForButton<JButton> timeChip(String clock) {
        return chip(clock, time.viewAs(Boolean.class, it -> it.strip().equals(clock)), () -> time.set(From.VIEW, clock));
    }

    /// A switch, drawn as a small track with a knob that slides across as it turns on.
    private static UIForBox<JBox> onOff(Val<Boolean> on, Runnable flip) {
        return
            box().withPrefSize(34, 20).withMinSize(34, 20).withMaxSize(34, 20)
            .withCursor(UI.Cursor.HAND)
            .withTooltip(on.viewAsString(it -> it ? "On: click to switch it off, keeping it on the schedule" : "Off: click to switch it on"))
            .withTransitionalStyle(on, LifeTime.of(0.18, TimeUnit.SECONDS), (status, it) -> it
                .backgroundColor(TRANSPARENT)
                .painter(UI.Layer.BACKGROUND, Painter.of(status.progress(), g -> {
                    double progress = status.progress();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(mix(RAISED, FLAME, progress));
                    g.fill(new RoundRectangle2D.Double(0, 1, 34, 18, 18, 18));
                    g.setColor(mix(BORDER, FLAME, progress));
                    g.draw(new RoundRectangle2D.Double(0.5, 1.5, 33, 17, 17, 17));
                    g.setColor(mix(SUBTEXT, ON_FLAME, progress));
                    g.fill(new Ellipse2D.Double(3 + 14 * progress, 4, 12, 12));
                })))
            .onMouseClick(it -> flip.run());
    }

    private static UIForLabel<JLabel> tag(Val<String> text) {
        return label(text)
                .withStyle(it -> it
                        .backgroundColor(RAISED)
                        .borderRadius(8)
                        .padding(2, 8, 2, 8)
                        .componentFont(f -> f
                            .family(FONT).size(11).color(SUBTEXT)
                        )
                );
    }

    /// A button that reads as a link, in brass.
    private static UIForButton<JButton> link(String text) {
        return button(text).group(Skin.ICON_BUTTON)
                .withStyle(it -> it.padding(2, 0, 2, 0).componentFont(f -> f.family(FONT).size(12).color(BRASS)));
    }

    /// A round dot of `size`.
    private static UIForBox<JBox> dot(Val<Color> colour, int size) {
        return box().withPrefSize(size, size).withMinSize(size, size).withMaxSize(size, size)
                .withStyle(colour, (c, it) -> it.backgroundColor(c).borderRadius(size));
    }

    /// Words in a colour, for text whose colour changes with it.
    private record Words(String text, Color colour) {}

    private static UIForBox<JBox> words(Val<Words> words, int size, float weight) {
        return box().withMinSize(0, 0)
                .withStyle(words, (shown, it) -> it.padding(1, 0, 1, 0).text(t -> t
                        .content(StyledString.of(f -> f.family(FONT).size(size).weight(weight).color(shown.colour()), shown.text()))
                        .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true)));
    }

    /// Text painted by the style engine, wrapped to the width it gets.
    private static UIForBox<JBox> words(String text, int size, float weight, Color colour) {
        return box().withMinSize(0, 0)
                .withStyle(it -> it.padding(1, 0, 1, 0).text(t -> t
                        .content(StyledString.of(f -> f.family(FONT).size(size).weight(weight).color(colour), text))
                        .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true)));
    }

    private static String shortened(String text, int most) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= most ? flat : flat.substring(0, most - 1).stripTrailing() + "…";
    }

    private static Color dim(Color colour) {
        return withAlpha(colour, 110);
    }

    private static Color withAlpha(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color mix(Color from, Color to, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return new Color((int) (from.getRed() + (to.getRed() - from.getRed()) * p),
                         (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * p),
                         (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * p));
    }
}
