package dev.oillamp;

import java.util.Optional;

/// Writes `.oillamp/session/gitconfig`, the git identity of the agent for this session.
///
/// The image's `/etc/gitconfig` includes this file, so every git in the sandbox reads it, from a
/// login shell, a harness or an IDE. It is git's system level, the lowest: the agent's own
/// `~/.gitconfig` and a repository's `.git/config` still override it. It is mounted read-only
/// and rewritten each session, so a changed `[git]` setting or a changed host identity takes
/// effect at the next `oillamp at`.
final class GitConfig {

    private GitConfig() {}

    /// A name and an email for git's `user.name` and `user.email`.
    record Author(String name, String email) {
        public Author {
            if (name.isBlank() || email.isBlank())
                throw new IllegalArgumentException("An author has a name and an email");
            if ((name + email).matches("(?s).*[\\r\\n].*"))
                throw new IllegalArgumentException("A git value is one line");
        }
    }

    /// The default identity: oillamp's own, with the lamp's id in the email, so each commit says
    /// which lamp made it and none carries the user's name or address.
    public static Author genie(AgentId lamp) {
        return new Author("genie agent", "genie@" + lamp.value());
    }

    /// The file's text. Without an author it holds only a comment, and git in the sandbox has no
    /// identity.
    public static String render(Optional<Author> author) {
        StringBuilder out = new StringBuilder()
                .append("# Written by oillamp for this session, from [git] in oillamp.toml.\n")
                .append("# Set user.name and user.email in ~/.gitconfig to override it.\n");
        author.ifPresent(it -> out.append("[user]\n")
                .append("\tname = ").append(quote(it.name())).append('\n')
                .append("\temail = ").append(quote(it.email())).append('\n'));
        return out.toString();
    }

    /// Wraps a value in double quotes, inside which git reads `\\` and `\"` as themselves.
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
