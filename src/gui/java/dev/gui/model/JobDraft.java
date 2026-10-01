package dev.gui.model;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// A job as it is being written on the schedule page, field by field, as the user typed and
/// picked. Nothing is checked while it is written; [#problem] says what stands in the way of
/// adding it.
///
/// @param replaces  the job it changes; empty for a new one
/// @param prompt    what the genie is asked
/// @param repeats   false for a job that runs once
/// @param day       the day a job that runs once runs on
/// @param time      the time of day, as typed, such as `9:30`: when a job that runs once runs,
///                  and when a daily, weekday or weekly one does
/// @param repeat    which way it repeats
/// @param minute    the minute past each hour an hourly job runs at, as typed
/// @param days      the days a weekly job runs on
/// @param cron      the expression of a custom one, as typed
/// @param endsOn    the last day a repeating job runs on; empty for never
/// @param month     the month the calendar shows
public record JobDraft(Optional<String> replaces, String prompt, boolean repeats, LocalDate day, String time,
                       Repeat repeat, String minute, Tuple<DayOfWeek> days, String cron,
                       Optional<LocalDate> endsOn, YearMonth month) {

    /// The ways a job can repeat, as the page offers them.
    public enum Repeat {
        HOURLY("Every hour"), DAILY("Every day"), WEEKDAYS("Weekdays"), WEEKLY("Some days"), CUSTOM("Custom");

        private final String label;

        Repeat(String label) { this.label = label; }

        public String label() { return label; }
    }

    /// The longest prompt a job may have, as oillamp allows it.
    public static final int MOST_PROMPT = 4000;

    /// A new job: once, at the next full hour but one, or tomorrow at nine late in the evening.
    public static JobDraft fresh(LocalDateTime now) {
        LocalDateTime at = now.getHour() >= 22 ? now.toLocalDate().plusDays(1).atTime(9, 0)
                         : now.withMinute(0).withSecond(0).withNano(0).plusHours(2);
        return new JobDraft(Optional.empty(), "", false, at.toLocalDate(), Recurrence.clock(at.toLocalTime()),
                            Repeat.DAILY, "0", Tuple.of(DayOfWeek.class, now.getDayOfWeek()), "0 9 * * 1-5",
                            Optional.empty(), YearMonth.from(at));
    }

    /// Job `job`, to be changed.
    public static JobDraft of(Schedule.Job job, ZoneId zone, LocalDateTime now) {
        JobDraft draft = fresh(now).withReplaces(Optional.of(job.id())).withPrompt(job.prompt());
        Optional<LocalDate> endsOn = job.expires().map(end -> end.minusSeconds(1).atZone(zone).toLocalDate());
        if (job.repeats().isEmpty()) {
            LocalDateTime at = job.at().map(time -> LocalDateTime.ofInstant(time, zone)).orElse(now.plusHours(1));
            return draft.withDay(at.toLocalDate()).withTime(Recurrence.clock(at.toLocalTime())).withMonth(YearMonth.from(at));
        }
        draft = draft.withRepeats(true).withEndsOn(endsOn);
        return switch (job.repeats().get()) {
            case Recurrence.Hourly hourly     -> draft.withRepeat(Repeat.HOURLY).withMinute(String.valueOf(hourly.minute()));
            case Recurrence.Daily daily       -> draft.withRepeat(Repeat.DAILY).withTime(Recurrence.clock(daily.at()));
            case Recurrence.Weekdays weekdays -> draft.withRepeat(Repeat.WEEKDAYS).withTime(Recurrence.clock(weekdays.at()));
            case Recurrence.Weekly weekly     -> draft.withRepeat(Repeat.WEEKLY).withTime(Recurrence.clock(weekly.at())).withDays(weekly.days());
            case Recurrence.Custom custom     -> draft.withRepeat(Repeat.CUSTOM).withCron(custom.cron());
        };
    }

    public JobDraft withReplaces(Optional<String> replaces) { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withPrompt(String prompt)       { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withRepeats(boolean repeats)   { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withDay(LocalDate day)         { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withTime(String time)          { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withRepeat(Repeat repeat)      { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withMinute(String minute)      { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withDays(Tuple<DayOfWeek> days) { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withCron(String cron)          { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withEndsOn(Optional<LocalDate> endsOn) { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }
    public JobDraft withMonth(YearMonth month)     { return new JobDraft(replaces, prompt, repeats, day, time, repeat, minute, days, cron, endsOn, month); }

    /// Adds `day` to a weekly job's days, or takes it away.
    public JobDraft toggle(DayOfWeek day) {
        return withDays(days.contains(day) ? days.remove(day) : days.add(day));
    }

    /// Picks a day on the calendar: the day a job that runs once runs, or the last day of a
    /// repeating one. Picking the last day again takes it away, so the job never ends.
    public JobDraft pick(LocalDate picked) {
        if (!repeats) return withDay(picked);
        return withEndsOn(endsOn.filter(picked::equals).isPresent() ? Optional.empty() : Optional.of(picked));
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2})(?:\\s*[:.h]\\s*(\\d{2})|(\\d{2}))?");

    /// The time of day typed, read the ways people write it: `9`, `9:30`, `09.30`, `0930`.
    public Optional<LocalTime> clock() {
        Matcher read = TIME.matcher(time.strip());
        if (!read.matches()) return Optional.empty();
        int hour = Integer.parseInt(read.group(1));
        String minutes = read.group(2) != null ? read.group(2) : read.group(3);
        int minuteOfHour = minutes == null ? 0 : Integer.parseInt(minutes);
        return hour <= 23 && minuteOfHour <= 59 ? Optional.of(LocalTime.of(hour, minuteOfHour)) : Optional.empty();
    }

    /// How it repeats, once what was typed can be read; empty for a job that runs once.
    public Optional<Recurrence> recurrence() {
        if (!repeats) return Optional.empty();
        return switch (repeat) {
            case HOURLY   -> minuteOfHour().map(Recurrence.Hourly::new);
            case DAILY    -> clock().map(Recurrence.Daily::new);
            case WEEKDAYS -> clock().map(Recurrence.Weekdays::new);
            case WEEKLY   -> days.isEmpty() ? Optional.empty() : clock().map(at -> Recurrence.weekly(days, at));
            case CUSTOM   -> cron.isBlank() ? Optional.empty() : Optional.of(new Recurrence.Custom(cron.strip()));
        };
    }

    /// When a job that runs once runs, once its time can be read.
    public Optional<LocalDateTime> once() {
        return repeats ? Optional.empty() : clock().map(day::atTime);
    }

    /// When a repeating job comes off the schedule: as its last day ends.
    public Optional<LocalDateTime> ends() {
        return repeats ? endsOn.map(last -> last.plusDays(1).atStartOfDay()) : Optional.empty();
    }

    /// What stands in the way of adding it, in a sentence; empty when nothing does. A custom
    /// expression is only checked by oillamp, as it is added.
    public Optional<String> problem(LocalDateTime now) {
        if (prompt.isBlank()) return Optional.of("Write what the genie should do.");
        if (prompt.strip().length() > MOST_PROMPT)
            return Optional.of("A task can be at most " + String.format("%,d", MOST_PROMPT) + " characters; this one has "
                               + String.format("%,d", prompt.strip().length()) + ".");
        return timingProblem(now);
    }

    /// What stands in the way of the time it runs being read, whatever the prompt.
    public Optional<String> timingProblem(LocalDateTime now) {
        if (!repeats) {
            if (clock().isEmpty()) return Optional.of(TIME_HINT);
            if (!once().orElseThrow().isAfter(now)) return Optional.of("That time has passed. Pick a later one.");
            return Optional.empty();
        }
        Optional<String> problem = switch (repeat) {
            case HOURLY -> minuteOfHour().isEmpty() ? Optional.of("The minute past the hour is a number from 0 to 59.") : Optional.empty();
            case DAILY, WEEKDAYS -> clock().isEmpty() ? Optional.of(TIME_HINT) : Optional.empty();
            case WEEKLY -> days.isEmpty() ? Optional.of("Pick at least one day.")
                         : clock().isEmpty() ? Optional.of(TIME_HINT) : Optional.empty();
            case CUSTOM -> cron.isBlank() ? Optional.of("Write a cron expression, such as 0 9 * * 1-5.") : Optional.empty();
        };
        if (problem.isPresent()) return problem;
        if (ends().filter(end -> !end.isAfter(now)).isPresent()) return Optional.of("The last day has passed.");
        return Optional.empty();
    }

    private static final String TIME_HINT = "Write the time as hours and minutes, such as 09:00.";

    /// When it will run, in a sentence, such as "Every weekday at 09:00, first tomorrow at 09:00."
    /// Empty while [#timingProblem] says what is wrong.
    public String summary(LocalDateTime now) {
        if (timingProblem(now).isPresent()) return "";
        LocalDate today = now.toLocalDate();
        if (!repeats) {
            LocalDateTime at = once().orElseThrow();
            return "Once, " + DateWordingUtil.dayInSentence(at.toLocalDate(), today) + " at " + Recurrence.clock(at.toLocalTime())
                 + " — " + DateWordingUtil.fromNow(Duration.between(now, at)) + ".";
        }
        Recurrence recurrence = recurrence().orElseThrow();
        String first = recurrence.nextAfter(now)
                .filter(next -> ends().isEmpty() || next.isBefore(ends().get()))
                .map(next -> ", first " + DateWordingUtil.dayInSentence(next.toLocalDate(), today) + " at " + Recurrence.clock(next.toLocalTime()))
                .orElse(recurrence instanceof Recurrence.Custom ? "" : ", but not before its last day");
        String last = endsOn.map(day -> ", until " + DateWordingUtil.dayInSentence(day, today).replaceFirst("^on ", "")).orElse("");
        return recurrence.describe() + first + last + "."
             + (recurrence instanceof Recurrence.Custom ? " oillamp reads the expression when you save the job." : "");
    }

    private Optional<Integer> minuteOfHour() {
        String typed = minute.strip();
        if (!typed.matches("\\d{1,2}")) return Optional.empty();
        int value = Integer.parseInt(typed);
        return value <= 59 ? Optional.of(value) : Optional.empty();
    }
}
