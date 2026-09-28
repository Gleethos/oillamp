package dev.gui.genie;

/// How a genie's harness is started, and what it is told about living in Genies.
final class GeniePrompt {

    private GeniePrompt() {}

    /// pi in RPC mode, on the Eden AI provider that the sandbox points at oillamp's model relay,
    /// continuing the genie's last conversation if there is one.
    static String[] harness(String name, String model) {
        return new String[] {
            "pi", "--mode", "rpc", "--provider", "edenai", "--model", model,
            "--continue", "--append-system-prompt", about(name)};
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
