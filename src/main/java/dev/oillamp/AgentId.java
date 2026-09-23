package dev.oillamp;

/**
 * The permanent identity of one lamp, for example {@code v4elchzj}.
 *
 * <p>Eight characters from {@code a-z} and {@code 2-7} (the lowercase base32 alphabet of RFC 4648),
 * generated once with {@code SecureRandom} when the lamp is created and stored in {@code lamp.json}.
 * The container name, the SSH host alias, the agent directory and the runtime directory are all
 * derived from it, so a lamp that is copied or moved is never mistaken for another one.
 */
record AgentId(String value) {

    /** Base32 without {@code 0}, {@code 1}, {@code 8} and {@code 9}, which look like letters. */
    public static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    public static final int LENGTH = 8;

    public AgentId {
        if (!value.matches("[a-z2-7]{8}"))
            throw new IllegalArgumentException(
                    "An agent id is 8 characters of [a-z2-7], not: '" + value + "'");
    }

    @Override public String toString() { return value; }
}
