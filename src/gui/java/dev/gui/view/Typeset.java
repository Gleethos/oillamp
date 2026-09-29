package dev.gui.view;

import java.awt.Color;

import sprouts.Tuple;
import swingtree.api.Configurator;
import swingtree.style.FontConf;
import swingtree.style.StyledString;

import static dev.gui.view.Palette.*;

/// Markdown runs, set in the lamp's colours, as the styled text SwingTree paints.
///
/// While an answer streams in, its newest characters materialise: the last [#TAIL] characters
/// are drawn from nearly transparent at the very end to opaque, and `settled` lifts them all
/// towards opaque as the moment of their arrival passes. So text appears the way ink spreads,
/// rather than in jumps, without any clock in the data: each new piece restarts `settled` at 0.
final class Typeset {

    private Typeset() {}

    /// How many of the newest characters fade in.
    static final int TAIL = 48;

    /// How finely the fade is stepped. Characters of the same step share one styled run.
    private static final int STEPS = 8;

    static final int BODY = 14;

    /// @param settled 0 when the newest characters just arrived, 1 once they are fully there
    static Tuple<StyledString> of(Tuple<Markdown.Run> runs, double settled) {
        int total = 0;
        for (Markdown.Run run : runs) total += run.text().length();
        Tuple<StyledString> out = Tuple.of(StyledString.class);
        int position = 0;
        for (Markdown.Run run : runs) {
            String text = run.text();
            int start = 0;
            while (start < text.length()) {
                int step = step(total - (position + start), settled);
                int end = start + 1;
                while (end < text.length() && step(total - (position + end), settled) == step) end++;
                double alpha = (double) step / STEPS;
                out = out.add(StyledString.of(look(run, alpha), text.substring(start, end)));
                start = end;
            }
            position += text.length();
        }
        return out;
    }

    /// The fade step of a character `fromEnd` characters before the end: 1 is the last one.
    static int step(int fromEnd, double settled) {
        double alpha = Math.min(1.0, (double) fromEnd / TAIL + Math.max(0, Math.min(1, settled)));
        return Math.max(1, (int) Math.ceil(alpha * STEPS));
    }

    private static Configurator<FontConf> look(Markdown.Run run, double alpha) {
        Color colour = switch (run.inline()) {
            case CODE -> run.block() == Markdown.Block.CODE ? TEXT : CODE_TEXT;
            case LINK -> FLAME;
            case STRIKE -> SUBTEXT;
            case MARKER -> run.block() == Markdown.Block.RULE || run.block() == Markdown.Block.TABLE ? BORDER : BRASS;
            default -> run.block() == Markdown.Block.QUOTE ? SUBTEXT : TEXT;
        };
        int size = switch (run.block()) { case H1 -> 20; case H2 -> 17; case H3 -> 15; default -> BODY; };
        boolean heading = run.block() == Markdown.Block.H1 || run.block() == Markdown.Block.H2 || run.block() == Markdown.Block.H3;
        boolean bold = heading || run.inline() == Markdown.Inline.BOLD || run.inline() == Markdown.Inline.BOLD_ITALIC;
        boolean italic = run.inline() == Markdown.Inline.ITALIC || run.inline() == Markdown.Inline.BOLD_ITALIC
                      || (run.block() == Markdown.Block.QUOTE && run.inline() != Markdown.Inline.MARKER);
        boolean code = run.inline() == Markdown.Inline.CODE;
        boolean even = code || run.block() == Markdown.Block.TABLE;
        Color shown = faded(colour, alpha);
        return f -> {
            FontConf set = f.family(even ? MONO : FONT).size(even ? size - 1 : size).color(shown)
                            .weight(bold ? 2 : 1).posture(italic ? 0.2f : 0f);
            if (code) set = set.backgroundColor(faded(run.block() == Markdown.Block.CODE ? SMOKE : RAISED, alpha));
            if (run.inline() == Markdown.Inline.STRIKE) set = set.strikeThrough(true);
            if (run.inline() == Markdown.Inline.LINK) set = set.underlined(true);
            return set;
        };
    }

    private static Color faded(Color colour, double alpha) {
        return alpha >= 1 ? colour
             : new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), (int) Math.round(colour.getAlpha() * alpha));
    }
}
