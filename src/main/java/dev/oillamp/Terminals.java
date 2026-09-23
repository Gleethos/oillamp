package dev.oillamp;

import java.util.Locale;
import java.util.Optional;

import sprouts.Tuple;

/// Chooses a terminal emulator and builds the command line that opens the sandbox shell in it.
///
/// Terminal emulators differ in how they set a window title and how they are given a command
/// (`--` or `-e`). The differences are kept in a table, so supporting another terminal
/// means adding a row. `VerifyingTerminalProfilesSpec` checks the rows against the terminals
/// installed on the machine it runs on.
final class Terminals {

    private Terminals() {}

    /// Expands to the full ssh argv; must stand alone as a template token.
    public static final String COMMAND_PLACEHOLDER = "{cmd}";
    /// Expands to the window title; may sit inside a larger token, as in `--title={title}`.
    public static final String TITLE_PLACEHOLDER = "{title}";

    /// One terminal's argument template, with `{cmd}` and `{title}` placeholders.
    public record Profile(TerminalProfileId id, Tuple<String> template) {}

    private static final Tuple<Profile> TABLE = Tuple.of(Profile.class,
        profile(TerminalProfileId.PTYXIS,         "ptyxis", "--new-window", "--", "{cmd}"),
        profile(TerminalProfileId.GNOME_TERMINAL, "gnome-terminal", "--title={title}", "--", "{cmd}"),
        profile(TerminalProfileId.KGX,            "kgx", "--title={title}", "--", "{cmd}"),
        profile(TerminalProfileId.KONSOLE,        "konsole", "-p", "tabtitle={title}", "-e", "{cmd}"),
        profile(TerminalProfileId.KITTY,          "kitty", "--title", "{title}", "{cmd}"),
        profile(TerminalProfileId.FOOT,           "foot", "--title={title}", "{cmd}"),
        profile(TerminalProfileId.ALACRITTY,      "alacritty", "--title", "{title}", "-e", "{cmd}"),
        profile(TerminalProfileId.WEZTERM,        "wezterm", "start", "--", "{cmd}"),
        profile(TerminalProfileId.XTERM,          "xterm", "-T", "{title}", "-e", "{cmd}"));

    private static Profile profile(TerminalProfileId id, String... template) {
        return new Profile(id, Tuple.of(String.class, template));
    }

    public static Tuple<Profile> table() { return TABLE; }

    public static Optional<Profile> profileOf(TerminalProfileId id) {
        for (Profile p : TABLE) if (p.id() == id) return Optional.of(p);
        return Optional.empty();
    }

    /// The names `terminal.profile` accepts, in the order doctor lists them.
    public static Tuple<String> supportedNames() {
        Tuple<String> names = Tuple.of(String.class);
        for (Profile p : TABLE) names = names.add(p.id().configName());
        return names;
    }

    /// Picks the terminal to open the sandbox shell in.
    ///
    /// Preference order: `terminal.profile` if set, then the detected desktop's own
    /// terminal (GNOME or KDE), then the table order. Preferring the desktop's own terminal means
    /// the window looks like the ones the user opens themselves.
    public static Result<Profile> choose(Optional<TerminalProfileId> requested,
                                         Tuple<TerminalCandidate> available,
                                         GraphicalSession session) {
        if (requested.isPresent()) {
            TerminalProfileId wanted = requested.get();
            for (TerminalCandidate candidate : available)
                if (candidate.id() == wanted)
                    return Result.ok(profileOf(wanted).orElseThrow());
            Problem problem = Problems.noTerminal(supportedNames())
                    .withEvidence(new Problem.Evidence.Config(
                            java.nio.file.Path.of("oillamp.toml"), "terminal.profile",
                            wanted.configName(), "a terminal that is installed"));
            return Result.err(problem);
        }
        for (TerminalProfileId preferred : preferredFor(session))
            for (TerminalCandidate candidate : available)
                if (candidate.id() == preferred)
                    return Result.ok(profileOf(preferred).orElseThrow());
        for (Profile profile : TABLE)
            for (TerminalCandidate candidate : available)
                if (candidate.id() == profile.id())
                    return Result.ok(profile);
        return Result.err(Problems.noTerminal(supportedNames()));
    }

    private static Tuple<TerminalProfileId> preferredFor(GraphicalSession session) {
        String desktop = session.desktop().toLowerCase(Locale.ROOT);
        if (desktop.contains("gnome"))
            return Tuple.of(TerminalProfileId.class,
                    TerminalProfileId.PTYXIS, TerminalProfileId.GNOME_TERMINAL, TerminalProfileId.KGX);
        if (desktop.contains("kde") || desktop.contains("plasma"))
            return Tuple.of(TerminalProfileId.class, TerminalProfileId.KONSOLE);
        return Tuple.of(TerminalProfileId.class);
    }

    /// Renders the argv that opens `command` in a titled window of this terminal.
    ///
    /// @param template the profile's template, or a user-supplied `terminal.command`
    public static Tuple<String> render(Tuple<String> template, String title, Tuple<String> command) {
        Tuple<String> argv = Tuple.of(String.class);
        for (String token : template) {
            if (token.equals(COMMAND_PLACEHOLDER))
                argv = argv.addAll(command);
            else
                argv = argv.add(token.replace(TITLE_PLACEHOLDER, title));
        }
        return argv;
    }

    /// The window title oillamp gives the sandbox terminal.
    public static String titleFor(String lampName) { return "oillamp · " + lampName; }
}
