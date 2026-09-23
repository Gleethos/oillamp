package dev.oillamp;

import java.nio.file.Path;

import sprouts.ValueSet;

/**
 * The host user oillamp runs as.
 *
 * <p>This user becomes container uid 1000, the agent user. That is why files the agent creates in
 * its home belong to this user on the host. Its groups decide whether the GPU can be used: the
 * user must be in the group that owns the render device.
 */
record UserInfo(String name, int uid, int gid, Path home, ValueSet<String> groups) {

    public UserInfo {
        if (name.isBlank()) throw new IllegalArgumentException("A user has a name");
        if (uid < 0)        throw new IllegalArgumentException("Negative uid: " + uid);
    }

    public boolean isRoot() { return uid == 0; }
    public boolean isInGroup(String group) { return groups.contains(group); }
}
