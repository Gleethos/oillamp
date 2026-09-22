package dev.oillamp;

import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the sandbox said about itself when it came up — the contents of {@code ready.json}, §16.
 *
 * <p>Only three fields, and only one of them is load-bearing. The session id is what tells this
 * session's readiness file apart from the one the previous session left in the same bind-mounted
 * directory; the renderer and the fallback flag are there because they are the two facts the
 * human most wants repeated back to them when the desktop opens.
 *
 * <p>Note what is deliberately <em>not</em> taken from here: the desktop size. The host rendered
 * {@code runtime.env} and therefore already knows it, and a viewer sized from the container's own
 * account of itself would be sized from the less trustworthy of the two sources.
 *
 * <p>Deliberately <b>package-private</b>: the sandbox's startup report. Its on-disk shape is a
 * private protocol between the entrypoint and the host, free to change with them.
 */
record ReadyInfo(String renderer, boolean gpuFallback, Optional<SessionId> session) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Unknown rather than absent: a session can be running perfectly with an unreadable report. */
    public static ReadyInfo unknown() {
        return new ReadyInfo("unknown", false, Optional.empty());
    }

    /**
     * Reads the file the entrypoint wrote, falling back to {@link #unknown()} rather than failing.
     *
     * <p>By the time this is parsed, oillamp has already connected to both of the sandbox's
     * sockets. The session is therefore known to be working, and refusing to start it over a
     * field that could not be read would be the tool inventing a problem it does not have.
     */
    public static ReadyInfo parse(String json) {
        try {
            JsonNode node = JSON.readTree(json);
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

    /** The one line the console shows when the desktop is up. */
    public String describe() {
        return "renderer " + renderer + (gpuFallback ? " (fell back from the GPU)" : "");
    }
}
