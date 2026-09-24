package dev.oillamp;

import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Reading and writing JSON, for everything oillamp keeps or exchanges in it: `lamp.json`,
/// `session.json`, `ready.json`, the network log, the control protocol and podman's answers.
///
/// Always through Jackson, never by joining strings: a value with a quote, a backslash or a
/// control character in it would otherwise produce a file no reader accepts.
final class Json {

    private Json() {}

    static final ObjectMapper MAPPER = new ObjectMapper();

    /// Two spaces per level and `"key": value`, the way a person would write it.
    private static final DefaultPrettyPrinter READABLE = new DefaultPrettyPrinter(
                    Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));

    static ObjectNode object() { return MAPPER.createObjectNode(); }

    /// The parsed text, or empty for anything that is not JSON.
    static Optional<JsonNode> parse(String text) {
        try {
            return Optional.of(MAPPER.readTree(text));
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
    }

    /// A file that people open as well as programs: indented, and ending with a line break.
    static String readable(JsonNode node) {
        try {
            return MAPPER.writer(READABLE).writeValueAsString(node) + "\n";
        } catch (JacksonException cannotHappen) {
            // A tree built in memory always serialises; this is only here for the signature.
            throw new IllegalStateException(cannotHappen);
        }
    }

    /// The text of a field, or `""` when it is missing.
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText();
    }
}
