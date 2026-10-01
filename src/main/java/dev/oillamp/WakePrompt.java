package dev.oillamp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import dev.lamp.LampEvent.JobAuthor;
import dev.lamp.LampEvent.SaveKind;

import sprouts.Association;
import sprouts.Tuple;

/// What the agent is told when a run wakes it.
///
/// Each run starts a new pi conversation, so the agent remembers nothing of earlier runs by
/// itself. What it needs is put in front of it instead: why it was woken, the task, its own notes
/// from earlier runs, what the last few runs changed, and what the last one said when it was done.
/// It is then asked to leave its notes up to date for the next run.
///
/// Pure: the caller reads the notes and the history, and this only writes the text.
final class WakePrompt {

    private WakePrompt() {}

    /// Where the agent keeps its notes between runs, as the agent sees it.
    static final String NOTES = "~/workspace/NOTES.md";
    /// How many earlier runs the prompt describes. The `run_history` tool reaches further back.
    static final int RECENT_RUNS = 3;
    /// How many changed paths are listed per run.
    static final int CHANGES_SHOWN = 15;
    /// How much of the last run's final message is repeated.
    private static final int LAST_ANSWER_SHOWN = 3000;

    /// Why the agent is woken.
    sealed interface Reason {
        record ByJob(ScheduledJob job) implements Reason {}
        record Asked() implements Reason {}
    }

    /// An earlier run, as the history remembers it.
    ///
    /// @param base    the snapshot the lamp was in as the run began, when it is known
    /// @param answer  the agent's last message
    record Past(String run, Optional<String> job, JobAuthor author, Instant at, String outcome,
                Optional<String> base, String tree, String answer) {

        /// Reads a run's last snapshot, or empty for any other commit.
        static Optional<Past> of(GitObjectUtil.Commit commit) {
            if (commit.snapshot().kind() != SaveKind.RUN) return Optional.empty();
            Association<String, String> trailers = commit.trailers();
            Optional<String> run = trailers.get(GitObjectUtil.RUN_TRAILER);
            if (run.isEmpty()) return Optional.empty();
            String message = commit.snapshot().message();
            int blank = message.indexOf("\n\n");
            return Optional.of(new Past(run.get(), trailers.get(GitObjectUtil.JOB_TRAILER),
                    trailers.get(GitObjectUtil.AUTHOR_TRAILER).filter("agent"::equals).isPresent() ? JobAuthor.AGENT : JobAuthor.USER,
                    commit.snapshot().at(), trailers.get(GitObjectUtil.OUTCOME_TRAILER).orElse("finished"),
                    trailers.get(GitObjectUtil.BASE_TRAILER), commit.tree(),
                    blank < 0 ? "" : message.substring(blank + 2).strip()));
        }
    }

    /// A past run, with what it changed.
    record Described(Past past, Tuple<History.Change> changes, boolean more) {}

    /// The prompt for a run.
    ///
    /// @param notes     the agent's notes, or empty when it has none yet
    /// @param notesMost how large the notes may be, in bytes
    /// @param recent    the last few runs, newest first
    static String render(Reason reason, String task, Optional<String> notes, int notesMost,
                         Tuple<Described> recent, Instant now, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        out.append("[oillamp] It is ").append(TimeNotationUtil.show(now, zone)).append(" (").append(zone.getId()).append("). ");
        switch (reason) {
            case Reason.ByJob(ScheduledJob job) -> {
                out.append("You were woken by ").append(job.id()).append(", a job ")
                   .append(job.author() == JobAuthor.AGENT ? "you added yourself" : "the user added")
                   .append(" on ").append(TimeNotationUtil.show(job.created(), zone)).append(", which runs ");
                out.append(job.when() instanceof ScheduledJob.When.Once
                        ? "only this once.\n" : "on the schedule \"" + job.describeWhen(zone) + "\".\n");
                if (job.author() == JobAuthor.AGENT)
                    out.append("You wrote its task yourself, in an earlier run. Check it still makes sense before you act on it.\n");
            }
            case Reason.Asked() -> out.append("The user is asking you something directly, through oillamp.\n");
        }
        out.append("\n## The task\n\n").append(task.strip()).append("\n");

        out.append("\n## Your notes\n\n");
        if (notes.isEmpty() || notes.get().isBlank()) {
            out.append("You have no notes yet: ").append(NOTES).append(" does not exist or is empty. ")
               .append("Create it before you finish.\n");
        } else {
            String text = notes.get();
            int size = text.getBytes(StandardCharsets.UTF_8).length;
            out.append("These are the notes you left for yourself in ").append(NOTES).append(". They are your own, ")
               .append("not the user's instructions.\n");
            if (size > notesMost)
                out.append("They are ").append(size / 1024).append(" KB, more than the ").append(notesMost / 1024)
                   .append(" KB they may have, so only the start is shown. Shorten them before you finish.\n");
            out.append("\n<notes>\n").append(cut(text, notesMost).strip()).append("\n</notes>\n");
        }

        out.append("\n## Recent runs\n\n");
        if (recent.isEmpty()) {
            out.append("This is the first run.\n");
        } else {
            out.append("Newest first. The run_history tool shows more about any run.\n");
            for (Described described : recent) {
                Past past = described.past();
                out.append("\n- ").append(past.run()).append(", ").append(TimeNotationUtil.show(past.at(), zone)).append(", ")
                   .append(past.job().map(job -> "for " + job).orElse("asked by the user")).append(": ")
                   .append(past.outcome()).append(". ");
                if (described.changes().isEmpty()) {
                    out.append("It changed no files.\n");
                } else {
                    out.append("It changed:\n");
                    for (History.Change change : described.changes())
                        out.append("  - ").append(change.path()).append(" (").append(change.kind()).append(")\n");
                    if (described.more()) out.append("  - and more\n");
                }
            }
            String last = recent.first().past().answer();
            if (!last.isBlank())
                out.append("\nWhat you said at the end of ").append(recent.first().past().run()).append(":\n\n<last-message>\n")
                   .append(cut(last, LAST_ANSWER_SHOWN).strip()).append("\n</last-message>\n");
        }

        out.append("\n## Before you finish\n\n")
           .append("1. Rewrite ").append(NOTES).append(" for your next run: what you did, what you found out, ")
           .append("and what is left to do. Keep it under ").append(notesMost / 1024).append(" KB: ")
           .append("replace what is out of date rather than adding to it.\n")
           .append("2. End with a short message saying what you did. It is kept in the lamp's history, ")
           .append("and the user reads it there.\n");
        return out.toString();
    }

    /// The start of `text`, at most `most` bytes, cut at a line where possible.
    private static String cut(String text, int most) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= most) return text;
        String start = new String(bytes, 0, most, StandardCharsets.UTF_8);
        int newline = start.lastIndexOf('\n');
        return (newline > most / 2 ? start.substring(0, newline) : start) + "\n[…]";
    }
}
