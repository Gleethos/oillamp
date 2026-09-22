package dev.oillamp;

import sprouts.Tuple;

/**
 * Picks a subordinate id range that does not collide with anyone else's — spec §11.2.
 *
 * <p>A pure function on purpose. Getting this wrong means two users share host uids, which
 * silently lets one user's container write files as another user; and it is only discovered
 * much later. Being pure means every awkward case (ranges touching, ranges out of order,
 * a range that ends exactly where ours would start) is a unit test rather than a field report.
 */
final class SubIdAllocator {

    private SubIdAllocator() {}

    /** Below this, ranges would collide with real system and login users. */
    public static final int FLOOR = 100_000;

    /**
     * The first free block of {@code size} ids at or above {@link #FLOOR}, aligned to {@code size}
     * so that allocations stay tidy and human-readable in {@code /etc/subuid}.
     */
    public static IdRange allocate(Tuple<IdRange> existing, int size) {
        if (size <= 0) throw new IllegalArgumentException("Range size must be positive: " + size);
        java.util.List<IdRange> sorted = new java.util.ArrayList<>();
        for (IdRange range : existing) sorted.add(range);
        java.util.Collections.sort(sorted);

        int candidate = FLOOR;
        for (IdRange taken : sorted) {
            if (taken.endExclusive() <= candidate) continue;          // entirely below us
            if (taken.start() >= candidate + size) break;             // gap is big enough
            candidate = alignUp(taken.endExclusive(), size);          // jump past it
        }
        return new IdRange(candidate, size);
    }

    private static int alignUp(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
    }
}
