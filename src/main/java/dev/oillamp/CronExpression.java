package dev.oillamp;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.Locale;
import java.util.Optional;

/// When a repeating job runs, written the way cron writes it: five fields, for the minute, the
/// hour, the day of the month, the month and the day of the week.
///
/// ```
/// */30 * * * *     every half hour
/// 0 9 * * 1-5      at nine on weekdays
/// 0 18 1 * *       at six in the evening on the first of each month
/// ```
///
/// Each field is `*`, a number, a range such as `1-5`, a list such as `1,15`, or any of these
/// with a step such as `*/15` or `8-18/2`. Months and days may be written by name (`jan`, `mon`),
/// and Sunday is both 0 and 7. `@hourly`, `@daily` and `@weekly` are short for the obvious
/// expressions. As in cron, when both the day of the month and the day of the week are
/// restricted, a day matching either one counts. A field that starts with `*`, such as `*/2`,
/// is not restricted in this sense.
///
/// Times are read in the host's time zone, because that is the clock the person who wrote the
/// expression was looking at.
///
/// A class rather than a record because its fields are `BitSet`s, which can be changed; this
/// way nothing outside can reach them.
final class CronExpression {

    private static final String[] MONTHS =
            {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String[] WEEKDAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    /// How far ahead [#nextAfter] looks. An expression such as `0 0 30 2 *` (the 30th of
    /// February) never matches, and the search must end somewhere.
    private static final int YEARS_AHEAD = 5;

    private final String text;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet days;
    private final BitSet months;
    private final BitSet weekdays;
    private final boolean anyDay;
    private final boolean anyWeekday;

    private CronExpression(String text, BitSet minutes, BitSet hours, BitSet days, BitSet months,
                           BitSet weekdays, boolean anyDay, boolean anyWeekday) {
        this.text = text;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
        this.anyDay = anyDay;
        this.anyWeekday = anyWeekday;
    }

    /// The expression as it was written, to show it back unchanged.
    String text() { return text; }

    /// Reads an expression.
    ///
    /// @return the expression, or a sentence saying what is wrong with it
    static Result<CronExpression> parse(String written) {
        String text = written.strip().replaceAll("\\s+", " ");
        String expanded = switch (text.toLowerCase(Locale.ROOT)) {
            case "@hourly" -> "0 * * * *";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@weekly" -> "0 0 * * 0";
            default -> text;
        };
        String[] fields = expanded.split(" ", -1);
        if (fields.length != 5)
            return failed(written, "a cron expression has five fields (minute, hour, day of month, "
                                 + "month, day of week), such as \"0 9 * * 1-5\"; this one has " + fields.length);
        try {
            BitSet minutes = field(fields[0], "minute", 0, 59, new String[0], 0);
            BitSet hours = field(fields[1], "hour", 0, 23, new String[0], 0);
            BitSet days = field(fields[2], "day of the month", 1, 31, new String[0], 0);
            BitSet months = field(fields[3], "month", 1, 12, MONTHS, 1);
            BitSet weekdays = field(fields[4], "day of the week", 0, 7, WEEKDAYS, 0);
            // Sunday is both 0 and 7.
            if (weekdays.get(7)) weekdays.set(0);
            weekdays.clear(7);
            return Result.ok(new CronExpression(text, minutes, hours, days, months, weekdays,
                    fields[2].startsWith("*"), fields[4].startsWith("*")));
        } catch (IllegalArgumentException wrong) {
            return failed(written, String.valueOf(wrong.getMessage()));
        }
    }

    private static Result<CronExpression> failed(String written, String why) {
        return Result.err(ProblemCatalogUtil.scheduleRefused("\"" + written + "\" is not a schedule oillamp can read: " + why));
    }

    /// One field: a comma-separated list of `*`, `n` or `a-b`, each with an optional `/step`.
    private static BitSet field(String text, String name, int min, int max, String[] names, int firstName) {
        BitSet values = new BitSet(max + 1);
        for (String part : text.split(",", -1)) {
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), name, 1, max - min + 1, new String[0], 0);
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max;
            } else if (range.indexOf('-') > 0) {
                int dash = range.indexOf('-');
                from = number(range.substring(0, dash), name, min, max, names, firstName);
                to = number(range.substring(dash + 1), name, min, max, names, firstName);
                if (to < from)
                    throw new IllegalArgumentException("the " + name + " range " + range + " runs backwards");
            } else {
                from = number(range, name, min, max, names, firstName);
                // `5/15` means from 5 to the end, every 15, as in cron.
                to = slash >= 0 ? max : from;
            }
            for (int value = from; value <= to; value += step) values.set(value);
        }
        return values;
    }

    private static int number(String text, String name, int min, int max, String[] names, int firstName) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.length; i++)
            if (names[i].equals(lower)) return i + firstName;
        try {
            int value = Integer.parseInt(text);
            if (value < min || value > max)
                throw new IllegalArgumentException("the " + name + " " + value + " is not between "
                                                   + min + " and " + max);
            return value;
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("'" + text + "' is not a " + name);
        }
    }

    /// The first moment after `after` that this expression names, or empty when there is none
    /// in the next few years.
    Optional<Instant> nextAfter(Instant after, ZoneId zone) {
        LocalDateTime start = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate day = start.toLocalDate();
        LocalDate last = day.plusYears(YEARS_AHEAD);
        LocalTime from = start.toLocalTime();
        while (!day.isAfter(last)) {
            if (!months.get(day.getMonthValue())) {
                day = day.withDayOfMonth(1).plusMonths(1);
                from = LocalTime.MIDNIGHT;
                continue;
            }
            if (matchesDay(day)) {
                Optional<LocalTime> time = firstTimeFrom(from);
                if (time.isPresent()) {
                    // A time that a clock change skips, such as 02:30 on the night the clocks go
                    // forward, happens at the time the clocks jump to.
                    Instant found = ZonedDateTime.of(day, time.get(), zone).toInstant();
                    if (found.isAfter(after)) return Optional.of(found);
                    from = time.get().plusMinutes(1);
                    if (from.equals(LocalTime.MIDNIGHT)) {
                        day = day.plusDays(1);
                    }
                    continue;
                }
            }
            day = day.plusDays(1);
            from = LocalTime.MIDNIGHT;
        }
        return Optional.empty();
    }

    private boolean matchesDay(LocalDate day) {
        boolean dayOfMonth = days.get(day.getDayOfMonth());
        boolean dayOfWeek = weekdays.get(day.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : day.getDayOfWeek().getValue());
        return (anyDay || anyWeekday) ? (dayOfMonth && dayOfWeek) : (dayOfMonth || dayOfWeek);
    }

    private Optional<LocalTime> firstTimeFrom(LocalTime from) {
        for (int hour = hours.nextSetBit(from.getHour()); hour >= 0; hour = hours.nextSetBit(hour + 1)) {
            int minute = minutes.nextSetBit(hour == from.getHour() ? from.getMinute() : 0);
            if (minute >= 0) return Optional.of(LocalTime.of(hour, minute));
        }
        return Optional.empty();
    }

    /// The shortest time between two runs that both come before `until`, or empty when it runs
    /// fewer than twice before then.
    ///
    /// The whole time until `until` is searched, because an expression such as `*/5 0 1 * *`
    /// runs every five minutes, but only on the first of the month.
    Optional<Duration> shortestGap(Instant from, Instant until, ZoneId zone) {
        Optional<Duration> shortest = Optional.empty();
        Optional<Instant> previous = nextAfter(from, zone);
        // A run every two minutes for a year is 263,000 runs; a gap of a minute ends the search.
        for (int runs = 0; previous.isPresent() && previous.get().isBefore(until) && runs < 600_000; runs++) {
            Optional<Instant> next = nextAfter(previous.get(), zone);
            if (next.isEmpty() || !next.get().isBefore(until)) break;
            Duration gap = Duration.between(previous.get(), next.get());
            if (shortest.isEmpty() || gap.compareTo(shortest.get()) < 0) shortest = Optional.of(gap);
            if (gap.toMinutes() <= 1) break;
            previous = next;
        }
        return shortest;
    }

    @Override public boolean equals(Object other) {
        return other instanceof CronExpression cron && cron.text.equals(text);
    }

    @Override public int hashCode() { return text.hashCode(); }

    @Override public String toString() { return text; }
}
