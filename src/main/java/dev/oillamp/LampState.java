package dev.oillamp;

import java.nio.file.Path;

import sprouts.Tuple;

/// What oillamp found at the path given as a lamp. Decided by [LampDirectoryUtil].
sealed interface LampState {

    /// Nothing there yet. oillamp will create it, including missing parent directories.
    record Missing(Path root) implements LampState {}

    /// The directory exists and holds nothing that matters. It can become a lamp.
    record Empty(Path root) implements LampState {}

    /// Someone else's files. Refused with `OIL-LAMP-002` unless `--init` is given.
    record Foreign(Path root, Tuple<String> sampleEntries) implements LampState {}

    /// A lamp oillamp has seen before.
    record Existing(Path root, LampMeta meta) implements LampState {}

    /// It looks like a lamp, but its identity file could not be read or understood.
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
