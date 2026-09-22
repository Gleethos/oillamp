package dev.oillamp;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sprouts.Tuple;

/**
 * Works out what is at a lamp path — spec §24.1.
 *
 * <p>The shell reads the directory and {@code lamp.json}; this decides what they mean. The
 * distinction that matters for the user is between "empty, so I will set it up", "an existing
 * lamp, so I will reuse the agent's home", and "your files, so I will not touch this" (FR-02).
 */
final class LampClassifier {

    private LampClassifier() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Entries that do not make a directory "someone else's".
     *
     * <p>Two groups: droppings left by editors and file managers, and the lamp's own
     * user-facing files. Writing {@code oillamp.toml} before the first run — configuring the
     * sandbox and then starting it — is a perfectly ordinary thing to do, and being refused
     * for it would be baffling.
     */
    private static final Tuple<String> IGNORED_ENTRIES = Tuple.of(String.class,
            ".DS_Store", ".directory", "Thumbs.db", ".keep", ".gitkeep",
            "oillamp.toml", "README.txt");

    /**
     * Classifies what is at the lamp path.
     *
     * @param lampJson the contents of {@code .oillamp/lamp.json}, if the shell found and read it
     */
    public static LampState classify(Path root, DirListing listing, Optional<String> lampJson) {
        if (!listing.exists())  return new LampState.Missing(root);
        if (!listing.readable()) return new LampState.Unreadable(root, "the directory cannot be read");

        if (lampJson.isPresent()) {
            try {
                return new LampState.Existing(root, parse(lampJson.get()));
            } catch (JacksonException | IllegalArgumentException | DateTimeParseException e) {
                return new LampState.Unreadable(root,
                        ".oillamp/lamp.json is not a lamp identity file: " + e.getMessage());
            }
        }

        Tuple<String> meaningful = listing.entries().removeIf(IGNORED_ENTRIES::contains);
        if (meaningful.isEmpty()) return new LampState.Empty(root);

        // A .oillamp directory without a readable lamp.json means a half-created or damaged lamp,
        // which is a different problem from "these are someone else's files".
        if (meaningful.contains(".oillamp"))
            return new LampState.Unreadable(root, "there is a .oillamp directory but no readable lamp.json");

        return new LampState.Foreign(root, meaningful.size() <= 5 ? meaningful : meaningful.sliceFirst(5));
    }

    private static LampMeta parse(String json) throws JacksonException {
        JsonNode node = JSON.readTree(json);
        JsonNode version = node.get("schemaVersion");
        JsonNode agentId = node.get("agentId");
        JsonNode createdAt = node.get("createdAt");
        JsonNode createdBy = node.get("createdBy");
        if (version == null || agentId == null || createdAt == null)
            throw new IllegalArgumentException("schemaVersion, agentId and createdAt are required");
        JsonNode lastSession = node.get("lastSessionAt");
        return new LampMeta(
                version.asInt(),
                new AgentId(agentId.asText()),
                Instant.parse(createdAt.asText()),
                createdBy == null ? "unknown" : createdBy.asText(),
                lastSession == null || lastSession.isNull()
                        ? Optional.empty()
                        : Optional.of(Instant.parse(lastSession.asText())));
    }

    /** Renders the identity file back out — the inverse of {@link #parse}. */
    public static String render(LampMeta meta) {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"schemaVersion\": ").append(meta.schemaVersion()).append(",\n");
        out.append("  \"agentId\": \"").append(meta.agentId().value()).append("\",\n");
        out.append("  \"createdAt\": \"").append(meta.createdAt()).append("\",\n");
        out.append("  \"createdBy\": \"").append(meta.createdBy()).append('"');
        meta.lastSessionAt().ifPresent(when ->
                out.append(",\n  \"lastSessionAt\": \"").append(when).append('"'));
        return out.append("\n}\n").toString();
    }
}
