package dev.gui.model;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// How often a job repeats: one of the shapes the schedule page offers, or any other cron
/// expression, kept as written.
public sealed interface Recurrence {

    /// Every hour, at `minute` past.
    record Hourly(int minute) implements Recurrence {
        public Hourly {
            if (minute < 0 || minute > 59) throw new IllegalArgumentException("a minute is between 0 and 59");
        }
    }

    record Daily(LocalTime at) implements Recurrence {}

    /// Monday to Friday.
    record Weekdays(LocalTime at) implements Recurrence {}

    /// On some days of the week.
    ///
    /// @param days Monday first, each once; never all seven, nor Monday to Friday alone, which
    ///             are [Daily] and [Weekdays]
    record Weekly(Tuple<DayOfWeek> days, LocalTime at) implements Recurrence {}

    /// Anything else, such as `0 9 1 * *` for nine on the first of each month.
    record Custom(String cron) implements Recurrence {}

    /// The cron expression oillamp is given: minute, hour, day of month, month, day of week.
    default String cron() {
        return switch (this) {
            case Hourly hourly     -> hourly.minute() + " * * * *";
            case Daily daily       -> daily.at().getMinute() + " " + daily.at().getHour() + " * * *";
            case Weekdays weekdays -> weekdays.at().getMinute() + " " + weekdays.at().getHour() + " * * 1-5";
            case Weekly weekly     -> weekly.at().getMinute() + " " + weekly.at().getHour() + " * * "
                    + String.join(",", weekly.days().mapTo(String.class, day -> String.valueOf(day.getValue() % 7)));
            case Custom custom     -> custom.cron().strip();
        };
    }

    /// In words, such as "Every weekday at 09:00".
    default String describe() {
        return switch (this) {
            case Hourly hourly     -> hourly.minute() == 0 ? "Every hour, on the hour" : "Every hour at " + twoDigits(hourly.minute()) + " past";
            case Daily daily       -> "Every day at " + clock(daily.at());
            case Weekdays weekdays -> "Every weekday at " + clock(weekdays.at());
            case Weekly weekly     -> "Every " + days(weekly.days()) + " at " + clock(weekly.at());
            case Custom custom     -> describeCustom(custom.cron().strip());
        };
    }

    /// The first time after `after` it names, for the shapes the page offers; empty for a
    /// [Custom] expression, which only oillamp reads.
    default Optional<LocalDateTime> nextAfter(LocalDateTime after) {
        LocalDateTime from = after.withSecond(0).withNano(0).plusMinutes(1);
        return switch (this) {
            case Hourly hourly -> {
                LocalDateTime candidate = from.withMinute(hourly.minute());
                yield Optional.of(candidate.isBefore(from) ? candidate.plusHours(1) : candidate);
            }
            case Daily daily       -> firstDayFrom(from, daily.at(), Tuple.of(DayOfWeek.class, DayOfWeek.values()));
            case Weekdays weekdays -> firstDayFrom(from, weekdays.at(), WORKING_DAYS);
            case Weekly weekly     -> firstDayFrom(from, weekly.at(), weekly.days());
            case Custom ignored    -> Optional.empty();
        };
    }

    /// Reads a cron expression into the shape it has, or keeps it as [Custom].
    static Recurrence of(String cron) {
        String text = cron.strip().toLowerCase(Locale.ROOT);
        Optional<Recurrence> named = switch (text) {
            case "@hourly"             -> Optional.of(new Hourly(0));
            case "@daily", "@midnight" -> Optional.of(new Daily(LocalTime.MIDNIGHT));
            case "@weekly"             -> Optional.of(weekly(Tuple.of(DayOfWeek.class, DayOfWeek.SUNDAY), LocalTime.MIDNIGHT));
            default                    -> Optional.empty();
        };
        if (named.isPresent()) return named.get();
        Matcher fields = FIVE_FIELDS.matcher(text);
        if (!fields.matches()) return new Custom(cron.strip());
        Optional<Integer> minute = number(fields.group(1), 59);
        if (minute.isEmpty() || !fields.group(3).equals("*") || !fields.group(4).equals("*")) return new Custom(cron.strip());
        if (fields.group(2).equals("*"))
            return fields.group(5).equals("*") ? new Hourly(minute.get()) : new Custom(cron.strip());
        Optional<Integer> hour = number(fields.group(2), 23);
        if (hour.isEmpty()) return new Custom(cron.strip());
        LocalTime at = LocalTime.of(hour.get(), minute.get());
        if (fields.group(5).equals("*")) return new Daily(at);
        Optional<Tuple<DayOfWeek>> days = weekdays(fields.group(5));
        return days.isEmpty() ? new Custom(cron.strip()) : weekly(days.get(), at);
    }

    /// The same days as [Weekly], or as [Daily] or [Weekdays] when they are those.
    static Recurrence weekly(Tuple<DayOfWeek> days, LocalTime at) {
        Tuple<DayOfWeek> sorted = Tuple.of(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) if (days.contains(day)) sorted = sorted.add(day);
        if (sorted.size() == 7) return new Daily(at);
        if (sorted.equals(WORKING_DAYS)) return new Weekdays(at);
        return new Weekly(sorted, at);
    }

    Tuple<DayOfWeek> WORKING_DAYS = Tuple.of(DayOfWeek.class,
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    Pattern FIVE_FIELDS = Pattern.compile("(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)");

    /// A time as the page shows it: `09:00`.
    static String clock(LocalTime at) {
        return twoDigits(at.getHour()) + ":" + twoDigits(at.getMinute());
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    /// "Monday", "Monday and Thursday", "Monday, Wednesday and Friday".
    private static String days(Tuple<DayOfWeek> days) {
        Tuple<String> names = days.mapTo(String.class, day -> day.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        if (names.size() == 1) return names.first();
        return String.join(", ", names.removeLast().toList()) + " and " + names.last();
    }

    private static Optional<LocalDateTime> firstDayFrom(LocalDateTime from, LocalTime at, Tuple<DayOfWeek> days) {
        for (int ahead = 0; ahead <= 7; ahead++) {
            LocalDateTime candidate = from.toLocalDate().plusDays(ahead).atTime(at);
            if (!candidate.isBefore(from) && days.contains(candidate.getDayOfWeek())) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Optional<Integer> number(String field, int most) {
        if (!field.matches("\\d{1,2}")) return Optional.empty();
        int value = Integer.parseInt(field);
        return value <= most ? Optional.of(value) : Optional.empty();
    }

    /// A day-of-week field made only of numbers, ranges of them and three-letter names, such as
    /// `1-5`, `mon,thu` or `0`.
    private static Optional<Tuple<DayOfWeek>> weekdays(String field) {
        Tuple<DayOfWeek> days = Tuple.of(DayOfWeek.class);
        for (String part : field.split(",", -1)) {
            String[] range = part.split("-", -1);
            if (range.length > 2) return Optional.empty();
            Optional<Integer> first = dayNumber(range[0]);
            Optional<Integer> last = range.length == 2 ? dayNumber(range[1]) : first;
            if (first.isEmpty() || last.isEmpty() || last.get() < first.get()) return Optional.empty();
            for (int number = first.get(); number <= last.get(); number++) {
                DayOfWeek day = number % 7 == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(number % 7);
                if (!days.contains(day)) days = days.add(day);
            }
        }
        return days.isEmpty() ? Optional.empty() : Optional.of(days);
    }

    private static Optional<Integer> dayNumber(String text) {
        int named = Tuple.of(String.class, "sun", "mon", "tue", "wed", "thu", "fri", "sat").toList().indexOf(text);
        if (named >= 0) return Optional.of(named);
        return text.matches("[0-7]") ? Optional.of(Integer.parseInt(text)) : Optional.empty();
    }

    private static String describeCustom(String cron) {
        Matcher every = Pattern.compile("\\*/(\\d+) \\* \\* \\* \\*").matcher(cron);
        if (every.matches()) return "Every " + every.group(1) + " minutes";
        Matcher hours = Pattern.compile("(\\d{1,2}) \\*/(\\d+) \\* \\* \\*").matcher(cron);
        if (hours.matches()) return "Every " + hours.group(2) + " hours, "
                + (Integer.parseInt(hours.group(1)) == 0 ? "on the hour" : "at " + twoDigits(Integer.parseInt(hours.group(1))) + " past");
        return "On the cron schedule “" + cron + "”";
    }
}
