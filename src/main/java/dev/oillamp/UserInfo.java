package dev.oillamp;

import java.nio.file.Path;

import sprouts.ValueSet;

/**
 * The host user oillamp runs as.
 *
 * <p>This user is mapped onto container uid 1000 (D-14), which is why files the agent creates in
 * its home show up on the host owned by this user, and why the GPU render node's group membership
 * decides whether hardware rendering is possible at all (§14.3).
 *
 * <p>Deliberately <b>package-private</b>: who is running oillamp, as probed.
 */
record UserInfo(String name, int uid, int gid, Path home, ValueSet<String> groups) {

    public UserInfo {
        if (name.isBlank()) throw new IllegalArgumentException("A user has a name");
        if (uid < 0)        throw new IllegalArgumentException("Negative uid: " + uid);
    }

    public boolean isRoot() { return uid == 0; }
    public boolean isInGroup(String group) { return groups.contains(group); }
}
