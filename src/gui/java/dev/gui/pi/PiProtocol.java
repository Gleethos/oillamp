package dev.gui.pi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Tuple;

/// pi's RPC mode, as far as a chat needs it: the commands a genie is sent, and the events it
/// answers with.
///
/// Every command and every event is one JSON object on one line. pi's own documentation is in the
/// sandbox, at `/usr/lib/node_modules/@earendil-works/pi-coding-agent/docs/rpc.md`.
///
/// Pure: it only turns text into values and values into text. [PiSession] does the talking.
public final class PiProtocol {

    private PiProtocol() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /// Tool output longer than this is shortened for the chat. The genie itself saw all of it.
    static final int OUTPUT_SHOWN = 4_000;

    /// Sends `text` as the user's next message.
    ///
    /// @param busy whether the genie is still working on the last one. pi then refuses a plain
    ///             prompt, so it is queued as a follow-up instead, answered once the genie is done
    public static String prompt(String text, boolean busy) {
        ObjectNode command = JSON.createObjectNode().put("type", "prompt").put("message", text);
        if (busy) command.put("streamingBehavior", "followUp");
        return command.toString();
    }

    /// Stops what the genie is doing.
    public static String abort() {
        return JSON.createObjectNode().put("type", "abort").toString();
    }

    /// Asks for the conversation so far, answered with a [PiEvent.History].
    ///
    /// pi's entries come as a flat list, each naming the one before it. pi can also send them as
    /// a tree, but nested one level per entry, which a long conversation takes deeper than a
    /// JSON reader will follow.
    public static String askForHistory() {
        return JSON.createObjectNode().put("type", "get_entries").toString();
    }

    /// Asks which conversation pi has open, answered with a [PiEvent.Opened].
    public static String askWhere() {
        return JSON.createObjectNode().put("type", "get_state").toString();
    }

    /// Opens the conversation kept in `file`, as the sandbox names it; answered with a
    /// [PiEvent.Switched].
    public static String open(String file) {
        return JSON.createObjectNode().put("type", "switch_session").put("sessionPath", file).toString();
    }

    /// Starts a new conversation; answered with a [PiEvent.Switched].
    public static String startAfresh() {
        return JSON.createObjectNode().put("type", "new_session").toString();
    }

    /// The name of the pi extension Genies adds to pi in the sandbox, whose commands move within a
    /// conversation. pi's RPC mode can show a conversation's tree, but not move within it.
    public static final String EXTENSION = "/usr/local/share/oillamp/genies/pi-genies.js";

    /// Asks which commands pi has, answered with a [PiEvent.CanMove] saying whether Genies'
    /// extension is among them.
    public static String askWhatItCanDo() {
        return JSON.createObjectNode().put("type", "get_commands").toString();
    }

    /// Continues the conversation after the entry `id`; answered with a [PiEvent.Moved].
    public static String goTo(String id) {
        return prompt("/genies-goto " + id, false);
    }

    /// Asks `text` instead of the user's message `id`, which stays in the conversation as a branch
    /// of its own; answered with a [PiEvent.Moved], and then with the genie's answer.
    public static String askInstead(String id, String text) {
        return prompt("/genies-edit " + id + " " + text, false);
    }

    /// Reads one line pi wrote, or nothing for a line that is not an event the chat shows.
    public static Optional<PiEvent> read(String line) {
        JsonNode record;
        try {
            record = JSON.readTree(line);
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
        if (record == null || !record.isObject()) return Optional.empty();
        return switch (record.path("type").asText()) {
            case "message_update"       -> update(record.path("assistantMessageEvent"));
            case "message_end"          -> answered(record.path("message"));
            case "tool_execution_start" -> Optional.of(new PiEvent.ToolStarted(
                    record.path("toolCallId").asText(), record.path("toolName").asText(),
                    summary(record.path("toolName").asText(), record.path("args"))));
            case "tool_execution_end"   -> Optional.of(new PiEvent.ToolFinished(
                    record.path("toolCallId").asText(), record.path("isError").asBoolean(),
                    shortened(text(record.path("result").path("content")))));
            case "auto_retry_start"     -> Optional.of(new PiEvent.Retrying(
                    record.path("attempt").asInt(), record.path("maxAttempts").asInt(),
                    record.path("errorMessage").asText()));
            case "agent_settled"        -> Optional.of(new PiEvent.Settled());
            case "response"             -> response(record);
            case "extension_ui_request" -> notice(record);
            case "extension_error"      -> Optional.of(new PiEvent.Refused(record.path("event").asText("extension"),
                                                                           record.path("error").asText()));
            default                     -> Optional.empty();
        };
    }

    private static Optional<PiEvent> update(JsonNode event) {
        return switch (event.path("type").asText()) {
            case "text_delta"     -> Optional.of(new PiEvent.Said(event.path("delta").asText()));
            case "thinking_start" -> Optional.of(new PiEvent.Thinking(""));
            case "thinking_delta" -> Optional.of(new PiEvent.Thinking(event.path("delta").asText()));
            default               -> Optional.empty();
        };
    }

    /// Only the genie's own messages. pi also reports the user's message and tool results as
    /// messages, which the chat already shows in its own way.
    private static Optional<PiEvent> answered(JsonNode message) {
        if (!message.path("role").asText().equals("assistant")) return Optional.empty();
        String stop = message.path("stopReason").asText();
        String failed = stop.equals("error") || stop.equals("aborted")
                ? message.path("errorMessage").asText(stop.equals("aborted") ? "stopped" : "the model failed")
                : "";
        return Optional.of(new PiEvent.Answered(text(message.path("content")), failed,
                message.path("usage").path("totalTokens").asInt()));
    }

    private static Optional<PiEvent> response(JsonNode record) {
        String command = record.path("command").asText();
        if (!record.path("success").asBoolean(false))
            return Optional.of(new PiEvent.Refused(command, record.path("error").asText("refused")));
        JsonNode data = record.path("data");
        return switch (command) {
            case "get_entries" -> Optional.of(history(data));
            case "get_state"   -> Optional.of(new PiEvent.Opened(data.path("sessionFile").asText("")));
            case "get_commands" -> {
                boolean found = false;
                for (JsonNode known : data.path("commands")) found |= known.path("name").asText().equals("genies-goto");
                yield Optional.of(new PiEvent.CanMove(found));
            }
            case "switch_session", "new_session" -> Optional.of(data.path("cancelled").asBoolean(false)
                    ? new PiEvent.Refused(command, "an extension in the sandbox said no")
                    : new PiEvent.Switched());
            default -> Optional.empty();
        };
    }

    /// The way from the first entry to the one pi is at, found by following each entry to the one
    /// before it, from the end.
    private static PiEvent.History history(JsonNode data) {
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode entry : data.path("entries"))
            if (entry.path("id").isTextual()) byId.put(entry.path("id").asText(), entry);
        String leaf = data.path("leafId").asText("");
        List<PiEvent.History.Line> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode at = byId.get(leaf); at != null && seen.add(at.path("id").asText()); at = byId.get(at.path("parentId").asText())) {
            JsonNode message = at.path("message");
            String role = message.path("role").asText();
            String text = text(message.path("content"));
            if (at.path("type").asText().equals("message") && (role.equals("user") || role.equals("assistant")) && !text.isBlank())
                lines.add(new PiEvent.History.Line(role.equals("user"), text, at.path("id").asText()));
        }
        return new PiEvent.History(Tuple.of(PiEvent.History.Line.class, lines.reversed()), leaf);
    }

    /// What Genies' extension in the sandbox says when it moved, or could not. Other extensions'
    /// notices are not for the chat.
    private static Optional<PiEvent> notice(JsonNode record) {
        String message = record.path("message").asText();
        if (!record.path("method").asText().equals("notify") || !message.startsWith("genies: ")) return Optional.empty();
        String couldNot = "genies: could not move: ";
        return Optional.of(new PiEvent.Moved(message.startsWith(couldNot) ? message.substring(couldNot.length()) : ""));
    }

    /// The text of a message's content, which is either a string or a list of blocks of which
    /// only the text blocks are read.
    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content)
            if (block.path("type").asText().equals("text")) text.append(block.path("text").asText());
        return text.toString();
    }

    /// One line saying what a tool is about to do: the command for `bash`, the file for the
    /// file tools, and the arguments as JSON for anything else.
    private static String summary(String tool, JsonNode arguments) {
        for (String field : new String[] {"command", "path", "file_path", "pattern", "url"})
            if (arguments.path(field).isTextual()) return oneLine(arguments.path(field).asText());
        return oneLine(tool.isEmpty() || arguments.isMissingNode() ? "" : arguments.toString());
    }

    private static String oneLine(String text) {
        String line = text.strip().replaceAll("\\s*\\n\\s*", " ⏎ ");
        return line.length() <= 160 ? line : line.substring(0, 159) + "…";
    }

    private static String shortened(String output) {
        return output.length() <= OUTPUT_SHOWN ? output
             : output.substring(0, OUTPUT_SHOWN) + "\n… (" + (output.length() - OUTPUT_SHOWN) + " more characters)";
    }
}
