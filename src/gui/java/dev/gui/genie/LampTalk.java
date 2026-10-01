package dev.gui.genie;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.gui.model.Conversation;
import dev.gui.model.Recurrence;
import dev.gui.model.Schedule;
import dev.gui.pi.PiEvent;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

import sprouts.Tuple;

/// Turns what the Lamp API reports into the values the chat and the tree are drawn from.
final class LampTalk {

    private LampTalk() {}

    /// A conversation as the tree of conversations shows it: only which entries are questions.
    static Conversation conversation(Lamp.Conversation conversation) {
        Tuple<Conversation.Step> steps = Tuple.of(Conversation.Step.class);
        for (Lamp.Conversation.Entry entry : conversation.entries()) {
            boolean asked = entry.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT;
            steps = steps.add(new Conversation.Step(entry.id(), entry.parent().orElse(""), asked, asked ? entry.text() : ""));
        }
        return new Conversation(conversation.id(), conversation.file(), conversation.name(),
                conversation.modified().toString(), steps, conversation.job().orElse(""));
    }

    /// The questions and answers from the start of `conversation` to `leaf`, as the chat shows them.
    static PiEvent.History history(Lamp.Conversation conversation, String leaf) {
        Tuple<PiEvent.History.Line> lines = Tuple.of(PiEvent.History.Line.class);
        for (Lamp.Conversation.Entry entry : conversation.lineTo(leaf)) {
            boolean asked = entry.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT;
            if ((asked || entry.kind() == Lamp.Conversation.Kind.MESSAGE_FROM_AGENT) && !entry.text().isBlank())
                lines = lines.add(new PiEvent.History.Line(asked, entry.text(), entry.id()));
        }
        return new PiEvent.History(lines, leaf);
    }

    /// Empty for [LampEvent.Progress.Opened], which the runner handles itself.
    static Optional<PiEvent> chatEventFor(LampEvent.Progress progress) {
        return switch (progress) {
            case LampEvent.Progress.Opened ignored -> Optional.empty();
            case LampEvent.Progress.Said said -> Optional.of(new PiEvent.Said(said.text()));
            case LampEvent.Progress.Thought thought -> Optional.of(new PiEvent.Thinking(thought.text()));
            case LampEvent.Progress.Answered answered -> Optional.of(new PiEvent.Answered(
                    answered.failed() ? "" : answered.text(), answered.failed() ? answered.text() : "", 0));
            case LampEvent.Progress.ToolStarted tool -> Optional.of(new PiEvent.ToolStarted(tool.call(), tool.tool(), tool.summary()));
            case LampEvent.Progress.ToolFinished tool -> Optional.of(new PiEvent.ToolFinished(tool.call(), tool.failed(), tool.output()));
            case LampEvent.Progress.Retrying retrying -> Optional.of(new PiEvent.Retrying(retrying.attempt(), retrying.most(), retrying.why()));
        };
    }

    // ─── the schedule ──────────────────────────────────────────────────────────────────────

    /// How oillamp writes the time of a job that runs once, after `once at `, on the clock of
    /// the schedule's zone.
    private static final DateTimeFormatter ONCE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    static Schedule.Job job(LampEvent.Job job, ZoneId zone) {
        boolean once = job.when().startsWith("once at ");
        Optional<Instant> at = Optional.empty();
        if (once) {
            try {
                at = Optional.of(LocalDateTime.parse(job.when().substring("once at ".length()), ONCE).atZone(zone).toInstant());
            } catch (DateTimeParseException unreadable) {
                at = job.next();
            }
        }
        return new Schedule.Job(job.id(), once ? Optional.empty() : Optional.of(Recurrence.of(job.when())), at,
                job.next(), job.upcoming(), job.prompt(), job.author() == LampEvent.JobAuthor.AGENT,
                job.expires(), job.enabled());
    }

    static Tuple<Schedule.Job> jobs(LampEvent.Schedule schedule) {
        ZoneId zone = ZoneId.of(schedule.zone());
        return schedule.jobs().mapTo(Schedule.Job.class, job -> job(job, zone));
    }

    /// The runs jobs had, from the lamp's snapshots, the most recent last. A run's last snapshot
    /// says which job it was, how it ended, and holds what the genie said last below its first line.
    static Tuple<Schedule.Run> runs(List<LampEvent.Snapshot> snapshots) {
        Tuple<Schedule.Run> runs = Tuple.of(Schedule.Run.class);
        for (LampEvent.Snapshot snapshot : snapshots.reversed()) {
            if (snapshot.kind() != LampEvent.SaveKind.RUN || snapshot.job().isEmpty() || snapshot.run().isEmpty()) continue;
            int body = snapshot.message().indexOf("\n\n");
            String said = body < 0 ? "" : snapshot.message().substring(body + 2).strip();
            runs = runs.add(new Schedule.Run(snapshot.run().get(), snapshot.job().get(), snapshot.at(),
                    snapshot.outcome().map(LampTalk::outcome).orElse(Schedule.Outcome.FAILED), said, snapshot.conversation()));
        }
        return runs;
    }

    static Schedule.Outcome outcome(LampEvent.RunOutcome outcome) {
        return switch (outcome) {
            case FINISHED -> Schedule.Outcome.FINISHED;
            case FAILED -> Schedule.Outcome.FAILED;
            case TIMED_OUT -> Schedule.Outcome.TIMED_OUT;
            case INTERRUPTED, CANCELLED -> Schedule.Outcome.STOPPED;
        };
    }
}
