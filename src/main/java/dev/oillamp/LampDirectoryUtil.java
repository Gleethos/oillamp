package dev.oillamp;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import sprouts.Tuple;

/// Decides what is at a lamp path: nothing, an empty directory, an existing lamp, someone else's
/// files, or a damaged lamp. The caller reads the directory listing and `lamp.json`; this class
/// only interprets them.
final class LampDirectoryUtil {

    private LampDirectoryUtil() {}

    /// Entries that do not make a directory count as someone else's: files that editors and file
    /// managers leave behind, and the lamp's own `oillamp.toml` and `README.txt`. Writing
    /// `oillamp.toml` before the first run is a normal way to configure a new lamp.
    private static final Tuple<String> IGNORED_ENTRIES = Tuple.of(String.class,
            ".DS_Store", ".directory", "Thumbs.db", ".keep", ".gitkeep",
            "oillamp.toml", "README.txt");

    /// Classifies what is at the lamp path.
    ///
    /// @param lampJson the contents of `.oillamp/lamp.json`, if the shell found and read it
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
        JsonNode node = Json.READER.readTree(json);
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

    /// Writes `lamp.json` as text; the reverse of [#parse].
    public static String render(LampMeta meta) {
        var node = Json.object()
                .put("schemaVersion", meta.schemaVersion())
                .put("agentId", meta.agentId().value())
                .put("createdAt", meta.createdAt().toString())
                .put("createdBy", meta.createdBy());
        meta.lastSessionAt().ifPresent(when -> node.put("lastSessionAt", when.toString()));
        return Json.readable(node);
    }
}
