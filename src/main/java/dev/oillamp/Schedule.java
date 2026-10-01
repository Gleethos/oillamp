package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.lamp.LampEvent.JobAuthor;

import sprouts.Tuple;

/// A lamp's schedule: its jobs, and the rules for changing them.
///
/// Kept in `.oillamp/schedule.json`, beside the history and out of the agent's reach. The agent
/// asks for changes through the session, and every change, whoever asks, goes through the
/// methods here. They decide; [ScheduleBook] reads and writes the file.
///
/// The user's jobs have no limits. The agent's have those in `[schedule]`: how many it may have,
/// how often one may run, and how long one may stay. The agent may not change or remove the
/// user's jobs.
///
/// @param paused  whether the user paused the whole schedule
/// @param nextJob the number the next job gets, so that no id is used twice
/// @param nextRun the number the next run gets, for the same reason
record Schedule(boolean paused, int nextJob, int nextRun, Tuple<ScheduledJob> jobs) {

    static Schedule empty() { return new Schedule(false, 1, 1, Tuple.of(ScheduledJob.class)); }

    /// A job someone asked for, as they wrote it, before the rules have judged it.
    ///
    /// @param cron    a cron expression, for a job that repeats
    /// @param at      a time, for a job that runs once; exactly one of the two is given
    /// @param expires when to take it off the schedule, or empty for no end (the agent's jobs
    ///                always get one)
    record Request(Optional<String> cron, Optional<String> at, String prompt, Optional<String> expires) {}

    /// The schedule after a change, and the job it was about.
    record Changed(Schedule schedule, ScheduledJob job) {}

    Optional<ScheduledJob> job(String id) {
        return jobs.stream().filter(job -> job.id().equals(id.strip())).findFirst();
    }

    Tuple<ScheduledJob> byAgent() { return jobs.retainIf(job -> job.author() == JobAuthor.AGENT); }

    /// Adds a job, if the rules allow it.
    Result<Changed> add(Request request, JobAuthor author, Instant now, ZoneId zone, LampConfig.Schedule limits) {
        boolean agent = author == JobAuthor.AGENT;
        String prompt = request.prompt().strip();
        if (prompt.isEmpty()) return refused("a job needs a prompt: what the agent is asked when it wakes");
        if (prompt.length() > ScheduledJob.MAX_PROMPT)
            return refused("the prompt is " + prompt.length() + " characters long; a job's prompt may have at most "
                         + ScheduledJob.MAX_PROMPT + ". Put the details in a file and point to it");
        if (request.cron().isPresent() == request.at().isPresent())
            return refused("a job either repeats, with a cron expression such as \"0 9 * * 1-5\", or runs once, "
                         + "at a time such as \"2026-10-01 09:00\"; give one of the two");
        if (agent && byAgent().size() >= limits.maxAgentJobs())
            return refused("the agent may have " + limits.maxAgentJobs() + " jobs on this lamp's schedule at "
                         + "once, and has " + byAgent().size() + "; remove one first");

        Instant longest = now.plus(limits.maxAgentLife());
        Optional<Instant> expires = Optional.empty();
        if (request.expires().isPresent()) {
            Optional<Instant> read = Moments.parse(request.expires().get(), now, zone);
            if (read.isEmpty())
                return refused("\"" + request.expires().get() + "\" is not a time oillamp can read as the end of the job; "
                             + "give " + Moments.FORMS);
            if (!read.get().isAfter(now)) return refused("the job would end at " + Moments.show(read.get(), zone)
                                                     + ", which has already passed");
            expires = read;
        }
        // The agent's jobs always end, at the latest after the configured number of days.
        if (agent) expires = Optional.of(expires.filter(end -> end.isBefore(longest)).orElse(longest));

        ScheduledJob.When when;
        if (request.at().isPresent()) {
            Optional<Instant> at = Moments.parse(request.at().get(), now, zone);
            if (at.isEmpty())
                return refused("\"" + request.at().get() + "\" is not a time oillamp can read; give " + Moments.FORMS);
            if (!at.get().isAfter(now))
                return refused(Moments.show(at.get(), zone) + " has already passed; a job runs in the future");
            if (agent && at.get().isAfter(longest))
                return refused("the agent may schedule at most " + limits.maxAgentDays() + " days ahead, until "
                             + Moments.show(longest, zone));
            when = new ScheduledJob.When.Once(at.get());
        } else {
            Result<CronExpression> read = CronExpression.parse(request.cron().get());
            if (!(read instanceof Result.Ok<CronExpression>(CronExpression cron, var _)))
                return Result.err(read.problems());
            if (cron.nextAfter(now, zone).isEmpty())
                return refused("\"" + cron.text() + "\" names no time in the next few years, such as the 30th of February");
            Optional<Duration> gap = cron.shortestGap(now, zone);
            if (agent && gap.isPresent() && gap.get().compareTo(limits.minAgentInterval()) < 0)
                return refused("\"" + cron.text() + "\" runs every " + describe(gap.get()) + " at times; the agent's "
                             + "jobs may run at most every " + limits.minAgentIntervalMinutes() + " minutes");
            when = new ScheduledJob.When.Repeating(cron);
        }

        ScheduledJob job = new ScheduledJob("job-" + nextJob, when, prompt, author, now, expires, true, Optional.empty());
        Optional<Instant> first = job.next(zone);
        if (expires.isPresent() && first.isPresent() && !first.get().isBefore(expires.get()))
            return refused("the job would end at " + Moments.show(expires.get(), zone) + ", before it first runs at "
                         + Moments.show(first.get(), zone));
        return Result.ok(new Changed(new Schedule(paused, nextJob + 1, nextRun, jobs.add(job)), job));
    }

    /// Takes a job off the schedule. The agent may only remove its own.
    Result<Changed> remove(String id, JobAuthor by, Path lamp) {
        Optional<ScheduledJob> found = job(id);
        if (found.isEmpty()) return Result.err(Problems.noSuchJob(id, lamp));
        if (by == JobAuthor.AGENT && found.get().author() == JobAuthor.USER)
            return refused(found.get().id() + " is one of the user's jobs; only the user can remove it");
        return Result.ok(new Changed(without(found.get().id()), found.get()));
    }

    /// Switches a job on or off. Only the user does this.
    Result<Changed> enable(String id, boolean on, Instant now, Path lamp) {
        Optional<ScheduledJob> found = job(id);
        if (found.isEmpty()) return Result.err(Problems.noSuchJob(id, lamp));
        ScheduledJob changed = found.get().enabled(on, now);
        return Result.ok(new Changed(replace(changed), changed));
    }

    Schedule paused(boolean pause) { return new Schedule(pause, nextJob, nextRun, jobs); }

    /// The jobs whose time has come, oldest first. None while the schedule is paused.
    Tuple<ScheduledJob> due(Instant now, ZoneId zone) {
        if (paused) return Tuple.of(ScheduledJob.class);
        return jobs.retainIf(job -> job.dueAt(now, zone))
                   .sort((a, b) -> a.next(zone).orElse(now).compareTo(b.next(zone).orElse(now)));
    }

    /// The jobs to take off the schedule now: those past their end, and those that ran once and
    /// will never run again.
    Tuple<ScheduledJob> finished(Instant now, ZoneId zone) {
        return jobs.retainIf(job -> job.expiredAt(now) || job.next(zone).isEmpty());
    }

    /// Records that a job ran, or began to.
    Schedule ran(String id, Instant at) {
        return job(id).map(job -> replace(job.ranAt(at))).orElse(this);
    }

    /// The schedule, and the name for a new run, such as `run-12`.
    record Numbered(Schedule schedule, String run) {}

    Numbered numberRun() {
        return new Numbered(new Schedule(paused, nextJob, nextRun + 1, jobs), "run-" + nextRun);
    }

    Schedule without(String id) {
        return new Schedule(paused, nextJob, nextRun, jobs.removeIf(job -> job.id().equals(id)));
    }

    private Schedule replace(ScheduledJob changed) {
        return new Schedule(paused, nextJob, nextRun,
                jobs.map(job -> job.id().equals(changed.id()) ? changed : job));
    }

    private static <T> Result<T> refused(String why) {
        return Result.err(Problems.scheduleRefused(why));
    }

    private static String describe(Duration gap) {
        long minutes = gap.toMinutes();
        return minutes == 1 ? "minute" : minutes < 120 ? minutes + " minutes" : gap.toHours() + " hours";
    }

    // ─── the file ──────────────────────────────────────────────────────────────────────────

    String render() {
        ObjectNode root = Json.object();
        root.put("paused", paused);
        root.put("next_job", nextJob);
        root.put("next_run", nextRun);
        ArrayNode list = root.putArray("jobs");
        for (ScheduledJob job : jobs) {
            ObjectNode node = list.addObject();
            node.put("id", job.id());
            switch (job.when()) {
                case ScheduledJob.When.Once once -> node.put("at", once.at().toString());
                case ScheduledJob.When.Repeating repeating -> node.put("cron", repeating.cron().text());
            }
            node.put("prompt", job.prompt());
            node.put("author", job.author().name().toLowerCase(Locale.ROOT));
            node.put("created", job.created().toString());
            job.expires().ifPresent(end -> node.put("expires", end.toString()));
            node.put("enabled", job.enabled());
            job.lastRun().ifPresent(last -> node.put("last_run", last.toString()));
        }
        return Json.readable(root) + "\n";
    }

    /// Reads the file [#render] wrote.
    static Result<Schedule> parse(String text, Path file) {
        Optional<JsonNode> parsed = Json.parse(text);
        if (parsed.isEmpty() || !parsed.get().isObject())
            return Result.err(Problems.scheduleDamaged(file, "it is not a JSON object"));
        JsonNode root = parsed.get();
        try {
            Tuple<ScheduledJob> jobs = Tuple.of(ScheduledJob.class);
            for (JsonNode node : root.path("jobs")) {
                ScheduledJob.When when;
                if (node.hasNonNull("cron")) {
                    Result<CronExpression> cron = CronExpression.parse(node.path("cron").asText());
                    if (!(cron instanceof Result.Ok<CronExpression>(CronExpression expression, var _)))
                        throw new IllegalArgumentException("the job " + node.path("id").asText() + " has an unreadable cron expression");
                    when = new ScheduledJob.When.Repeating(expression);
                } else {
                    when = new ScheduledJob.When.Once(Instant.parse(node.path("at").asText()));
                }
                jobs = jobs.add(new ScheduledJob(node.path("id").asText(), when, node.path("prompt").asText(),
                        node.path("author").asText().equals("agent") ? JobAuthor.AGENT : JobAuthor.USER,
                        Instant.parse(node.path("created").asText()),
                        optionalInstant(node, "expires"), node.path("enabled").asBoolean(true),
                        optionalInstant(node, "last_run")));
            }
            return Result.ok(new Schedule(root.path("paused").asBoolean(false),
                    Math.max(1, root.path("next_job").asInt(1)), Math.max(1, root.path("next_run").asInt(1)), jobs));
        } catch (RuntimeException unreadable) {
            return Result.err(Problems.scheduleDamaged(file, Problems.reason(unreadable)));
        }
    }

    private static Optional<Instant> optionalInstant(JsonNode node, String field) {
        return node.hasNonNull(field) ? Optional.of(Instant.parse(node.path(field).asText())) : Optional.empty();
    }
}
