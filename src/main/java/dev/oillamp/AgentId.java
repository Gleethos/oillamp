package dev.oillamp;

/**
 * The permanent identity of one lamp — spec §10.1.
 *
 * <p>Eight characters from the RFC 4648 lowercase base32 alphabet, generated once from a
 * {@code SecureRandom} in the shell and never changed. Every derived name (container, hostname,
 * agent directory, runtime directory, SSH host alias) is a pure function of it, so a lamp that
 * is copied or moved can never be mistaken for another one.
 *
 * <p>Deliberately <b>package-private</b>: the identifier that names a lamp forever. Nothing outside
 * constructs one, and making it public would freeze its base32 alphabet into the API.
 */
record AgentId(String value) {

    /** The alphabet — deliberately without look-alike characters, so ids survive being read aloud. */
    public static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    public static final int LENGTH = 8;

    public AgentId {
        if (!value.matches("[a-z2-7]{8}"))
            throw new IllegalArgumentException(
                    "An agent id is 8 characters of [a-z2-7], not: '" + value + "'");
    }

    @Override public String toString() { return value; }
}
