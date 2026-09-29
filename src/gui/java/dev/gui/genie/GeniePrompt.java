package dev.gui.genie;

import java.util.ArrayList;
import java.util.List;

import dev.gui.pi.PiProtocol;

/// How a genie's harness is started, and what it is told about living in Genies.
final class GeniePrompt {

    private GeniePrompt() {}

    /// pi in RPC mode, on the Eden AI provider that the sandbox points at oillamp's model relay,
    /// with Genies' extension for moving within a conversation.
    ///
    /// pi refuses to start with an extension that is not there, which it is not in a sandbox
    /// image built before Genies had one. Such a genie must still wake, so a shell adds the
    /// extension only when it finds it; [PiProtocol#askWhatItCanDo()] then tells which it is.
    ///
    /// @param conversation the session file to open, as the sandbox names it, or nothing to
    ///                     continue the last conversation
    static String[] harness(String name, String model, String conversation) {
        List<String> command = new ArrayList<>(List.of(
            "sh", "-c", "if [ -r \"$0\" ]; then exec pi --extension \"$0\" \"$@\"; else exec pi \"$@\"; fi",
            PiProtocol.EXTENSION,
            "--mode", "rpc", "--provider", "edenai", "--model", model));
        command.addAll(conversation.isEmpty() ? List.of("--continue") : List.of("--session", conversation));
        command.addAll(List.of("--append-system-prompt", about(name)));
        return command.toArray(String[]::new);
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
              your desktop and say so; the user can watch your desktop in the app.
            - Keep answers short unless the user asks for more.
            """.formatted(name);
    }
}
