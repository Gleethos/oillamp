package dev.oillamp;

import java.time.Instant;
import java.util.Optional;

/// The contents of `.oillamp/lamp.json`: the lamp's identity.
///
/// `schemaVersion` lets this oillamp refuse a lamp written by a newer version instead of
/// damaging it, and would let a future version upgrade an older lamp. Only version 1 exists so far.
record LampMeta(
    int schemaVersion,
    AgentId agentId,
    Instant createdAt,
    String createdBy,
    Optional<Instant> lastSessionAt
) {
    /// The layout this build writes and fully understands.
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public LampMeta {
        if (schemaVersion < 1)
            throw new IllegalArgumentException("Schema version must be positive: " + schemaVersion);
        if (createdBy.isBlank())
            throw new IllegalArgumentException("A lamp records which oillamp created it");
    }

    public static LampMeta createdNow(AgentId id, Instant now, String version) {
        return new LampMeta(CURRENT_SCHEMA_VERSION, id, now, "oillamp " + version, Optional.empty());
    }

    public LampMeta usedAt(Instant when) {
        return new LampMeta(schemaVersion, agentId, createdAt, createdBy, Optional.of(when));
    }
}
