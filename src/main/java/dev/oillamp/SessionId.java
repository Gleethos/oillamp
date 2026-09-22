package dev.oillamp;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One run of {@code oillamp at}, identified by its UTC start time — spec §10.3.
 *
 * <p>A timestamp is enough to be unique per lamp because sessions never overlap (FR-04), and it
 * sorts chronologically, which is what the recordings and log listings want.
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

    @Override public String toString() { return value; }
}
