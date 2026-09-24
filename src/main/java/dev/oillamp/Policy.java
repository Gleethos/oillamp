package dev.oillamp;

import java.util.Optional;

import sprouts.Tuple;

/// Decides whether the egress proxy may make one connection.
///
/// This is the whole decision, as a pure function. [Egress] resolves names and moves
/// bytes; this class only looks at values, so tests can check every case without a network.
///
/// The decision is made for each _resolved address_, not for the host name. Whoever
/// controls a domain's DNS can point any name at `127.0.0.1` or at a private network. Because
/// every address is checked against the rules' address ranges, the default deny rule catches that
/// whatever the name is. This is what makes "allow the internet by default" safe for the host.
final class Policy {

    private Policy() {}

    /// What the policy said, and which rule said it.
    ///
    /// @param rule the label of the deciding rule, or `(default)` when no rule matched. It is
    ///             quoted in the 403 body and the network log, so the agent can report why a
    ///             connection failed
    record Verdict(Decision decision, String rule, Optional<IpAddress> address) {

        public boolean allowed() { return decision == Decision.ALLOW; }

        /// What the agent is told, in the one sentence it will paste into its report.
        public String explain(String host, int port) {
            if (rule.equals(UNSPECIFIED))
                return "oillamp: connection to " + host + ":" + port + " denied: it is "
                     + UNSPECIFIED + ", and no rule can allow it";
            return "oillamp: connection to " + host + ":" + port + " denied by rule \""
                 + rule + "\" in oillamp.toml";
        }
    }

    /// The label used when no rule matched and `network.default` decided.
    static final String NO_RULE = "(default)";

    /// Decides a connection to `host:port`, given the addresses the host name resolved to.
    ///
    /// Each address is checked in the order the resolver returned them. The first allowed address
    /// is the one to connect to. If none is allowed, the verdict for the _first_ address is
    /// returned, so the agent is told the rule that applies to the address it would normally have
    /// used.
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

    /// The label used when the address is `0.0.0.0` or `::`, which no rule can allow.
    static final String UNSPECIFIED = "the unspecified address, which means this machine";

    private static Verdict decideOne(NetworkPolicy policy, String host, int port, IpAddress address) {
        // Linux connects to the unspecified address as if it were this machine. It is never a
        // destination on the internet, so it is refused whatever the configuration says. A lamp
        // whose oillamp.toml was written before this address was added to the shipped deny rule
        // is protected as well.
        if (address.isUnspecified())
            return new Verdict(Decision.DENY, UNSPECIFIED, Optional.of(address));
        for (Rule rule : policy.rules())
            if (matches(rule, host, port, address))
                return new Verdict(rule.action(), rule.label(), Optional.of(address));
        return new Verdict(policy.defaultDecision(), NO_RULE, Optional.of(address));
    }

    /// Whether a rule matches: every criterion the rule lists must match, and a criterion it does
    /// not list is ignored. So a rule with only `cidrs`, like the default deny rule, applies to
    /// every host name and port.
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
