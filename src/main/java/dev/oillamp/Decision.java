package dev.oillamp;

/// What a network rule, or the policy's default, does with a connection: allow it or deny it.
enum Decision {
    ALLOW, DENY;

    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
