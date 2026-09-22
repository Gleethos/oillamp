package dev.oillamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import sprouts.Tuple;

/**
 * Chooses which recordings to delete before a new session starts — spec §16.
 *
 * <p>Recording is on by default and a lamp is used day after day, so without retention a laptop
 * fills up quietly. Two independent limits apply, and a recording goes if <em>either</em> says so:
 * it is older than {@code max_age_days}, or it falls outside the newest {@code max_total_gb}.
 *
 * <p>Pure, and deliberately phrased as "select what to delete" rather than "delete": the caller
 * shows the list, logs it, and only then removes the files — which for these files needs
 * {@code podman unshare} anyway, since they belong to the container's infra user (§9.2).
 *
 * <p>Deliberately <b>package-private</b>: which recordings to delete. Policy that should stay
 * tunable.
 */
final class Retention {

    private Retention() {}

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    /**
     * Returns the recordings to delete, oldest first.
     *
     * @return the recordings that fall outside the configured age and size budget
     */
    public static Tuple<RecordingFile> select(Tuple<RecordingFile> existing,
                                              LampConfig.Recording policy,
                                              Instant now) {
        List<RecordingFile> newestFirst = new ArrayList<>();
        for (RecordingFile file : existing) newestFirst.add(file);
        newestFirst.sort((a, b) -> b.recordedAt().compareTo(a.recordedAt()));

        List<RecordingFile> doomed = new ArrayList<>();
        long keptBytes = 0L;
        long budget = policy.maxTotalGb() <= 0 ? Long.MAX_VALUE : policy.maxTotalGb() * BYTES_PER_GB;
        java.util.Optional<Duration> maxAge = policy.maxAgeDays() <= 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.ofDays(policy.maxAgeDays()));

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
