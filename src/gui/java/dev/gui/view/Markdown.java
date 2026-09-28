package dev.gui.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// Markdown as models write it, read into runs of text that each have one look.
///
/// Pure: text in, runs out, nothing drawn. [Typeset] turns the runs into what SwingTree paints.
/// Only what models commonly write is understood: headings, emphasis, inline code, fenced code
/// blocks, lists, quotes, links, rules and tables. Anything else stays as it was written, and so
/// does anything not closed yet, such as `**bold` while an answer is still streaming in.
final class Markdown {

    private Markdown() {}

    /// What a line is.
    enum Block { PARAGRAPH, H1, H2, H3, QUOTE, CODE, RULE }

    /// How a run within a line looks.
    enum Inline { PLAIN, BOLD, ITALIC, BOLD_ITALIC, STRIKE, CODE, LINK, MARKER }

    /// A piece of text with one look. Line breaks are runs of their own, `"\n"`.
    record Run(String text, Block block, Inline inline) {
        static Run of(String text, Block block, Inline inline) { return new Run(text, block, inline); }
        boolean isLineBreak() { return text.equals("\n"); }
    }

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])\\s*(\\1\\s*){2,}$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    static Tuple<Run> parse(String markdown) {
        Tuple<Run> runs = Tuple.of(Run.class);
        boolean inCode = false, first = true;
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.replace("\r", "");
            if (FENCE.matcher(line).matches()) {
                inCode = !inCode;
                continue;                               // the fence itself is not shown
            }
            if (!first) runs = runs.add(Run.of("\n", Block.PARAGRAPH, Inline.PLAIN));
            first = false;
            // Code, and tables, whose columns only line up in a font of even widths.
            runs = inCode || line.startsWith("|")
                    ? runs.add(Run.of(line.isEmpty() ? " " : line, Block.CODE, Inline.CODE))
                    : line(line, runs);
        }
        return runs;
    }

    private static Tuple<Run> line(String line, Tuple<Run> runs) {
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            Block level = switch (heading.group(1).length()) { case 1 -> Block.H1; case 2 -> Block.H2; default -> Block.H3; };
            return inline(heading.group(2), level, runs);
        }
        if (RULE.matcher(line).matches())
            return runs.add(Run.of("────────────────────────", Block.RULE, Inline.MARKER));
        if (line.startsWith(">")) {
            runs = runs.add(Run.of("▎ ", Block.QUOTE, Inline.MARKER));
            return inline(line.substring(1).stripLeading(), Block.QUOTE, runs);
        }
        Matcher bullet = BULLET.matcher(line);
        if (bullet.matches()) {
            runs = runs.add(Run.of(bullet.group(1) + "  •  ", Block.PARAGRAPH, Inline.MARKER));
            return inline(bullet.group(2), Block.PARAGRAPH, runs);
        }
        Matcher numbered = NUMBERED.matcher(line);
        if (numbered.matches()) {
            runs = runs.add(Run.of(numbered.group(1) + "  " + numbered.group(2) + ".  ", Block.PARAGRAPH, Inline.MARKER));
            return inline(numbered.group(3), Block.PARAGRAPH, runs);
        }
        return inline(line, Block.PARAGRAPH, runs);
    }

    /// Emphasis, code and links within one line. A marker without its closing partner is text.
    private static Tuple<Run> inline(String text, Block block, Tuple<Run> runs) {
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            java.util.Optional<Span> found = span(text, i);
            if (found.isEmpty()) {
                plain.append(text.charAt(i++));
                continue;
            }
            Span span = found.get();
            if (!plain.isEmpty()) {
                runs = runs.add(Run.of(plain.toString(), block, Inline.PLAIN));
                plain.setLength(0);
            }
            runs = runs.add(Run.of(span.text(), block, span.look()));
            i = span.end();
        }
        if (!plain.isEmpty()) runs = runs.add(Run.of(plain.toString(), block, Inline.PLAIN));
        return runs;
    }

    private record Span(String text, Inline look, int end) {}

    private static java.util.Optional<Span> span(String text, int at) {
        if (text.charAt(at) == '`') return closed(text, at, "`", Inline.CODE);
        if (text.startsWith("***", at)) return closed(text, at, "***", Inline.BOLD_ITALIC);
        if (text.startsWith("**", at)) return closed(text, at, "**", Inline.BOLD);
        if (text.startsWith("__", at)) return closed(text, at, "__", Inline.BOLD);
        if (text.startsWith("~~", at)) return closed(text, at, "~~", Inline.STRIKE);
        if (text.charAt(at) == '*' && at + 1 < text.length() && text.charAt(at + 1) != ' ')
            return closed(text, at, "*", Inline.ITALIC);
        // An underscore inside a word, as in snake_case, is not emphasis.
        if (text.charAt(at) == '_' && (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))))
            return closed(text, at, "_", Inline.ITALIC);
        if (text.charAt(at) == '[') {
            int close = text.indexOf("](", at + 1);
            int end = close < 0 ? -1 : text.indexOf(')', close + 2);
            if (close > at + 1 && end > 0) return java.util.Optional.of(new Span(text.substring(at + 1, close), Inline.LINK, end + 1));
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<Span> closed(String text, int at, String marker, Inline look) {
        int end = text.indexOf(marker, at + marker.length());
        // Not closed, or closed at once, as in `**` alone: text, not emphasis.
        if (end < 0 || end == at + marker.length()) return java.util.Optional.empty();
        return java.util.Optional.of(new Span(text.substring(at + marker.length(), end), look, end + marker.length()));
    }
}
