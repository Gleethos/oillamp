package dev.gui.pi;

import java.util.Optional;

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
    public static String askForHistory() {
        return JSON.createObjectNode().put("type", "get_messages").toString();
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
        if (!command.equals("get_messages")) return Optional.empty();
        Tuple<PiEvent.History.Line> lines = Tuple.of(PiEvent.History.Line.class);
        for (JsonNode message : record.path("data").path("messages")) {
            String role = message.path("role").asText();
            String text = text(message.path("content"));
            if ((role.equals("user") || role.equals("assistant")) && !text.isBlank())
                lines = lines.add(new PiEvent.History.Line(role.equals("user"), text));
        }
        return Optional.of(new PiEvent.History(lines));
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
