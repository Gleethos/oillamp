package dev.oillamp;

import java.nio.file.Path;

/**
 * One configuration file, already read by the shell — spec §20.1.
 *
 * <p>The loader is pure, so it receives text rather than reading files itself. Keeping the
 * {@code origin} with the text is what lets a problem say <em>which</em> file the bad key is in
 * when a lamp file and the user-global file both contribute.
 */
record ConfigSource(Path origin, String text, Kind kind) {

    /** Which layer of the precedence chain this file is (§20.1). */
    public enum Kind {
        /** {@code ~/.config/oillamp/config.toml} — optional defaults for every lamp. */
        USER_GLOBAL,
        /** {@code <lamp>/oillamp.toml} — the lamp's own file, and the only one that must declare a schema version. */
        LAMP
    }

    /** The label used when a value came from oillamp's own built-in defaults. */
    public static final Path BUILT_IN = Path.of("<built-in defaults>");

    public static ConfigSource userGlobal(Path origin, String text) {
        return new ConfigSource(origin, text, Kind.USER_GLOBAL);
    }

    public static ConfigSource lamp(Path origin, String text) {
        return new ConfigSource(origin, text, Kind.LAMP);
    }
}
