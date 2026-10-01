package dev.oillamp;

import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;

/// The contents of `ready.json`, which the container's entrypoint writes once the desktop and
/// the SSH listener accept connections.
///
/// The session id tells this session's file apart from one the previous session left behind in
/// the same directory. The renderer and the fallback flag are shown to the user when the desktop
/// opens.
///
/// The desktop size in the file is deliberately ignored. The host wrote it into
/// `runtime.env` itself, and trusts its own value over what the container reports.
record ReadyInfo(String renderer, boolean gpuFallback, Optional<SessionId> session) {

    /// Used when the file cannot be read. The session may still be working perfectly.
    public static ReadyInfo unknown() {
        return new ReadyInfo("unknown", false, Optional.empty());
    }

    /// Reads the file, falling back to [#unknown()] instead of failing.
    ///
    /// By the time this runs, oillamp has already connected to both of the sandbox's sockets, so
    /// the session is known to work. An unreadable field is no reason to stop it.
    public static ReadyInfo parse(String json) {
        try {
            JsonNode node = Json.READER.readTree(json);
            JsonNode renderer = node.get("renderer");
            JsonNode fallback = node.get("gpu_fallback");
            JsonNode session = node.get("session");
            return new ReadyInfo(
                    renderer == null ? "unknown" : renderer.asText(),
                    fallback != null && fallback.asBoolean(),
                    session == null ? Optional.empty() : sessionId(session.asText()));
        } catch (JacksonException e) {
            return unknown();
        }
    }

    private static Optional<SessionId> sessionId(String value) {
        try {
            return Optional.of(new SessionId(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /// The one line the console shows when the desktop is up.
    public String describe() {
        return "renderer " + renderer + (gpuFallback ? " (fell back from the GPU)" : "");
    }
}
