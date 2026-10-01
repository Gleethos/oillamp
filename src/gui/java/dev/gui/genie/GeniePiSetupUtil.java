package dev.gui.genie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What the genie's pi is told about living in Genies, and which model it uses.
///
/// The lamp's session runs pi itself, so Genies cannot give it command-line options. It writes
/// pi's own files in the genie's home instead, before the first run: `APPEND_SYSTEM.md`, which pi
/// adds to its system prompt, and `defaultProvider` and `defaultModel` in `settings.json`, keeping
/// everything else in that file.
final class GeniePiSetupUtil {

    private GeniePiSetupUtil() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /// pi's directory in the genie's home.
    static final String PI_DIRECTORY = ".pi/agent";

    /// Writes the genie's instructions and model into pi's directory in `home`. pi reads both when
    /// it starts, which is at the lamp's first run.
    ///
    /// @throws IOException when pi's directory is a link, or a file could not be written
    static void prepare(Path home, String name, String model) throws IOException {
        Path pi = home;
        for (Path part : Path.of(PI_DIRECTORY)) {
            pi = pi.resolve(part);
            if (Files.isSymbolicLink(pi)) throw new IOException("the genie's " + PI_DIRECTORY + " is a link");
        }
        Files.createDirectories(pi);
        Files.writeString(pi.resolve("APPEND_SYSTEM.md"), about(name), StandardCharsets.UTF_8);
        Path settingsFile = pi.resolve("settings.json");
        ObjectNode settings = JSON.createObjectNode();
        if (Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode read = JSON.readTree(Files.readString(settingsFile, StandardCharsets.UTF_8));
            if (read != null && read.isObject()) settings = (ObjectNode) read;
        }
        settings.put("defaultProvider", "edenai").put("defaultModel", model);
        Files.writeString(settingsFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(settings) + "\n",
                StandardCharsets.UTF_8);
    }

    /// Added to pi's own instructions. The sandbox's AGENTS.md, which pi reads too, explains the
    /// sandbox itself; this explains the app around it.
    static String about(String name) {
        return """
            You are %s, a genie: an assistant with a Linux desktop of your own, in a sandbox. The
            user talks to you through the Genies app, which shows this conversation as a chat and
            can show your desktop next to it.

            - To hand the user a file, save it in ~/outbox. The app shows it in the chat, and the
              user can save it. Mention the file's name when you do.
            - Files the user gives you arrive in ~/inbox. The app tells you when one does.
            - To show the user something graphical (a picture, a web page, a program), open it on
              your desktop, fullscreen where it can be, and run `lamp show "what it is"`: the app
              then shows your desktop next to the chat, usually at the size of that panel.
            - Keep answers short unless the user asks for more.
            """.formatted(name);
    }
}
