package dev.gui.model;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import sprouts.HasId;
import sprouts.Tuple;

/// A genie's schedule as its page shows it: the jobs that wake it, the runs they had, and the
/// job being written.
///
/// Read from the genie's lamp, awake or asleep. Jobs only run while the genie is awake.
///
/// @param read    whether it was read from the lamp yet; before that, the page says it is looking
/// @param paused  whether the user paused the whole schedule
/// @param zone    the time zone the jobs' times are on, this computer's
/// @param jobs    in the order they were added
/// @param runs    the runs jobs had over the last week, the most recent last
/// @param running the run a job has going now, if any
/// @param draft   the job being written or changed, while the page shows its editor
/// @param picked  the job whose times the timeline brings forward; empty for none
/// @param earlier whether the timeline shows every run of the last week, not only the last few
/// @param problem why the last change could not be made, in the lamp's words; empty when it could
/// @param busy    whether a change is on its way to the lamp
public record Schedule(boolean read, boolean paused, ZoneId zone, Tuple<Job> jobs, Tuple<Run> runs,
                       Optional<Running> running, Optional<JobDraft> draft, String picked,
                       boolean earlier, String problem, boolean busy) {

    /// A job as oillamp lists it, read into the page's terms.
    ///
    /// @param repeats how it repeats; empty for a job that runs once
    /// @param at      when a job that runs once runs; empty for a repeating one
    /// @param next    when it runs next; empty when it is switched off. At or before now, it came
    ///                due and runs as soon as the genie is awake
    /// @param upcoming when it runs over the next seven days, after now, earliest first
    /// @param byGenie whether the genie added it itself
    /// @param expires when it comes off the schedule; empty for never
    public record Job(String id, Optional<Recurrence> repeats, Optional<Instant> at, Optional<Instant> next, Tuple<Instant> upcoming,
                      String prompt, boolean byGenie, Optional<Instant> expires, boolean enabled)
            implements HasId<String> {

        /// The first line of its prompt, cut at 90 characters.
        public String title() { return firstLine(prompt, 90); }

        /// When it runs, in words, such as "Every weekday at 09:00" or "Once, tomorrow at 09:00".
        public String when(java.time.LocalDateTime now, ZoneId zone) {
            if (repeats.isPresent()) return repeats.get().describe();
            return at.map(time -> java.time.LocalDateTime.ofInstant(time, zone))
                     .map(time -> "Once, " + Dates.dayInSentence(time.toLocalDate(), now.toLocalDate()) + " at "
                                + Recurrence.clock(time.toLocalTime()))
                     .orElse("Once");
        }
    }

    /// A job's run that ended, as the lamp's history records it.
    ///
    /// @param id           such as `run-12`
    /// @param job          such as `job-3`
    /// @param ended        when it ended
    /// @param said         the genie's last message in it; empty when it said nothing
    /// @param conversation pi's id for the conversation it had; empty when it did not get that far
    public record Run(String id, String job, Instant ended, Outcome outcome, String said, Optional<String> conversation) {}

    public enum Outcome { FINISHED, FAILED, TIMED_OUT, STOPPED }

    /// The run a job has going now.
    ///
    /// @param since when it began
    public record Running(String run, String job, Instant since) {}

    public static Schedule unread(ZoneId zone) {
        return new Schedule(false, false, zone, Tuple.of(Job.class), Tuple.of(Run.class), Optional.empty(),
                            Optional.empty(), "", false, "", false);
    }

    public Schedule withPaused(boolean paused)            { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withJobs(Tuple<Job> jobs)             { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withRunning(Optional<Running> running) { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withDraft(Optional<JobDraft> draft)   { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withPicked(String picked)             { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withEarlier(boolean earlier)          { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withProblem(String problem)           { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withBusy(boolean busy)                { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }

    /// What the lamp holds now. The job being written, and the job picked if it is still there,
    /// stay as they were.
    public Schedule readAs(boolean paused, ZoneId zone, Tuple<Job> jobs, Tuple<Run> runs) {
        String stillPicked = jobs.any(job -> job.id().equals(picked)) ? picked : "";
        return new Schedule(true, paused, zone, jobs, runs, running, draft, stillPicked, earlier, problem, busy);
    }

    public Optional<Job> job(String id) {
        for (Job job : jobs) if (job.id().equals(id)) return Optional.of(job);
        return Optional.empty();
    }

    /// Picks `id`, or unpicks it when it is picked already.
    public Schedule pick(String id) {
        return withPicked(picked.equals(id) ? "" : id);
    }

    /// Opens the editor on a new job.
    public Schedule writeNew(java.time.LocalDateTime now) {
        return withDraft(Optional.of(JobDraft.fresh(now))).withProblem("");
    }

    /// Opens the editor on job `id`, to change it.
    public Schedule change(String id, java.time.LocalDateTime now) {
        return job(id).map(job -> withDraft(Optional.of(JobDraft.of(job, zone, now))).withProblem("")).orElse(this);
    }

    /// Closes the editor, and forgets what was written in it.
    public Schedule closeEditor() {
        return withDraft(Optional.empty()).withProblem("").withBusy(false);
    }

    static String firstLine(String text, int most) {
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() <= most ? line : line.substring(0, most - 1).stripTrailing() + "…";
    }
}
