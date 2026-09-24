package dev.oillamp;

import sprouts.Tuple;

/// The `[network]` section of the configuration: which outbound connections the egress proxy
/// allows. [Policy#decide] applies it.
///
/// It lives in `oillamp.toml`, outside the agent directory, so the agent cannot change it.
record NetworkPolicy(Decision defaultDecision, Tuple<Rule> rules,
                            boolean logAllowed, boolean consoleDenied) {

    /// The default policy: allow the internet, except Eden AI's global endpoint, private networks
    /// and the host's own loopback. Users add allow rules for internal services above the deny
    /// rules.
    public static NetworkPolicy shippedDefault() {
        return new NetworkPolicy(Decision.ALLOW,
                Tuple.of(Rule.class, edenAiOnlyInTheEu(), blockPrivateRanges()), true, true);
    }

    public static final String EDEN_AI_RULE_LABEL = "Eden AI only through its EU endpoint";

    /// Refuses Eden AI's global endpoint, so that Eden AI is only reached through
    /// `api.eu.edenai.run`. The harnesses are set up to use that endpoint already; this rule
    /// covers anything that ignores their settings, and says why in the refusal.
    public static Rule edenAiOnlyInTheEu() {
        return new Rule(EDEN_AI_RULE_LABEL, Decision.DENY,
                Tuple.of(HostPattern.class, HostPattern.parse("api.edenai.run")),
                Tuple.of(PortRange.class), Tuple.of(Cidr.class));
    }

    public static final String DEFAULT_DENY_RULE_LABEL = "block private, internal and loopback ranges";

    public static Rule blockPrivateRanges() {
        Tuple<Cidr> ranges = Tuple.of(Cidr.class);
        for (String text : new String[] {
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                // "::" is the unspecified address. Linux connects to it as this machine.
                "::1/128", "::/128", "fc00::/7", "fe80::/10" })
            ranges = ranges.add(Cidr.parse(text).orElseThrow());
        return new Rule(DEFAULT_DENY_RULE_LABEL, Decision.DENY,
                Tuple.of(HostPattern.class), Tuple.of(PortRange.class), ranges);
    }
}
