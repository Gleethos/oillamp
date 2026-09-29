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
    enum Block { PARAGRAPH, H1, H2, H3, QUOTE, CODE, RULE, TABLE }

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

    private static final Pattern DIVIDER = Pattern.compile("^\\s*:?-+:?\\s*$");

    static Tuple<Run> parse(String markdown) {
        Tuple<Run> runs = Tuple.of(Run.class);
        boolean inCode = false, first = true;
        String[] lines = markdown.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).matches()) {
                inCode = !inCode;
                continue;                               // the fence itself is not shown
            }
            if (!first) runs = runs.add(Run.of("\n", Block.PARAGRAPH, Inline.PLAIN));
            first = false;
            if (inCode) {
                runs = runs.add(Run.of(line.isEmpty() ? " " : line, Block.CODE, Inline.CODE));
            } else if (line.startsWith("|")) {
                int end = i;
                while (end + 1 < lines.length && lines[end + 1].startsWith("|")) end++;
                runs = table(java.util.Arrays.asList(lines).subList(i, end + 1), runs);
                i = end;
            } else {
                runs = line(line, runs);
            }
        }
        return runs;
    }

    /// A table, with its columns lined up, which takes a font of even widths. The pipes become
    /// thin lines, the row of dashes under the header a line across, and the header is bold. A
    /// cell keeps its words without their Markdown markers, and the alignment the dashes ask for.
    private static Tuple<Run> table(java.util.List<String> lines, Tuple<Run> runs) {
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        for (String line : lines) rows.add(cells(line));
        int divider = rows.size() > 1 && rows.get(1).stream().allMatch(cell -> DIVIDER.matcher(cell).matches()) ? 1 : -1;
        int columns = rows.stream().mapToInt(java.util.List::size).max().orElse(0);
        int[] widths = new int[columns];
        char[] align = new char[columns];
        java.util.Arrays.fill(align, 'l');
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                String cell = rows.get(r).get(c);
                if (r == divider) {
                    align[c] = cell.startsWith(":") && cell.endsWith(":") ? 'c' : cell.endsWith(":") ? 'r' : 'l';
                } else {
                    widths[c] = Math.max(widths[c], cell.length());
                }
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) runs = runs.add(Run.of("\n", Block.PARAGRAPH, Inline.PLAIN));
            if (r == divider) {
                StringBuilder across = new StringBuilder();
                for (int c = 0; c < columns; c++) across.append(c == 0 ? "" : "─┼─").append("─".repeat(widths[c]));
                runs = runs.add(Run.of(across.toString(), Block.TABLE, Inline.MARKER));
                continue;
            }
            Inline look = r < divider ? Inline.BOLD : Inline.PLAIN;
            for (int c = 0; c < columns; c++) {
                if (c > 0) runs = runs.add(Run.of(" │ ", Block.TABLE, Inline.MARKER));
                String cell = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                runs = runs.add(Run.of(padded(cell, widths[c], align[c]), Block.TABLE, look));
            }
        }
        return runs;
    }

    /// The cells of a table's row, trimmed, without their Markdown markers.
    private static java.util.List<String> cells(String line) {
        String inner = line.strip();
        inner = inner.substring(1);                     // the leading pipe
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        java.util.List<String> cells = new java.util.ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            StringBuilder words = new StringBuilder();
            for (Run run : inline(cell.strip(), Block.TABLE, Tuple.of(Run.class))) words.append(run.text());
            cells.add(words.toString());
        }
        return cells;
    }

    private static String padded(String cell, int width, char align) {
        int room = width - cell.length();
        return switch (align) {
            case 'r' -> " ".repeat(room) + cell;
            case 'c' -> " ".repeat(room / 2) + cell + " ".repeat(room - room / 2);
            default -> cell + " ".repeat(room);
        };
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
