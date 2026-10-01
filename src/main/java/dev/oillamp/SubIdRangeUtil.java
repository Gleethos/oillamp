package dev.oillamp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sprouts.Tuple;

/// Picks a subordinate id range for the user that overlaps nobody else's.
///
/// An overlap would give two users the same host ids, so one user's containers could write files
/// as the other, and nobody would notice for a long time. That is why this is a pure function with
/// its own tests for the awkward cases: ranges that touch, ranges out of order, a range that ends
/// exactly where the new one would start.
final class SubIdRangeUtil {

    private SubIdRangeUtil() {}

    /// Below this, ranges would collide with real system and login users.
    public static final int FLOOR = 100_000;

    /// The first free block of `size` ids at or above [#FLOOR], aligned to `size`
    /// so that allocations stay tidy and human-readable in `/etc/subuid`.
    public static IdRange allocate(Tuple<IdRange> existing, int size) {
        if (size <= 0) throw new IllegalArgumentException("Range size must be positive: " + size);
        List<IdRange> sorted = new ArrayList<>();
        for (IdRange range : existing) sorted.add(range);
        Collections.sort(sorted);

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
