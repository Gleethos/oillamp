package dev.gui.model;

import java.util.Optional;

import dev.gui.pi.PiEvent;

import sprouts.Tuple;

/// A conversation with one genie, as the chat shows it.
///
/// Every change is a method that returns a new transcript. [#hear(PiEvent)] is where pi's events
/// become entries: pieces of an answer are added to the answer being written, a tool's result
/// is filed with the tool call it belongs to, and a finished answer replaces its pieces with the
/// text pi recorded.
public record Transcript(Tuple<Entry> entries) {

    public static Transcript empty() { return new Transcript(Tuple.of(Entry.class)); }

    /// What the user wrote.
    public Transcript you(String text) { return add(Entry.of(Entry.Kind.YOU, text)); }

    /// Something the app says, such as a genie waking up.
    public Transcript notice(String text) { return add(Entry.of(Entry.Kind.NOTICE, text)); }

    /// Something that went wrong, said in the chat so the user sees it where they look.
    public Transcript problem(String text) {
        return add(Entry.of(Entry.Kind.NOTICE, text).withState(Entry.State.FAILED));
    }

    /// A file the genie handed over, which the user can save.
    public Transcript handedOver(Handout file) {
        return add(Entry.of(Entry.Kind.FILE, "handed you a file, " + file.readableSize()).withTitle(file.name()));
    }

    /// A file the user gave the genie.
    public Transcript gave(String name) {
        return add(Entry.of(Entry.Kind.FILE, "you gave the genie this file, in ~/inbox").withTitle(name));
    }

    public Transcript hear(PiEvent event) {
        return switch (event) {
            case PiEvent.Said said -> {
                Transcript thought = doneThinking();
                yield thought.answering()
                        .map(answer -> thought.replace(answer.withText(answer.text() + said.text())))
                        .orElseGet(() -> thought.add(answer(said.text())));
            }
            case PiEvent.Thinking thinking -> thinking()
                    .map(thought -> replace(thought.withText(thought.text() + thinking.more())))
                    // A start with nothing in it yet shows nothing: many models only say they
                    // think, and some say so in the middle of an answer.
                    .orElseGet(() -> thinking.more().isEmpty() ? this
                               : add(Entry.of(Entry.Kind.THINKING, thinking.more()).withState(Entry.State.WRITING)));
            case PiEvent.ToolStarted tool -> doneThinking().withoutEmptyAnswers().add(Entry.of(Entry.Kind.TOOL, tool.summary())
                    .withTitle(tool.tool()).withRef(tool.call()).withState(Entry.State.WRITING));
            case PiEvent.ToolFinished tool -> toolCall(tool.call())
                    .map(call -> replace(call.withDetail(tool.output())
                            .withState(tool.failed() ? Entry.State.FAILED : Entry.State.DONE)))
                    .orElse(this);
            case PiEvent.Answered answered -> answered(answered);
            case PiEvent.Retrying retry -> notice("The model did not answer (" + retry.reason()
                    + "). Trying again, " + retry.attempt() + " of " + retry.attempts() + ".");
            case PiEvent.Settled ignored -> settled();
            case PiEvent.Refused refused -> problem("The genie could not take that: " + refused.reason());
            case PiEvent.History history -> {
                Tuple<Entry> said = Tuple.of(Entry.class);
                for (PiEvent.History.Line line : history.lines())
                    said = said.add(Entry.of(line.fromUser() ? Entry.Kind.YOU : Entry.Kind.GENIE, line.text()));
                yield new Transcript(said);
            }
        };
    }

    /// Everything still being written is done: the genie stopped, or its sandbox went away.
    public Transcript settled() {
        return new Transcript(doneThinking().withoutEmptyAnswers().entries.map(entry ->
                entry.isWriting() ? entry.withState(Entry.State.DONE) : entry));
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    private Transcript answered(PiEvent.Answered answered) {
        Transcript result = doneThinking().answering()
                .map(answer -> answered.text().isEmpty() ? doneThinking()
                             : doneThinking().replace(answer.withText(answered.text())))
                .orElseGet(() -> answered.text().isEmpty() ? doneThinking()
                               : doneThinking().add(Entry.of(Entry.Kind.GENIE, answered.text())));
        result = result.withoutEmptyAnswers();
        result = new Transcript(result.entries.map(entry ->
                entry.kind() == Entry.Kind.GENIE && entry.isWriting() ? entry.withState(Entry.State.DONE) : entry));
        return answered.failed().isEmpty() ? result : result.problem(answered.failed());
    }

    private static Entry answer(String text) {
        return Entry.of(Entry.Kind.GENIE, text).withState(Entry.State.WRITING);
    }

    /// The answer the genie is writing in this turn, since the user last wrote: the one more
    /// text goes into, even when a thought or a tool call came after it. Some models start a
    /// thought in the middle of their text, and the answer must not break in two there.
    private Optional<Entry> answering() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.kind() == Entry.Kind.YOU) break;
            if (entry.kind() == Entry.Kind.GENIE && entry.isWriting()) return Optional.of(entry);
        }
        return Optional.empty();
    }

    /// The genie's thoughts being written now: the last entry, if it is that.
    private Optional<Entry> thinking() {
        if (entries.isEmpty()) return Optional.empty();
        Entry last = entries.last();
        return last.kind() == Entry.Kind.THINKING && last.isWriting() ? Optional.of(last) : Optional.empty();
    }

    /// The genie stopped thinking, because it began to answer or to use a tool. Thoughts it did
    /// not share leave nothing behind.
    private Transcript doneThinking() {
        return thinking().map(thought -> thought.text().isBlank()
                        ? new Transcript(entries.removeAt(entries.size() - 1))
                        : replace(thought.withState(Entry.State.DONE)))
                .orElse(this);
    }

    private Optional<Entry> toolCall(String ref) {
        for (Entry entry : entries)
            if (entry.kind() == Entry.Kind.TOOL && entry.ref().equals(ref)) return Optional.of(entry);
        return Optional.empty();
    }

    private Transcript withoutEmptyAnswers() {
        return new Transcript(entries.removeIf(entry -> entry.isEmptyAnswer() && entry.isWriting()));
    }

    private Transcript add(Entry entry) { return new Transcript(entries.add(entry)); }

    /// Replaces one entry in place. The chat redraws only that entry's row, which matters while
    /// an answer streams in, piece by piece.
    private Transcript replace(Entry changed) {
        for (int i = entries.size() - 1; i >= 0; i--)
            if (entries.get(i).id().equals(changed.id())) return new Transcript(entries.setAt(i, changed));
        return this;
    }
}
