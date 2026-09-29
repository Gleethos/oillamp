package dev.gui.genie;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.gui.model.Conversation;
import dev.gui.model.Conversations;

import sprouts.Tuple;

/// A genie's conversations, read from pi's session files in the genie's home on this computer.
///
/// pi writes one file per conversation, one JSON object per line: a header with the session's
/// id, then every entry of the conversation as it happened. Genies reads them from the lamp
/// directory directly, not through the sandbox, so the tree of conversations is there while the
/// genie sleeps too.
///
/// The genie writes these files, so they are read as the genie's work: no link is followed, not
/// even a directory on the way, a file larger than [#LARGEST] is left out, and a line that is not
/// JSON is skipped.
public final class SessionFiles {

    private SessionFiles() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /// Larger session files are left out of the tree. A long conversation with much tool output
    /// is a few megabytes.
    static final long LARGEST = 64L * 1024 * 1024;

    /// Every conversation in the genie's `home`, newest first. None when pi has not kept any yet.
    public static Tuple<Conversation> read(Path home) {
        Optional<Path> directory = directory(home);
        if (directory.isEmpty()) return Tuple.of(Conversation.class);
        List<Conversation> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory.get())) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (Files.size(file) > LARGEST) continue;
                    try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        parse(Conversations.DIRECTORY + "/" + name, lines.lines()).ifPresent(found::add);
                    }
                } catch (IOException | java.io.UncheckedIOException unreadable) {
                    // Being written, or gone since it was listed: left out until the next look.
                }
            }
        } catch (IOException unreadable) {
            return Tuple.of(Conversation.class);
        }
        found.sort(Comparator.comparing(Conversation::modified).reversed());
        return Tuple.of(Conversation.class, found);
    }

    /// Reads the lines of one session file, or nothing when they have no header.
    ///
    /// @param file where the file is, relative to the genie's home
    static Optional<Conversation> parse(String file, Stream<String> lines) {
        String id = "";
        String name = "";
        String modified = "";
        List<Conversation.Step> steps = new ArrayList<>();
        for (String line : (Iterable<String>) lines::iterator) {
            Optional<JsonNode> read = readLine(line);
            if (read.isEmpty()) continue;
            JsonNode entry = read.get();
            String type = entry.path("type").asText();
            if (type.equals("session")) {
                id = entry.path("id").asText();
                modified = entry.path("timestamp").asText();
                continue;
            }
            if (!entry.path("id").isTextual()) continue;
            if (type.equals("session_info")) name = entry.path("name").asText("");
            modified = entry.path("timestamp").asText(modified);
            JsonNode message = entry.path("message");
            boolean asked = type.equals("message") && message.path("role").asText().equals("user");
            steps.add(new Conversation.Step(entry.path("id").asText(), entry.path("parentId").asText(""),
                                            asked, asked ? text(message.path("content")) : ""));
        }
        if (id.isEmpty()) return Optional.empty();
        return Optional.of(new Conversation(id, file, name, modified, Tuple.of(Conversation.Step.class, steps)));
    }

    /// Deletes the conversation kept in `file`, relative to the genie's `home`.
    ///
    /// @throws IOException when it is not one of pi's session files, or could not be deleted
    public static void delete(Path home, String file) throws IOException {
        Path directory = directory(home).orElseThrow(() -> new IOException("the genie keeps no conversations"));
        String prefix = Conversations.DIRECTORY + "/";
        String name = file.startsWith(prefix) ? file.substring(prefix.length()) : "";
        if (!Handouts.isPlainName(name) || !name.endsWith(".jsonl"))
            throw new IOException("not a conversation of the genie's: " + file);
        Path doomed = directory.resolve(name);
        if (!Files.isRegularFile(doomed, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("the conversation is gone already: " + name);
        Files.delete(doomed);
    }

    /// The directory pi keeps the genie's conversations in, if it is there and no link leads to it.
    private static Optional<Path> directory(Path home) {
        Path at = home;
        for (Path part : Path.of(Conversations.DIRECTORY)) {
            at = at.resolve(part);
            if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        }
        return Optional.of(at);
    }

    private static Optional<JsonNode> readLine(String line) {
        try {
            return Optional.ofNullable(JSON.readTree(line)).filter(JsonNode::isObject);
        } catch (JacksonException notJson) {
            return Optional.empty();
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
}
