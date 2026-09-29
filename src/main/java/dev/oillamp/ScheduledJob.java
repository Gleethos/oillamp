package dev.oillamp;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import dev.lamp.LampEvent;
import dev.lamp.LampEvent.JobAuthor;

/// One job on a lamp's schedule: a prompt, and when it wakes the agent with it.
///
/// @param id      its name, such as `job-3`; never used twice on one lamp
/// @param when    once at a set time, or again and again
/// @param prompt  what the agent is asked
/// @param author  who added it. The agent may only change or remove its own jobs
/// @param created when it was added
/// @param expires when it is taken off the schedule; empty for a user's job that stays until removed.
///                An agent's job always has one
/// @param enabled false for a job the user switched off
/// @param lastRun when it last ran. A repeating job runs at the first time its expression names
///                after this, or after `created` if it never ran. So a job that came due while no
///                session ran runs once as soon as one does, not once for every time it missed
record ScheduledJob(String id, When when, String prompt, JobAuthor author, Instant created,
                    Optional<Instant> expires, boolean enabled, Optional<Instant> lastRun) {

    /// The longest prompt a job may have. A prompt is an instruction, not a document.
    static final int MAX_PROMPT = 4000;

    ScheduledJob {
        if (!id.matches("job-[0-9]{1,9}")) throw new IllegalArgumentException("not a job id: " + id);
        if (prompt.isBlank()) throw new IllegalArgumentException("a job needs a prompt");
        if (prompt.length() > MAX_PROMPT) throw new IllegalArgumentException("a job's prompt is too long");
    }

    /// When a job runs.
    sealed interface When {
        /// Once, at this moment, and then it is taken off the schedule.
        record Once(Instant at) implements When {}
        /// Whenever the expression says.
        record Repeating(CronExpression cron) implements When {}
    }

    /// When it runs next, whether or not that time has passed. Empty when it will never run again.
    Optional<Instant> next(ZoneId zone) {
        return switch (when) {
            case When.Once once -> lastRun.isEmpty() ? Optional.of(once.at()) : Optional.empty();
            case When.Repeating repeating -> repeating.cron().nextAfter(lastRun.orElse(created), zone);
        };
    }

    boolean expiredAt(Instant now) {
        return expires.filter(end -> !end.isAfter(now)).isPresent();
    }

    /// Whether it should run now: switched on, not expired, and its time has come.
    boolean dueAt(Instant now, ZoneId zone) {
        return enabled && !expiredAt(now) && next(zone).filter(at -> !at.isAfter(now)).isPresent();
    }

    ScheduledJob ranAt(Instant at) {
        return new ScheduledJob(id, when, prompt, author, created, expires, enabled, Optional.of(at));
    }

    /// Switched on or off. Switching a repeating job on counts from now, so it does not run at once
    /// for every time it was off.
    ScheduledJob enabled(boolean on, Instant now) {
        return new ScheduledJob(id, when, prompt, author, created, expires, on,
                on && !enabled && when instanceof When.Repeating ? Optional.of(now) : lastRun);
    }

    /// How it is written: the cron expression, or `once at` and the time on this machine's clock.
    String describeWhen(ZoneId zone) {
        return switch (when) {
            case When.Once once -> "once at " + Moments.show(once.at(), zone);
            case When.Repeating repeating -> repeating.cron().text();
        };
    }

    /// How it is shown to the user, to an application, and to the agent.
    LampEvent.Job describe(ZoneId zone) {
        return new LampEvent.Job(id, describeWhen(zone), enabled ? next(zone) : Optional.empty(), prompt,
                author, created, expires, enabled);
    }
}
