package dev.oillamp;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/// Identifies one session by its UTC start time, as `yyyyMMdd-HHmmss`, for example
/// `20260923-085055`.
///
/// A timestamp is unique per lamp because only one session can run on a lamp at a time, and it
/// sorts in time order, which suits recordings and log files.
record SessionId(String value) {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public SessionId {
        if (!value.matches("\\d{8}-\\d{6}"))
            throw new IllegalArgumentException("A session id looks like yyyyMMdd-HHmmss, not: '" + value + "'");
    }

    public static SessionId at(Instant start) {
        return new SessionId(FORMAT.format(start));
    }

    /// Reads a session id from a name such as a recording's file name, or returns empty if the
    /// name is not a session id.
    public static java.util.Optional<SessionId> parse(String name) {
        return name.matches("\\d{8}-\\d{6}")
                ? java.util.Optional.of(new SessionId(name))
                : java.util.Optional.empty();
    }

    /// When this session started, which is what its id encodes.
    public Instant startedAt() {
        return java.time.LocalDateTime.parse(value, FORMAT).toInstant(ZoneOffset.UTC);
    }

    @Override public String toString() { return value; }
}
