package dev.oillamp;

import java.nio.file.Path;

import sprouts.Tuple;

/**
 * What oillamp found at the requested lamp path — spec §24.1.
 *
 * <p>The shell reads the directory; this type records what was there; a pure function then
 * decides what to do about it. Splitting it this way is what lets "you pointed me at your
 * Documents folder" be tested without a filesystem.
 *
 * <p>Deliberately <b>package-private</b>: the lifecycle state of §10. An internal vocabulary.
 */
sealed interface LampState {

    /** Nothing there yet — oillamp will create it, parents included (FR-02). */
    record Missing(Path root) implements LampState {}

    /** The directory exists but holds nothing — safe to initialise (FR-02). */
    record Empty(Path root) implements LampState {}

    /** Someone else's files. Refused unless {@code --init} says otherwise (FR-02, OIL-LAMP-002). */
    record Foreign(Path root, Tuple<String> sampleEntries) implements LampState {}

    /** A lamp oillamp has seen before. */
    record Existing(Path root, LampMeta meta) implements LampState {}

    /** It looks like a lamp, but its identity file could not be read or understood. */
    record Unreadable(Path root, String reason) implements LampState {}

    default Path root() {
        return switch (this) {
            case Missing m    -> m.root();
            case Empty e      -> e.root();
            case Foreign f    -> f.root();
            case Existing x   -> x.root();
            case Unreadable u -> u.root();
        };
    }
}
