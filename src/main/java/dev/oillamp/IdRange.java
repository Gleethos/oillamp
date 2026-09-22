package dev.oillamp;

/** A half-open range of subordinate user or group ids, as written in {@code /etc/subuid}. */
record IdRange(int start, int count) implements Comparable<IdRange> {

    public IdRange {
        if (start < 0)  throw new IllegalArgumentException("Negative id range start: " + start);
        if (count <= 0) throw new IllegalArgumentException("Empty id range: " + count);
    }

    public int endExclusive() { return start + count; }

    public boolean overlaps(IdRange other) {
        return start < other.endExclusive() && other.start < endExclusive();
    }

    /** The {@code usermod --add-subuids} form. */
    public String asUsermodArgument() { return start + "-" + (endExclusive() - 1); }

    @Override public int compareTo(IdRange other) { return Integer.compare(start, other.start); }

    @Override public String toString() { return start + ":" + count; }
}
