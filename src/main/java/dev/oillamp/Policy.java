package dev.oillamp;

import java.util.Optional;

import sprouts.Tuple;

/**
 * Decides whether the egress proxy may make one connection.
 *
 * <p>This is the whole decision, as a pure function. {@link Egress} resolves names and moves
 * bytes; this class only looks at values, so tests can check every case without a network.
 *
 * <p>The decision is made for each <em>resolved address</em>, not for the host name. Whoever
 * controls a domain's DNS can point any name at {@code 127.0.0.1} or at a private network. Because
 * every address is checked against the rules' address ranges, the default deny rule catches that
 * whatever the name is. This is what makes "allow the internet by default" safe for the host.
 */
final class Policy {

    private Policy() {}

    /**
     * What the policy said, and which rule said it.
     *
     * @param rule the label of the deciding rule, or {@code (default)} when no rule matched. It is
     *             quoted in the 403 body and the network log, so the agent can report why a
     *             connection failed
     */
    record Verdict(Decision decision, String rule, Optional<IpAddress> address) {

        public boolean allowed() { return decision == Decision.ALLOW; }

        /** What the agent is told, in the one sentence it will paste into its report. */
        public String explain(String host, int port) {
            return "oillamp: connection to " + host + ":" + port + " denied by rule \""
                 + rule + "\" in oillamp.toml";
        }
    }

    /** The label used when no rule matched and {@code network.default} decided. */
    static final String NO_RULE = "(default)";

    /**
     * Decides a connection to {@code host:port}, given the addresses the host name resolved to.
     *
     * <p>Each address is checked in the order the resolver returned them. The first allowed address
     * is the one to connect to. If none is allowed, the verdict for the <em>first</em> address is
     * returned, so the agent is told the rule that applies to the address it would normally have
     * used.
     */
    static Verdict decide(NetworkPolicy policy, String host, int port, Tuple<IpAddress> candidates) {
        if (candidates.isEmpty())
            return new Verdict(policy.defaultDecision(), NO_RULE, Optional.empty());
        Verdict first = decideOne(policy, host, port, candidates.first());
        if (first.allowed()) return first;
        for (int i = 1; i < candidates.size(); i++) {
            Verdict verdict = decideOne(policy, host, port, candidates.get(i));
            if (verdict.allowed()) return verdict;
        }
        return first;
    }

    private static Verdict decideOne(NetworkPolicy policy, String host, int port, IpAddress address) {
        for (Rule rule : policy.rules())
            if (matches(rule, host, port, address))
                return new Verdict(rule.action(), rule.label(), Optional.of(address));
        return new Verdict(policy.defaultDecision(), NO_RULE, Optional.of(address));
    }

    /**
     * Whether a rule matches: every criterion the rule lists must match, and a criterion it does
     * not list is ignored. So a rule with only {@code cidrs}, like the default deny rule, applies to
     * every host name and port.
     */
    private static boolean matches(Rule rule, String host, int port, IpAddress address) {
        if (!rule.hosts().isEmpty() && rule.hosts().stream().noneMatch(p -> p.matches(host)))
            return false;
        if (!rule.ports().isEmpty() && rule.ports().stream().noneMatch(r -> r.contains(port)))
            return false;
        if (!rule.cidrs().isEmpty() && rule.cidrs().stream().noneMatch(c -> c.contains(address)))
            return false;
        return true;
    }
}
