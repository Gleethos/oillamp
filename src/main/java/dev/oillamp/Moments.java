package dev.oillamp;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Reads and writes moments the way a person, or an agent, writes them: `2026-10-01 09:00` on
/// this machine's clock, `2026-10-01T07:00Z` with a zone, or `in 2h` from now.
final class Moments {

    private Moments() {}

    private static final Pattern RELATIVE =
            Pattern.compile("(?:in\\s+|\\+)?(\\d{1,6})\\s*(m|min|mins|minutes?|h|hours?|d|days?|w|weeks?)");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /// Reads a moment.
    ///
    /// @return the moment, or empty when the text is none of the accepted forms
    static Optional<Instant> parse(String written, Instant now, ZoneId zone) {
        String text = written.strip().toLowerCase(Locale.ROOT);
        Matcher relative = RELATIVE.matcher(text);
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(1));
            Duration unit = switch (relative.group(2).charAt(0)) {
                case 'm' -> Duration.ofMinutes(1);
                case 'h' -> Duration.ofHours(1);
                case 'd' -> Duration.ofDays(1);
                default  -> Duration.ofDays(7);
            };
            return Optional.of(now.plus(unit.multipliedBy(amount)));
        }
        String iso = written.strip().replace(' ', 'T');
        try {
            return Optional.of(OffsetDateTime.parse(iso).toInstant());
        } catch (DateTimeException withoutZone) {
            // Tried next without a zone, then as a date.
        }
        try {
            return Optional.of(Instant.parse(iso));
        } catch (DateTimeException notAnInstant) {
            // Next.
        }
        try {
            return Optional.of(LocalDateTime.parse(iso).atZone(zone).toInstant());
        } catch (DateTimeException notLocal) {
            // Next.
        }
        try {
            return Optional.of(LocalDate.parse(iso).atStartOfDay(zone).toInstant());
        } catch (DateTimeException notADate) {
            return Optional.empty();
        }
    }

    /// The forms [#parse] reads, for a message saying a time could not be read.
    static final String FORMS = "a time such as \"2026-10-01 09:00\" (on this machine's clock), "
            + "\"2026-10-01T07:00Z\", or \"in 2h\" (m, h, d or w)";

    /// A moment on this machine's clock, to the minute, such as `2026-10-01 09:00`.
    static String show(Instant at, ZoneId zone) {
        return SHOWN.format(at.atZone(zone));
    }
}
