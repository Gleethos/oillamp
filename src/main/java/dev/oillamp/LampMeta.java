package dev.oillamp;

import java.time.Instant;
import java.util.Optional;

/**
 * The contents of {@code .oillamp/lamp.json} — a lamp's identity card, spec §10.1.
 *
 * <p>{@code schemaVersion} is what lets a future oillamp migrate an old lamp instead of
 * misreading it, and lets this one refuse a lamp from the future rather than damaging it.
 *
 * <p>Deliberately <b>package-private</b>: the persisted {@code meta.json}. Its on-disk form is
 * versioned and migrated (§10.4); the Java record is not the thing being promised.
 */
record LampMeta(
    int schemaVersion,
    AgentId agentId,
    Instant createdAt,
    String createdBy,
    Optional<Instant> lastSessionAt
) {
    /** The layout this build writes and fully understands. */
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
