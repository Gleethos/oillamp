package dev.gui.view;

import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.font.FontRenderContext;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.JPanel;

import dev.gui.model.Entry;
import dev.gui.model.Genie;

import sprouts.From;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForPanel;
import swingtree.animation.LifeTime;
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

    private final Look look;
    private final Consumer<String> saveHandout;
    private final Val<Double> pulse;

    /// @param saveHandout asks the user where to save the outbox file of that name
    /// @param pulse       loops from 0 to 1 while the genie thinks; drives the dots
    ChatRows(Look look, Consumer<String> saveHandout, Val<Double> pulse) {
        this.look = look;
        this.saveHandout = saveHandout;
        this.pulse = pulse;
    }

    /// Built later than the window, whenever the conversation changes, so it enters the style
    /// sheet again.
    UIForAnySwing<?, ?> row(Var<Entry> entry) {
        return UI.of(UI.use(look, () ->
            panel("fill, ins 0", "[grow, center]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("growx, wmin 0, wmax " + COLUMN, body(entry))
            .get(JPanel.class)));
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

    /// A bubble on the right, as wide as its longest line, up to a limit, then wrapped.
    private UIForAnySwing<?, ?> yours(Var<Entry> entry) {
        String text = entry.get().text();
        return
            panel("fill, ins 8 60 8 0", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("align right, wmin 0, wmax " + YOURS_WIDTH,
                box().withMinSize(0, 0)
                .withStyle(it -> it
                    .backgroundColor(YOURS)
                    .borderRadius(16)
                    .padding(9, 14, 9, 14)
                    .prefWidth(Math.min(YOURS_WIDTH, widthOf(text) * 1.08 + 36))
                    .text(t -> t.content(Typeset.of(Markdown.parse(text), 1))
                                .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true))));
    }

    // ─── what the genie answered ───────────────────────────────────────────────────────────

    /// The answer as a page of text next to the genie's lamp, the way chat apps show a model's
    /// answer: no bubble, the full column. While it streams in, its newest characters fade in.
    private UIForAnySwing<?, ?> answer(Var<Entry> entry) {
        Val<Boolean> copyable = entry.viewAs(Boolean.class, it -> !it.isWriting());
        return
            panel("fill, ins 10 0 10 0, gap 12, hidemode 3", "[26!][grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .add("top, gaptop 1", Parts.lamp(Val.of(Genie.Phase.READY), 26))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 4, hidemode 3", "[grow]")
                .add("growx, wmin 0",
                    box().withMinSize(0, 0)
                    // Restarts on every change: each new piece fades in from its arrival.
                    .withStyle(entry, FADE, (it, animation, style) -> style
                        .padding(3, 0, 3, 0)
                        .text(t -> t.content(Typeset.of(Markdown.parse(it.text()), animation.progress()))
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

    /// A bar that says the genie is working on an answer while nothing has come of it yet, so a
    /// user can tell a genie that thinks from one that is stuck.
    UIForAnySwing<?, ?> waiting(Val<String> who, Val<Boolean> shown) {
        return UI.of(UI.use(look, () ->
            panel("fill, ins 0", "[grow, center]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .isVisibleIf(shown)
            .add("growx, wmin 0, wmax " + COLUMN,
                box("fill, ins 4 18 4 18", "[grow]")
                .add("growx, wmin 0",
                    strip(
                        box("fill, ins 0, gap 8", "[][grow]")
                        .add(label(who.viewAsString(name -> "✦ " + name + " is thinking")).withStyle(it -> it
                            .componentFont(f -> f.family(FONT).size(12).weight(2f).color(BRASS))))
                        .add("growx, wmin 0, h 4!", progress(Val.of(true))),
                        box().isVisibleIf(Val.of(false)))))
            .get(JPanel.class)));
    }

    /// A slim track with a glow travelling along it while `moving`, and nothing once it stops.
    private UIForAnySwing<?, ?> progress(Val<Boolean> moving) {
        return
            box().withMinSize(0, 4).isVisibleIf(moving)
            .withStyle(Viewable.of(Glow.class, moving, pulse, Glow::new), (glow, it) -> {
                int length = UI.scale(it.componentWidth());
                int thickness = UI.scale(it.componentHeight());
                int glowLength = Math.max(1, length / 3);
                int at = (int) Math.round((length + glowLength) * glow.at()) - glowLength;
                return it.backgroundColor(RAISED).borderRadius(2)
                         .painter(UI.Layer.CONTENT, g -> {
                             if (!glow.moving()) return;
                             g.setPaint(new java.awt.GradientPaint(at, 0, TRANSPARENT, at + glowLength / 2f, 0, FLAME, true));
                             g.fillRect(Math.max(0, at), 0, Math.max(0, Math.min(glowLength, length - Math.max(0, at))), thickness);
                         });
            });
    }

    /// Whether a progress bar moves, and where its glow is along it, from 0 to 1.
    private record Glow(boolean moving, double at) {}

    /// The shape of a tool's or a thought's row: a line of its own, on smoke, with an optional
    /// part below that shows when it is expanded.
    private UIForPanel<JPanel> strip(UIForAnySwing<?, ?> line, UIForAnySwing<?, ?> below) {
        return
            panel("fill, wrap 1, ins 0 38 0 0, gap 0, hidemode 3", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
            .withCursor(UI.Cursor.HAND)
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

    private UIForAnySwing<?, ?> notice(Var<Entry> entry) {
        boolean failed = entry.get().isFailed();
        String text = entry.get().text();
        return
            panel("fill, ins 6 38 6 0", "[grow]")
            .withStyle(it -> it.backgroundColor(TRANSPARENT))
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
        Font font = new Font(FONT, Font.PLAIN, Typeset.BODY);
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
