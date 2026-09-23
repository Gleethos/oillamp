package dev.oillamp;

/// The terminal emulators oillamp knows how to open a window in. See [Terminals] for the
/// command line each one needs.
///
/// The order of the constants is the fallback order when neither the configuration nor the
/// desktop picks one: from the most likely terminal on a modern desktop to the one that is almost
/// always installed.
enum TerminalProfileId {
    PTYXIS("ptyxis"),
    GNOME_TERMINAL("gnome-terminal"),
    KGX("kgx"),
    KONSOLE("konsole"),
    KITTY("kitty"),
    FOOT("foot"),
    ALACRITTY("alacritty"),
    WEZTERM("wezterm"),
    XTERM("xterm");

    private final String executable;

    TerminalProfileId(String executable) { this.executable = executable; }

    public String executable() { return executable; }

    /// The spelling used in `terminal.profile` in oillamp.toml.
    public String configName() { return executable; }
}
