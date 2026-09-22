package dev.oillamp;

/**
 * What a network rule does when it matches — spec §18.3.
 *
 * <p>Deliberately <b>package-private</b>: allow or deny. It belongs to the policy engine's
 * vocabulary and to {@code oillamp.toml}; the config file is the contract, not this enum.
 */
enum Decision {
    ALLOW, DENY;

    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
