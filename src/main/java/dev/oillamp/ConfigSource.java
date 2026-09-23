package dev.oillamp;

import java.nio.file.Path;

/**
 * The text of one configuration file and where it came from.
 *
 * <p>{@link ConfigLoader} does not read files itself; it is given their text. The {@code origin}
 * lets a problem name the file a bad key is in when both the global file and the lamp's file
 * contribute.
 */
record ConfigSource(Path origin, String text, Kind kind) {

    /** Which of the two configuration files this is. The lamp's file wins over the global one. */
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
