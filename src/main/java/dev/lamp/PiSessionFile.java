package dev.lamp;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sprouts.Tuple;

/// Reads one of pi's session files into a [Lamp.Conversation].
///
/// pi keeps each conversation in a file of its own, one JSON object per line: a header with the
/// conversation's id, then every entry as it happened. Each entry names the one before it, which
/// makes the conversation a tree. The format is described in pi's documentation, in the sandbox at
/// `/usr/lib/node_modules/@earendil-works/pi-coding-agent/docs/session-format.md`.
///
/// The agent writes these files, so they are read as the agent's work: a line that is not JSON,
/// or is not an entry, is passed over, and nothing in them is trusted to be well formed.
///
/// Pure: it is given the lines, and [Lamp] reads the files.
final class PiSessionFile {

    private PiSessionFile() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /// How long a tool call's summary may be, in characters.
    private static final int SUMMARY_LENGTH = 160;

    /// Reads the lines of one session file.
    ///
    /// @param file where the file is, relative to the agent's home
    /// @return the conversation, or empty when the lines have no header
    static Optional<Lamp.Conversation> parse(String file, Iterable<String> lines) {
        String id = "";
        String name = "";
        Instant started = Instant.EPOCH;
        Instant modified = Instant.EPOCH;
        List<Lamp.Conversation.Entry> entries = new ArrayList<>();
        for (String line : lines) {
            Optional<JsonNode> read = readLine(line);
            if (read.isEmpty()) continue;
            JsonNode record = read.get();
            String type = record.path("type").asText();
            Instant at = time(record.path("timestamp").asText(), modified);
            if (type.equals("session")) {
                if (!id.isEmpty()) continue;
                id = record.path("id").asText("");
                started = at;
                modified = at;
                continue;
            }
            if (!record.path("id").isTextual()) continue;
            if (type.equals("session_info")) name = record.path("name").asText("");
            modified = at.isAfter(modified) ? at : modified;
            entries.add(entry(record, type, at));
        }
        if (id.isBlank()) return Optional.empty();
        return Optional.of(new Lamp.Conversation(id, name, file, started, modified,
                Tuple.of(Lamp.Conversation.Entry.class, entries)));
    }

    private static Lamp.Conversation.Entry entry(JsonNode record, String type, Instant at) {
        String id = record.path("id").asText();
        Optional<String> parent = record.path("parentId").isTextual()
                ? Optional.of(record.path("parentId").asText()) : Optional.empty();
        JsonNode message = record.path("message");
        Tuple<Lamp.Conversation.ToolCall> none = Tuple.of(Lamp.Conversation.ToolCall.class);
        return switch (type) {
            case "message" -> switch (message.path("role").asText()) {
                case "user" -> new Lamp.Conversation.Entry(id, parent, at, Lamp.Conversation.Kind.QUESTION,
                        text(message.path("content")), "", none, Optional.empty(), false);
                case "assistant" -> {
                    String stop = message.path("stopReason").asText();
                    boolean failed = stop.equals("error") || stop.equals("aborted");
                    String said = text(message.path("content"));
                    String error = message.path("errorMessage").asText("");
                    yield new Lamp.Conversation.Entry(id, parent, at, Lamp.Conversation.Kind.ANSWER,
                            failed && !error.isBlank() ? (said.isBlank() ? error : said + "\n\n" + error) : said,
                            thinking(message.path("content")), calls(message.path("content")), Optional.empty(), failed);
                }
                case "toolResult" -> new Lamp.Conversation.Entry(id, parent, at, Lamp.Conversation.Kind.TOOL_OUTPUT,
                        text(message.path("content")), "", none,
                        Optional.of(message.path("toolName").asText("")), message.path("isError").asBoolean(false));
                default -> other(id, parent, at, "");
            };
            case "compaction", "branch_summary" -> new Lamp.Conversation.Entry(id, parent, at,
                    Lamp.Conversation.Kind.SUMMARY, record.path("summary").asText(""), "", none, Optional.empty(), false);
            case "custom_message" -> other(id, parent, at,
                    record.path("display").asBoolean(false) ? text(record.path("content")) : "");
            default -> other(id, parent, at, "");
        };
    }

    private static Lamp.Conversation.Entry other(String id, Optional<String> parent, Instant at, String text) {
        return new Lamp.Conversation.Entry(id, parent, at, Lamp.Conversation.Kind.OTHER, text, "",
                Tuple.of(Lamp.Conversation.ToolCall.class), Optional.empty(), false);
    }

    private static Optional<JsonNode> readLine(String line) {
        if (line.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(JSON.readTree(line)).filter(JsonNode::isObject);
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
    }

    private static Instant time(String text, Instant fallback) {
        try {
            return text.isEmpty() ? fallback : Instant.parse(text);
        } catch (DateTimeParseException unreadable) {
            return fallback;
        }
    }

    /// The text of a message's content: a string, or a list of blocks of which the text ones count.
    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content)
            if (block.path("type").asText().equals("text")) text.append(block.path("text").asText());
        return text.toString();
    }

    private static String thinking(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content)
            if (block.path("type").asText().equals("thinking")) text.append(block.path("thinking").asText());
        return text.toString();
    }

    private static Tuple<Lamp.Conversation.ToolCall> calls(JsonNode content) {
        Tuple<Lamp.Conversation.ToolCall> calls = Tuple.of(Lamp.Conversation.ToolCall.class);
        for (JsonNode block : content)
            if (block.path("type").asText().equals("toolCall"))
                calls = calls.add(new Lamp.Conversation.ToolCall(block.path("id").asText(""),
                        block.path("name").asText(""), summary(block.path("arguments"))));
        return calls;
    }

    /// One line saying what a tool call does: the command for `bash`, the file for the file
    /// tools, and the arguments as JSON for anything else.
    static String summary(JsonNode arguments) {
        for (String field : new String[] {"command", "path", "file_path", "pattern", "url"})
            if (arguments.path(field).isTextual()) return oneLine(arguments.path(field).asText());
        return oneLine(arguments.isMissingNode() || arguments.isNull() ? "" : arguments.toString());
    }

    private static String oneLine(String text) {
        String line = text.strip().replaceAll("\\s*\\n\\s*", " ⏎ ");
        return line.length() <= SUMMARY_LENGTH ? line : line.substring(0, SUMMARY_LENGTH - 1) + "…";
    }
}
