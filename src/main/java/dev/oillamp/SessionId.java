package dev.oillamp;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One run of {@code oillamp at}, identified by its UTC start time — spec §10.3.
 *
 * <p>A timestamp is enough to be unique per lamp because sessions never overlap (FR-04), and it
 * sorts chronologically, which is what the recordings and log listings want.
 *
 * <p>Deliberately <b>package-private</b>: names one run. Derived from the clock, which is why it
 * comes through the seam.
 */
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

    /**
     * The id in this name, when the name is one — spec §16.
     *
     * <p>Recordings are named after their session, so this is how a file on disk says when it
     * began. Anything else in that directory is somebody's own file, and gets an empty answer
     * rather than a guess.
     */
    public static java.util.Optional<SessionId> parse(String name) {
        return name.matches("\\d{8}-\\d{6}")
                ? java.util.Optional.of(new SessionId(name))
                : java.util.Optional.empty();
    }

    /** When this session started, which is what its id encodes. */
    public Instant startedAt() {
        return java.time.LocalDateTime.parse(value, FORMAT).toInstant(ZoneOffset.UTC);
    }

    @Override public String toString() { return value; }
}
