package dev.oillamp;

import sprouts.Tuple;

/// The `[network]` section of the configuration: which outbound connections the egress proxy
/// allows. [Policy#decide] applies it.
///
/// It lives in `oillamp.toml`, outside the agent directory, so the agent cannot change it.
record NetworkPolicy(Decision defaultDecision, Tuple<Rule> rules,
                            boolean logAllowed, boolean consoleDenied) {

    /// The default policy: allow the internet, deny private networks and the host's own loopback.
    /// Users add allow rules for internal services above the deny rule.
    public static NetworkPolicy shippedDefault() {
        return new NetworkPolicy(Decision.ALLOW, Tuple.of(Rule.class, blockPrivateRanges()), true, true);
    }

    public static final String DEFAULT_DENY_RULE_LABEL = "block private, internal and loopback ranges";

    public static Rule blockPrivateRanges() {
        Tuple<Cidr> ranges = Tuple.of(Cidr.class);
        for (String text : new String[] {
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                "::1/128", "fc00::/7", "fe80::/10" })
            ranges = ranges.add(Cidr.parse(text).orElseThrow());
        return new Rule(DEFAULT_DENY_RULE_LABEL, Decision.DENY,
                Tuple.of(HostPattern.class), Tuple.of(PortRange.class), ranges);
    }
}
