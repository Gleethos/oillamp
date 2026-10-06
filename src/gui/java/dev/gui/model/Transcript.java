package dev.gui.model;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gui.pi.PiEvent;

import sprouts.Tuple;

/// A conversation with one genie, as the chat shows it.
///
/// Every change is a method that returns a new transcript. [#hear(PiEvent)] is where pi's events
/// become entries: pieces of an answer are added to the answer being written, a tool's result
/// is filed with the tool call it belongs to, and a finished answer replaces its pieces with the
/// text pi recorded.
public record Transcript(Tuple<Entry> entries) {

    /// oillamp's answer when it cannot reach the model service, as `Egress` words it.
    private static final Pattern UNREACHABLE = Pattern.compile("502 oillamp: cannot reach the model service at (\\S+) — (.+)", Pattern.DOTALL);

    /// An error with an HTTP status first, as pi gives a model service's answer: `429 {...}`, `451: {...}`.
    private static final Pattern STATUS = Pattern.compile("(\\d{3}):? (.+)", Pattern.DOTALL);

    /// The message in a model service's JSON error, such as `{"message":"…","type":"…"}`.
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

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
            case PiEvent.ToolStarted tool -> doneThinking().withoutEmptyAnswers().beforeTools().add(Entry.of(Entry.Kind.TOOL, tool.summary())
                    .withTitle(tool.tool()).withRef(tool.call()).withState(Entry.State.WRITING));
            case PiEvent.ToolFinished tool -> toolCall(tool.call())
                    .map(call -> replace(call.withDetail(tool.output())
                            .withState(tool.failed() ? Entry.State.FAILED : Entry.State.DONE)))
                    .orElse(this);
            case PiEvent.Answered answered -> answered(answered);
            case PiEvent.Retrying retry -> {
                // The failed answer before a retry said why already.
                String again = "Trying again, " + retry.attempt() + " of " + retry.attempts() + ".";
                boolean told = !entries.isEmpty() && entries.last().kind() == Entry.Kind.NOTICE && entries.last().isFailed();
                yield told ? notice(again) : problem(explained(retry.reason()) + " " + again);
            }
            case PiEvent.Settled ignored -> settled();
            case PiEvent.Refused refused -> problem("The genie could not take that: " + refused.reason());
            case PiEvent.History history -> asksTheSame(history) ? learnIds(history) : from(history);
            case PiEvent.Opened ignored -> this;
        };
    }

    /// Everything still being written is done: the genie stopped, or its sandbox went away.
    public Transcript settled() {
        return new Transcript(doneThinking().withoutEmptyAnswers().entries.map(entry ->
                entry.isWriting() ? entry.withState(Entry.State.DONE) : entry));
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    /// The user asks `text` instead of their question `id`: what came after that question goes
    /// from the chat, since pi keeps it as a branch of its own, and the new question takes its
    /// place.
    public Transcript askedInstead(String id, String text) {
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).kind() == Entry.Kind.YOU && entries.get(i).ref().equals(id))
                return new Transcript(entries.slice(0, i)).you(text);
        return this;
    }

    /// The conversation as pi has it, with nothing but what was said: the chat shows it when
    /// the genie wakes, or moves to another conversation or branch.
    private static Transcript from(PiEvent.History history) {
        Transcript said = empty();
        for (PiEvent.History.Line line : history.lines())
            said = line.failed() ? said.failed(line.text())
                 : said.add(Entry.of(line.fromUser() ? Entry.Kind.YOU : Entry.Kind.GENIE, line.text())
                                 .withRef(line.id()).withBeforeTools(line.usedTools()));
        return said;
    }

    /// pi's ids for the user's questions, learnt from what pi sent after an answer. The chat
    /// shows more than pi sends, such as the tools used, so it is never replaced then; it only
    /// learns the ids when it shows the same questions.
    public Transcript learn(PiEvent.History history) {
        return asksTheSame(history) ? learnIds(history) : this;
    }

    /// Whether the chat already shows the conversation pi sent, question for question. It then
    /// shows more than pi sends, such as the tools used and the files handed over, and is kept.
    private boolean asksTheSame(PiEvent.History history) {
        return questions().equals(history.lines().stream().filter(PiEvent.History.Line::fromUser)
                .map(PiEvent.History.Line::text).map(String::strip).toList());
    }

    /// pi's ids for the user's questions, which the chat learns after they were sent, so that
    /// each can be asked differently later.
    private Transcript learnIds(PiEvent.History history) {
        Iterator<PiEvent.History.Line> asked = history.lines().stream().filter(PiEvent.History.Line::fromUser).iterator();
        return new Transcript(entries.map(entry -> entry.kind() == Entry.Kind.YOU ? entry.withRef(asked.next().id()) : entry));
    }

    private List<String> questions() {
        return entries.stream().filter(entry -> entry.kind() == Entry.Kind.YOU).map(entry -> entry.text().strip()).toList();
    }

    private Transcript answered(PiEvent.Answered answered) {
        Transcript result = doneThinking().answering()
                .map(answer -> answered.text().isEmpty() ? doneThinking()
                             : doneThinking().replace(answer.withText(answered.text())))
                .orElseGet(() -> answered.text().isEmpty() ? doneThinking()
                               : doneThinking().add(Entry.of(Entry.Kind.GENIE, answered.text())));
        result = result.withoutEmptyAnswers();
        result = new Transcript(result.entries.map(entry ->
                entry.kind() == Entry.Kind.GENIE && entry.isWriting() ? entry.withState(Entry.State.DONE) : entry));
        return answered.failed().isEmpty() ? result : result.failed(answered.failed());
    }

    /// The model could not answer, and gave `error`: a problem, said plainly, or a notice when the
    /// genie was stopped, which is no problem.
    private Transcript failed(String error) {
        String stripped = error.strip().toLowerCase(Locale.ROOT);
        return stripped.endsWith("aborted") || stripped.endsWith("aborted.")
               ? notice("Stopped before the answer was done.")
               : problem(explained(error));
    }

    /// Why the model could not answer, said plainly. `error` is what pi got: oillamp's answer
    /// when it could not pass the request on, the SDK's word for a broken connection, or the
    /// model service's status and message.
    private static String explained(String error) {
        String raw = error.strip();
        Matcher unreachable = UNREACHABLE.matcher(raw);
        if (unreachable.matches())
            return "oillamp could not reach the model service at " + unreachable.group(1) + " (" + unreachable.group(2).strip()
                   + "), so the genie got no answer. The service, or this computer's way to it, is down; the genie is fine.";
        if (raw.equals("Connection error."))
            return "The connection to the model service broke off before an answer came, so the genie got none. The genie is fine.";
        Matcher status = STATUS.matcher(raw);
        String told = "The model could not answer: " + raw;
        if (status.matches()) {
            int code = Integer.parseInt(status.group(1));
            String said = status.group(2).strip();
            Matcher message = MESSAGE.matcher(said);
            if (message.find()) said = message.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
            String who = code == 401 || code == 403 ? "The model service refused the key"
                       : code == 429 ? "The model service takes no more requests for now, or the key's credit ran out"
                       : code >= 500 ? "The model service had trouble of its own"
                       : "The model service refused the request";
            // oillamp says plainly already what it refused.
            told = said.startsWith("oillamp:") ? said : who + " (" + code + "): " + said;
        }
        // Ending as a sentence, so more can follow it.
        return told.matches("(?s).*[.!?]") ? told : told + ".";
    }

    /// The genie goes on to use a tool, so its last answer since the user wrote said what it does.
    private Transcript beforeTools() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.kind() == Entry.Kind.YOU) break;
            if (entry.kind() == Entry.Kind.GENIE) return entry.beforeTools() ? this : replace(entry.withBeforeTools(true));
        }
        return this;
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
