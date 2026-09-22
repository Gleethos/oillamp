package dev.oillamp;

import java.util.Optional;

/** A port or an inclusive range of ports in a network rule — spec §18.3. */
record PortRange(int from, int to) {

    public PortRange {
        if (from < 1 || from > 65535) throw new IllegalArgumentException("Not a TCP port: " + from);
        if (to < from || to > 65535)  throw new IllegalArgumentException("Not a port range: " + from + "-" + to);
    }

    public static PortRange single(int port) { return new PortRange(port, port); }

    /** Parses {@code 443} or {@code "8000-8100"} — TOML allows both a number and a string here. */
    public static Optional<PortRange> parse(String text) {
        String value = text.trim();
        try {
            int dash = value.indexOf('-', 1);
            if (dash < 0) return Optional.of(single(Integer.parseInt(value)));
            return Optional.of(new PortRange(
                    Integer.parseInt(value.substring(0, dash).trim()),
                    Integer.parseInt(value.substring(dash + 1).trim())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean contains(int port) { return port >= from && port <= to; }

    @Override public String toString() { return from == to ? Integer.toString(from) : from + "-" + to; }
}
