package dev.oillamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sprouts.Tuple;

/// Chooses which screen recordings to delete, at every session start and on
/// `oillamp recordings --prune`.
///
/// A lamp with recording enabled and used every day would otherwise fill the disk. Two
/// independent limits apply, and a recording is deleted if either says so: it is older than
/// `recording.max_age_days`, or it falls outside the newest `recording.max_total_gb`.
///
/// This only selects; it deletes nothing. The caller turns the selection into a step, which
/// deletes through `podman unshare` because the files belong to the infra user.
final class RecordingRetentionUtil {

    private RecordingRetentionUtil() {}

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    /// Returns the recordings to delete, oldest first.
    ///
    /// @return the recordings that fall outside the configured age and size budget
    public static Tuple<RecordingFile> select(Tuple<RecordingFile> existing,
                                              LampConfig.Recording policy,
                                              Instant now) {
        List<RecordingFile> newestFirst = new ArrayList<>();
        for (RecordingFile file : existing) newestFirst.add(file);
        newestFirst.sort((a, b) -> b.recordedAt().compareTo(a.recordedAt()));

        List<RecordingFile> doomed = new ArrayList<>();
        long keptBytes = 0L;
        long budget = policy.maxTotalGb() <= 0 ? Long.MAX_VALUE : policy.maxTotalGb() * BYTES_PER_GB;
        Optional<Duration> maxAge = policy.maxAgeDays() <= 0
                ? Optional.empty()
                : Optional.of(Duration.ofDays(policy.maxAgeDays()));

        for (RecordingFile file : newestFirst) {
            boolean tooOld = maxAge.isPresent()
                    && Duration.between(file.recordedAt(), now).compareTo(maxAge.get()) > 0;
            boolean overBudget = keptBytes + file.sizeBytes() > budget;
            if (tooOld || overBudget) {
                doomed.add(file);
            } else {
                keptBytes += file.sizeBytes();
            }
        }
        doomed.sort((a, b) -> a.recordedAt().compareTo(b.recordedAt()));
        return Tuple.of(RecordingFile.class, doomed);
    }
}
