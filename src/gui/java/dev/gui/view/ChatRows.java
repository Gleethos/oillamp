package dev.gui.view;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.font.FontRenderContext;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.swing.JComponent;
import javax.swing.JPanel;

import dev.gui.model.Conversation;
import dev.gui.model.Entry;
import dev.gui.model.Genie;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForPanel;
import swingtree.animation.LifeTime;
import swingtree.api.IconDeclaration;
import swingtree.style.ComponentBackend;
import swingtree.style.StyledString;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// The rows of a conversation, one per [Entry], in a column no wider than reads well.
///
/// Text is painted by SwingTree's style engine rather than by text components: wrapped to
/// whatever width the row gets, with the row as tall as its text then needs
/// (`autoPreferredHeight`). So an answer wraps while it streams in, not only after the window
/// was resized, and Markdown can be shown with its looks.
///
/// Each row is bound to its entry through a lens, so it redraws in place as the entry changes.
final class ChatRows {

    /// How wide the conversation gets, in the window's units: a comfortable line length.
    static final int COLUMN = 780;

    /// How wide a message of the user's gets at most.
    private static final int YOURS_WIDTH = 560;

    /// How long newly arrived characters take to materialise.
    private static final LifeTime FADE = LifeTime.of(0.45, TimeUnit.SECONDS);

    /// How large the genie beside an answer is: one and a half times its twenty pixels.
    private static final int GENIE_SIZE = 30;

    private final Look look;
    private final Val<Genie> genie;
    private final Consumer<String> saveHandout;
    private final Val<Double> pulse;
    private final Consumer<Entry> askInstead;
    private final Val<Boolean> waits;
    private final Consumer<String> showLeaf;

    /// @param genie       the genie whose conversation this is
    /// @param saveHandout asks the user where to save the outbox file of that name
    /// @param pulse       loops from 0 to 1 while a genie works or wakes; drives the bars and the genie
    /// @param askInstead  lets the user ask one of their questions differently
    /// @param waits       whether the genie waits for the user, the only time a question can be
    ///                    asked differently
    /// @param showLeaf    shows the conversation in the chat up to the entry of that id
    ChatRows(
        Look look,
        Val<Genie> genie,
        Consumer<String> saveHandout,
        Val<Double> pulse,
        Consumer<Entry> askInstead,
        Val<Boolean> waits,
        Consumer<String> showLeaf
    ) {
        this.look = look;
        this.genie = genie;
        this.saveHandout = saveHandout;
        this.pulse = pulse;
        this.askInstead = askInstead;
        this.waits = waits;
        this.showLeaf = showLeaf;
    }

    /// Built later than the window, whenever the conversation changes, so it enters the style
    /// sheet again.
    UIForAnySwing<?, ?> row(Var<Entry> entry) {
        return UI.of(UI.use(look, () ->
            panel("fill, ins 0", "[grow, center]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("growx, wmin 0, wmax " + COLUMN, body(entry))
            .peek(ChatRows::measureTextOnEveryNewWidth)
            .get(JPanel.class)));
    }

    /// Workaround for a SwingTree issue: SwingTree measures the height of a component's text
    /// (`autoPreferredHeight`) only when it computes the component's style, and it computes the
    /// style when the component is painted. Swing paints only the rows in the visible part of
    /// the chat. Without this, a new row outside it keeps the height of an empty row until the
    /// user scrolls to it: the scroll bar's length is wrong, and the row grows under the user.
    ///
    /// So each time Swing's layout gives `row` a new width, the style of every component in it
    /// is computed again, which measures its text at that width.
    private static void measureTextOnEveryNewWidth(JPanel row) {
        row.addComponentListener(new ComponentAdapter() {
            private int measuredAt = -1;

            @Override public void componentResized(ComponentEvent event) {
                if (row.getWidth() == measuredAt) return;
                measuredAt = row.getWidth();
                Deque<Component> open = new ArrayDeque<>(List.of(row));
                while (!open.isEmpty()) {
                    Component next = open.pop();
                    if (next instanceof JComponent styled) ComponentBackend.powering(styled).gatherApplyAndInstallStyle(false);
                    if (next instanceof Container inside) open.addAll(List.of(inside.getComponents()));
                }
            }
        });
    }

    private UIForAnySwing<?, ?> body(Var<Entry> entry) {
        return switch (entry.get().kind()) {
            case YOU -> yours(entry);
            case GENIE -> answer(entry);
            case THINKING -> thought(entry);
            case TOOL -> tool(entry);
            case FILE -> file(entry);
            case NOTICE -> notice(entry);
        };
    }

    // ─── what the user wrote ───────────────────────────────────────────────────────────────

    /// A bubble on the right, as wide as its longest line, up to a limit, then wrapped. Below it,
    /// while the genie waits, a button to ask it differently; the old question stays in the
    /// conversation as a branch of its own. Once the message was edited, arrows beside that
    /// button switch between its conversation versions.
    private UIForAnySwing<?, ?> yours(Var<Entry> entry) {
        String text = entry.get().text();
        Val<Boolean> editable = Viewable.of(Boolean.class, waits, entry, (waiting, it) -> waiting && !it.ref().isEmpty());
        return
            panel("fill, wrap 1, ins 8 60 8 0, gap 2, hidemode 3", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("align right, wmin 0, wmax " + YOURS_WIDTH,
                box().withMinSize(0, 0)
                .withStyle(it -> it
                    .backgroundColor(YOURS)
                    .borderRadius(16)
                    .padding(9, 14, 9, 14)
                    .prefWidth(Math.min(YOURS_WIDTH, widthOf(text) * 1.08 + 36))
                    .text(t -> t.content(MarkdownStylingUtil.of(MarkdownParsingUtil.parse(text), 1))
                                .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))))
            .add("align right",
                box("ins 0, gap 0, hidemode 3")
                .add(versions(entry))
                .add(
                    button("✎  Edit").group(Skin.ICON_BUTTON).isVisibleIf(editable)
                    .withTooltip("Ask this differently. What followed is kept, as a branch in the genie's conversations.")
                    .withStyle(it -> it.componentFont(f -> f.family(FONT).size(11).color(SUBTEXT)))
                    .onClick(it -> askInstead.accept(entry.get()))));
    }

    /// "‹ 2 / 3 ›" under an edited user message: the chat shows conversation version 2 of 3.
    /// ‹ and › show the previous or next version up to its most recently written entry, the same
    /// as clicking that branch in the tree. Hidden for a message that was never edited. Disabled
    /// while the genie wakes, and while it answers in this conversation, because the chat does
    /// not switch then.
    private UIForAnySwing<?, ?> versions(Var<Entry> entry) {
        Val<Conversation.Versions> versions = Viewable.of(Conversation.Versions.class, genie, entry, (it, question) ->
                it.conversations().current()
                  .map(conversation -> conversation.versionsOf(question.ref()))
                  .orElse(Conversation.Versions.NONE));
        Val<Boolean> movable = genie.viewAs(Boolean.class, it ->
                it.phase() != Genie.Phase.WAKING
                && (it.phase() != Genie.Phase.WORKING || it.conversations().aside().isPresent()));
        Val<Boolean> hasEarlier = Viewable.of(Boolean.class, movable, versions, (on, it) -> on && it.at() > 0);
        Val<Boolean> hasLater = Viewable.of(Boolean.class, movable, versions, (on, it) -> on && it.at() < it.leaves().size() - 1);
        return
            box("ins 0, gap 0")
            .isVisibleIf(versions.viewAs(Boolean.class, it -> it.leaves().size() > 1))
            .add(
                button("‹").group(Skin.ICON_BUTTON).isEnabledIf(hasEarlier)
                .withTooltip("Previous conversation version")
                .onClick(it -> showLeaf.accept(versions.get().leaves().get(versions.get().at() - 1))))
            .add(
                label(versions.viewAsString(it -> (it.at() + 1) + " / " + it.leaves().size()))
                .withStyle(it -> it.componentFont(f -> f.family(FONT).size(11).color(SUBTEXT))))
            .add(
                button("›").group(Skin.ICON_BUTTON).isEnabledIf(hasLater)
                .withTooltip("Next conversation version")
                .onClick(it -> showLeaf.accept(versions.get().leaves().get(versions.get().at() + 1))));
    }

    // ─── what the genie answered ───────────────────────────────────────────────────────────

    /// The answer as a page of text next to the genie, the way chat apps show a model's answer:
    /// no bubble, the full column. While it streams in, its newest characters fade in.
    private UIForAnySwing<?, ?> answer(Var<Entry> entry) {
        Val<Boolean> copyable = entry.viewAs(Boolean.class, it -> !it.isWriting());
        UUID id = entry.get().id();
        return
            panel("fill, ins 10 0 10 0, gap 12, hidemode 3", "[" + GENIE_SIZE + "!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("top", picture(it -> answers(it, id) ? GenieSvgUtil.poseOf(it) : GenieSvgUtil.poseBeside(entry.get()),
                                it -> answers(it, id)))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 4, hidemode 3", "[grow]")
                .add("growx, wmin 0",
                    box().withMinSize(0, 0)
                    // Restarts on every change: each new piece fades in from its arrival.
                    .withStyle(entry, FADE, (it, animation, style) -> style
                        .padding(3, 0, 3, 0)
                        .text(t -> t.content(MarkdownStylingUtil.of(MarkdownParsingUtil.parse(it.text()), animation.progress()))
                                    .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))))
                .add("left",
                    button("⧉  Copy").group(Skin.ICON_BUTTON).isVisibleIf(copyable)
                    .withTooltip("Copy the answer, as the genie wrote it")
                    .withStyle(it -> it.componentFont(f -> f.family(FONT).size(11).color(SUBTEXT)))
                    .onClick(it -> copy(entry.get().text()))));
    }

    // ─── what the genie thinks ─────────────────────────────────────────────────────────────

    /// The genie's thoughts, when its model shares them: a bar like a tool's, moving while it
    /// thinks. Click it to watch the thoughts as they come, or to read them afterwards.
    private UIForAnySwing<?, ?> thought(Var<Entry> entry) {
        Val<Boolean> writing = entry.viewAs(Boolean.class, Entry::isWriting);
        Val<Boolean> expanded = entry.viewAs(Boolean.class, Entry::expanded);
        return
            strip(
                picture(it -> GenieSvgUtil.Pose.THINKING, it -> entry.get().isWriting()),
                box("fill, ins 0, gap 8, hidemode 3", "[][grow][]")
                .add(label(entry.viewAsString(it -> it.isWriting() ? "✦ thinking" : "✦ thought")).withStyle(it -> it
                    .componentFont(f -> f.family(FONT).size(12).weight(2f).color(BRASS))))
                .add("growx, wmin 0, h 4!", progress(writing))
                .add(label(entry.viewAsString(it -> it.expanded() ? "▾" : "▸"))
                    .withStyle(it -> it.componentFont(f -> f.size(12).color(SUBTEXT)))),
                box().withMinSize(0, 0).isVisibleIf(expanded)
                .withStyle(entry.viewAsString(Entry::text), (thoughts, it) -> it
                    .padding(6, 2, 2, 2)
                    .borderAt(UI.Edge.TOP, 1, BORDER)
                    .text(t -> t.content(StyledString.of(f -> f.family(FONT).size(12).posture(0.14f).color(SUBTEXT), thoughts))
                                .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))))
            .withTooltip(entry.viewAsString(it -> it.expanded() ? "Click to fold the thoughts away" : "Click to see what the genie thinks"))
            .onMouseClick(it -> entry.update(From.VIEW, e -> e.withExpanded(!e.expanded())));
    }

    /// A bar that says what the user waits for while nothing of the answer has come yet: the
    /// genie thinks, or it does a scheduled job first. So a user can tell a genie that works
    /// from one that is stuck. The job's time stands after the moving bar, where a long title
    /// cannot push it out of sight.
    UIForAnySwing<?, ?> waiting(Val<Genie.Waiting> waiting, Val<Boolean> shown) {
        return UI.of(UI.use(look, () ->
            panel("fill, ins 0", "[grow, center]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .isVisibleIf(shown)
            .add("growx, wmin 0, wmax " + COLUMN,
                box("fill, ins 4 18 4 18", "[grow]")
                .add("growx, wmin 0",
                    strip(
                        picture(GenieSvgUtil::poseOf, it -> true),
                        box("fill, ins 0, gap 8, hidemode 3", "[][grow][]")
                        .add("wmin 0", label(waiting.viewAsString(it -> "✦ " + it.what())).withStyle(it -> it
                            .componentFont(f -> f.family(FONT).size(12).weight(2f).color(BRASS))))
                        .add("growx, wmin 60, h 4!", progress(Val.of(true)))
                        // As wide as "59:59", so the bar keeps its length while the digits change.
                        .add("w 40!", label(waiting.viewAsString(Genie.Waiting::time))
                             .withHorizontalAlignment(UI.HorizontalAlignment.RIGHT)
                             .isVisibleIf(waiting.viewAs(Boolean.class, it -> !it.time().isEmpty()))
                             .withStyle(it -> it.componentFont(f -> f.family(FONT).size(12).color(SUBTEXT)))),
                        box().isVisibleIf(Val.of(false)))
                    .withTooltip(waiting.viewAsString(it -> it.time().isEmpty() ? it.what()
                            : it.what() + ". The genie does one thing at a time, so your message waits until this job is done."))))
            .get(JPanel.class)));
    }

    /// The genie in the pose `pose` gives it. Thinking and working move with the pulse while
    /// `moves` says so, and otherwise stand still in their last frame, as a picture of what the
    /// genie did then. A picture in the style, not a component of its own.
    private UIForAnySwing<?, ?> picture(Function<Genie, GenieSvgUtil.Pose> pose, Predicate<Genie> moves) {
        Val<IconDeclaration> picture = Viewable.of(IconDeclaration.class, genie, pulse, (it, progress) -> {
            GenieSvgUtil.Pose shown = pose.apply(it);
            int frame = moves.test(it) ? GenieSvgUtil.frameAt(shown, progress) : shown.frames - 1;
            return GenieSvgUtil.genie(GenieSvgUtil.appearanceOf(it.id()), shown, frame);
        });
        return box().withPrefSize(GENIE_SIZE, GENIE_SIZE).withMinSize(GENIE_SIZE, GENIE_SIZE)
                .withStyle(picture, (icon, it) -> it.image(img -> img.image(icon).fitMode(UI.FitComponent.MIN_DIM)));
    }

    /// Whether the answer with id `answer` is the one `genie` writes now: the last of its answers
    /// since the user's last message, while it or a tool after it is being written. The genie
    /// beside it thinks and works; while a thought is written, the genie beside the thought does.
    private static boolean answers(Genie genie, UUID answer) {
        Tuple<Entry> entries = genie.transcript().entries();
        if (genie.conversations().aside().isPresent() || entries.isEmpty() || !entries.last().isWriting()
                || entries.last().kind() == Entry.Kind.THINKING) return false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry it = entries.get(i);
            if (it.kind() == Entry.Kind.YOU) return false;
            if (it.kind() == Entry.Kind.GENIE) return it.id().equals(answer);
        }
        return false;
    }

    /// A slim track with a glow travelling along it while `moving`, and nothing once it stops.
    private UIForAnySwing<?, ?> progress(Val<Boolean> moving) {
        return
            box().withMinSize(0, 4).isVisibleIf(moving)
            .withStyle(Viewable.of(Glow.class, moving, pulse, Glow::new), (glow, it) -> {
                int length = it.componentWidth();
                int thickness = it.componentHeight();
                int glowLength = Math.max(1, length / 3);
                int at = (int) Math.round((length + glowLength) * glow.at()) - glowLength;
                return it.backgroundColor(RAISED).borderRadius(2)
                         .painter(UI.Layer.CONTENT, g -> {
                             // Only the part of the glow on the track: the gradient repeats, and
                             // painted further it would start a second glow behind the first.
                             int from = Math.max(0, at);
                             int to = Math.min(length, at + glowLength);
                             if (!glow.moving() || to <= from) return;
                             g.setPaint(new GradientPaint(at, 0, TRANSPARENT, at + glowLength / 2f, 0, FLAME, true));
                             // Round at its ends, as the track is, where it enters and leaves.
                             g.fill(new RoundRectangle2D.Float(from, 0, to - from, thickness, thickness, thickness));
                         });
            });
    }

    /// Whether a progress bar moves, and where its glow is along it, from 0 to 1.
    private record Glow(boolean moving, double at) {}

    /// The shape of a tool's or a thought's row: a line of its own, on smoke, with an optional
    /// part below that shows when it is expanded. Left of it, under the genies beside the
    /// answers, stands `beside`: the genie, or nothing.
    private UIForPanel<JPanel> strip(UIForAnySwing<?, ?> beside, UIForAnySwing<?, ?> line, UIForAnySwing<?, ?> below) {
        return
            panel("fill, ins 0, gap 8, hidemode 3", "[" + GENIE_SIZE + "!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .withCursor(UI.Cursor.HAND)
            .add("top", beside)
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 5 10 5 10, gap 6, hidemode 3", "[grow]")
                .withStyle(it -> it.backgroundColor(SMOKE).borderRadius(8).margin(2, 0, 2, 0))
                .add("growx, wmin 0", line)
                .add("growx, wmin 0", below));
    }

    // ─── a tool the genie used ─────────────────────────────────────────────────────────────

    /// One line saying what the tool does, and how it went. Click it to see what the tool
    /// printed, right there in the conversation.
    private UIForAnySwing<?, ?> tool(Var<Entry> entry) {
        Val<Boolean> expanded = entry.viewAs(Boolean.class, it -> it.expanded() && !it.detail().isBlank());
        Val<Boolean> hasDetail = entry.viewAs(Boolean.class, it -> !it.detail().isBlank());
        return
            strip(
                box(),
                box("fill, ins 0, gap 8, hidemode 3", "[][grow][][]")
                .add(label("⚙ " + entry.get().title()).withStyle(it -> it
                    .componentFont(f -> f.family(MONO).size(12).weight(2f).color(BRASS))))
                .add("growx, wmin 0", label(entry.viewAsString(Entry::text)).withStyle(it -> it
                    .componentFont(f -> f.family(MONO).size(12).color(SUBTEXT))))
                .add(label(entry.viewAsString(it -> switch (it.state()) {
                        case WRITING -> "running…";
                        case FAILED -> "✗ failed";
                        case DONE -> "✓";
                    }))
                    .withStyle(entry.viewAs(Entry.State.class, Entry::state), (state, it) -> it
                        .componentFont(f -> f.family(FONT).size(12)
                            .color(state == Entry.State.FAILED ? TROUBLE : state == Entry.State.WRITING ? FLAME : CONTENT))))
                .add(label(entry.viewAsString(it -> it.expanded() ? "▾" : "▸")).isVisibleIf(hasDetail)
                    .withStyle(it -> it.componentFont(f -> f.size(12).color(SUBTEXT)))),
                box().withMinSize(0, 0).isVisibleIf(expanded)
                .withStyle(entry.viewAsString(Entry::detail), (detail, it) -> it
                    .padding(6, 2, 2, 2)
                    .borderAt(UI.Edge.TOP, 1, BORDER)
                    .text(t -> t.content(StyledString.of(f -> f.family(MONO).size(12).color(SUBTEXT), detail))
                                .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))))
            .withTooltip(entry.viewAsString(it -> it.detail().isBlank() ? it.title() + ": " + it.text()
                                                                        : "Click to see what it printed"))
            .onMouseClick(it -> entry.update(From.VIEW, e -> e.withExpanded(!e.expanded())));
    }

    // ─── a file ────────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> file(Var<Entry> entry) {
        boolean handed = entry.get().text().startsWith("handed you");
        return
            panel("fill, ins 6 38 6 0", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("growx, wmin 0",
                panel("fill, ins 8 12 8 10, gap 12", "[][grow][]")
                .withStyle(it -> it.backgroundColor(RAISED).border(1, BORDER).borderRadius(12))
                .add(label("🗎").withStyle(it -> it.componentFont(f -> f.size(22).color(FLAME))))
                .add("growx, wmin 0",
                    box("fill, wrap 1, ins 0, gap 0")
                    .add("growx, wmin 0", label(entry.get().title()).withStyle(it -> it
                        .componentFont(f -> f.family(FONT).size(13).weight(2f).color(TEXT))))
                    .add("growx, wmin 0", label(entry.get().text()).group(Skin.META)))
                .add(handed
                     ? button("⤓  Save…").group(Skin.QUIET_BUTTON).onClick(it -> saveHandout.accept(entry.get().title()))
                     : box()));
    }

    // ─── what the app says ─────────────────────────────────────────────────────────────────

    /// What the app says, in grey; or a problem, in red, beside the dizzy genie.
    private UIForAnySwing<?, ?> notice(Var<Entry> entry) {
        boolean failed = entry.get().isFailed();
        String text = entry.get().text();
        return
            panel("fill, ins 6 0 6 0, gap 8", "[" + GENIE_SIZE + "!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("top", failed ? picture(it -> GenieSvgUtil.Pose.DIZZY, it -> false) : box())
            .add("growx, wmin 0",
                box().withMinSize(0, 0)
                .withStyle(it -> it
                    .backgroundColor(failed ? TROUBLE_WASH : TRANSPARENT)
                    .border(failed ? 1 : 0, TROUBLE)
                    .borderRadius(8)
                    .padding(6, 10, 6, 10)
                    .text(t -> t.content(StyledString.of(f -> f.family(FONT).size(12)
                                    .posture(failed ? 0f : 0.14f).color(failed ? TROUBLE : SUBTEXT), text))
                                .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))));
    }

    // ─── small helpers ─────────────────────────────────────────────────────────────────────

    /// About how wide `text` is at the body size, in the window's units: its longest line. The
    /// font painted may be a little wider than the one measured, hence the slack where it is used.
    private static int widthOf(String text) {
        Font font = new Font(FONT, Font.PLAIN, MarkdownStylingUtil.BODY);
        FontRenderContext context = new FontRenderContext(null, true, true);
        double widest = 0;
        for (String line : text.lines().toList())
            widest = Math.max(widest, font.getStringBounds(line, context).getWidth());
        return (int) Math.ceil(widest);
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
