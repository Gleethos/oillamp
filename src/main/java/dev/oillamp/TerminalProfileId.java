package dev.oillamp;

/**
 * The terminal emulators oillamp knows how to open a window in — spec §17.4 (D-22).
 *
 * <p>The order of the constants is the fallback order used when nothing better applies, so it
 * runs from "most likely to be the user's actual terminal on a modern desktop" to "always works".
 */
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

    /** The spelling used in {@code terminal.profile} in oillamp.toml. */
    public String configName() { return executable; }
}
