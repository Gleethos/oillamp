package dev.oillamp;

/** What a network rule does when it matches — spec §18.3. */
enum Decision {
    ALLOW, DENY;

    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
