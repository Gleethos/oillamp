package dev.oillamp;

import sprouts.Tuple;

/**
 * What a directory contained when oillamp looked at it. {@link Filesystem#list} produces it and
 * {@link LampClassifier} decides what it means, so the classifier can be tested without a disk.
 *
 * @param entries the names in the directory, sorted
 */
record DirListing(boolean exists, boolean readable, Tuple<String> entries) {

    public static DirListing missing() {
        return new DirListing(false, true, Tuple.of(String.class));
    }

    public static DirListing empty() {
        return new DirListing(true, true, Tuple.of(String.class));
    }

    public static DirListing of(String... entries) {
        return new DirListing(true, true, Tuple.of(String.class, entries));
    }

    public boolean isEmpty() { return entries.isEmpty(); }
}
