package dev.oillamp;

import sprouts.Tuple;

/**
 * What the shell saw when it looked at a candidate lamp directory.
 *
 * <p>Passing the listing as a value — rather than letting the classifier read the disk — is what
 * lets every awkward case ("exists but unreadable", "has a stray .DS_Store", "has a lamp.json
 * from the future") be a unit test.
 *
 * @param entries at most a handful of names, enough to show the user what is in the way
 *
 * <p>Deliberately <b>package-private</b>: an intermediate result on the way to classifying a
 * directory.
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
