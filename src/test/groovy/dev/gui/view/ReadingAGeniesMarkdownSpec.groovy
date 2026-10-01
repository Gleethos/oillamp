package dev.gui.view

import spock.lang.Specification

import static dev.gui.view.MarkdownParsingUtil.Block.*
import static dev.gui.view.MarkdownParsingUtil.Inline.*

/**
 *  How a genie's answer, written in Markdown as models write it, is read for the chat.
 *
 *  <p>{@link MarkdownParsingUtil} turns the text into runs that each have one look, which
 *  {@link Typeset} then sets in the lamp's colours for SwingTree to paint. Both are pure, so
 *  what an answer looks like is pinned here without a window.
 */
class ReadingAGeniesMarkdownSpec extends Specification {

    // Both kinds of look have a CODE; these say which one is meant.
    static final MarkdownParsingUtil.Block CODE_LINE = MarkdownParsingUtil.Block.CODE
    static final MarkdownParsingUtil.Inline CODE_SPAN = MarkdownParsingUtil.Inline.CODE

    def 'Emphasis, code and links lose their markers and keep their look'() {
        reportInfo """
            Models write **bold**, *italic*, `code` and [links](…). The chat shows what they
            mean: the markers go, the look stays. A link shows its label; the address is not
            something the user can open from the chat, so it is not shown.
        """
        expect:
            runs('Run **pip install** or *maybe* `uv add x`, see [the docs](https://docs.example.com).') == [
                    ['Run ', PARAGRAPH, PLAIN], ['pip install', PARAGRAPH, BOLD], [' or ', PARAGRAPH, PLAIN],
                    ['maybe', PARAGRAPH, ITALIC], [' ', PARAGRAPH, PLAIN], ['uv add x', PARAGRAPH, CODE_SPAN],
                    [', see ', PARAGRAPH, PLAIN], ['the docs', PARAGRAPH, LINK], ['.', PARAGRAPH, PLAIN]]
    }

    def 'A marker that is not closed yet stays as written, so a streaming answer never jumps'() {
        reportInfo """
            While an answer streams in, `**bold` arrives before its closing `**`. Until then the
            asterisks are shown as they are; the moment the closing marker arrives, the run
            turns bold. And an underscore inside a word, as in snake_case, is not emphasis.
        """
        expect:
            runs('This is **not closed') == [['This is **not closed', PARAGRAPH, PLAIN]]
            runs('call read_file_now') == [['call read_file_now', PARAGRAPH, PLAIN]]
            runs('a lone ** here') == [['a lone ** here', PARAGRAPH, PLAIN]]
    }

    def 'Headings, lists, quotes and rules are shown as such'() {
        reportInfo """
            Block-level Markdown sets the look of a whole line: headings are larger and bold,
            list items get a bullet or their number, quotes a bar and a quieter voice, and a
            rule becomes a line.
        """
        expect:
            runs('## Plan\n- first\n2. second\n> careful\n---') == [
                    ['Plan', H2, PLAIN], ['\n', PARAGRAPH, PLAIN],
                    ['  •  ', PARAGRAPH, MARKER], ['first', PARAGRAPH, PLAIN], ['\n', PARAGRAPH, PLAIN],
                    ['  2.  ', PARAGRAPH, MARKER], ['second', PARAGRAPH, PLAIN], ['\n', PARAGRAPH, PLAIN],
                    ['▎ ', QUOTE, MARKER], ['careful', QUOTE, PLAIN], ['\n', PARAGRAPH, PLAIN],
                    ['────────────────────────', RULE, MARKER]]
    }

    def 'A fenced code block is shown as code, line by line, with its fences hidden'() {
        reportInfo """
            Models put commands and programs in fenced blocks. Inside one, nothing is Markdown:
            `**` in a program stays `**`. The fences, with their language name, are not shown;
            the code is set in a font of even widths.
        """
        expect:
            runs('Try:\n```bash\necho **hi**\n\nls\n```') == [
                    ['Try:', PARAGRAPH, PLAIN], ['\n', PARAGRAPH, PLAIN],
                    ['echo **hi**', CODE_LINE, CODE_SPAN], ['\n', PARAGRAPH, PLAIN],
                    [' ', CODE_LINE, CODE_SPAN], ['\n', PARAGRAPH, PLAIN],
                    ['ls', CODE_LINE, CODE_SPAN]]
    }

    def 'A table has its columns lined up, a bold header and thin lines instead of pipes'() {
        reportInfo """
            Models answer with tables often. Written in Markdown, a table is rows of cells
            between pipes, with a row of dashes under the header. The chat lines the columns
            up in a font of even widths, turns the pipes into thin lines and the dashes into a
            line across, sets the header in bold, and drops Markdown markers inside cells. A
            colon at the right end of the dashes aligns that column to the right, as numbers
            usually want.
        """
        expect:
            text('| Train | Price |\n|---|---:|\n| **Railjet** | €29.90 |\n| ICE | €9 |') ==
                    'Train   │  Price\n' +
                    '────────┼───────\n' +
                    'Railjet │ €29.90\n' +
                    'ICE     │     €9'

        and: 'the header is bold, the lines are drawn quietly'
            runs('| a | b |\n|---|---|\n| 1 | 2 |').take(3) == [['a', TABLE, BOLD], [' │ ', TABLE, MARKER], ['b', TABLE, BOLD]]
    }

    def 'The newest characters fade in, and are fully there once they have settled'() {
        reportInfo """
            While an answer streams in, its last characters are drawn from nearly transparent,
            at the very end, to opaque, over a few dozen characters; as each new piece settles,
            the whole tail lifts to opaque. So new text appears the way ink spreads rather than
            in jumps. The fade is stepped, so a tail is a handful of styled runs, not one per
            character.
        """
        expect: 'just arrived: the very last character is faintest, earlier ones are solid'
            Typeset.step(1, 0) == 1
            Typeset.step(Typeset.TAIL.intdiv(2), 0) == 4
            Typeset.step(Typeset.TAIL, 0) == 8
            Typeset.step(1000, 0) == 8

        and: 'settled: everything is solid'
            Typeset.step(1, 1) == 8

        and: 'a settled answer is one styled run per Markdown run'
            Typeset.of(MarkdownParsingUtil.parse('plain **bold**'), 1).size() == 2
            Typeset.of(MarkdownParsingUtil.parse('x' * 100), 0).size() == 8
    }

    private static String text(String markdown) {
        MarkdownParsingUtil.parse(markdown).toList().collect { it.text() }.join('')
    }

    private static List<List> runs(String markdown) {
        MarkdownParsingUtil.parse(markdown).toList().collect { [it.text(), it.block(), it.inline()] }
    }
}
