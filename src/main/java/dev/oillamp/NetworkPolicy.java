package dev.oillamp;

import sprouts.Tuple;

/**
 * The egress policy for one lamp — spec §18.3/§18.4.
 *
 * <p>Lives in {@code oillamp.toml}, which sits outside the agent directory precisely so that the
 * agent cannot rewrite its own jail (D-10).
 *
 * <p>Deliberately <b>package-private</b>: first-match-wins evaluation over the rules. Public would
 * commit us to that evaluation model forever; the rule syntax in {@code oillamp.toml} is the
 * commitment instead.
 */
record NetworkPolicy(Decision defaultDecision, Tuple<Rule> rules,
                            boolean logAllowed, boolean consoleDenied) {

    /**
     * The shipped default (§18.4): the open web, minus everything that would reach the intranet
     * or the host's own loopback. Users add allow-rules for internal services above this one.
     */
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
