package dev.oillamp;

import java.util.Optional;

import sprouts.Tuple;

/**
 * Whether one connection may be made — spec §18.3.
 *
 * <p>Pure, and deliberately the whole of the decision. The proxy resolves names and moves bytes;
 * this decides, from values alone, and can therefore be asked every awkward question in a
 * scenario without a socket anywhere near it.
 *
 * <p>The rule that gives the model its teeth is that a decision is made <em>per resolved
 * address</em>, not per host name. A name is a claim the other side controls: an attacker who can
 * publish DNS can point any public-looking name at {@code 127.0.0.1} or at the machine's own
 * intranet. Since every candidate address is checked against the CIDR rules, the shipped deny
 * rule catches that no matter what the name says — which is what makes "allow the open web" a
 * safe default rather than a hopeful one.
 *
 * <p>Deliberately <b>package-private</b>: the evaluation model of §18.3. The rule syntax in
 * {@code oillamp.toml} is the promise; first-match-wins is an implementation of it.
 */
final class Policy {

    private Policy() {}

    /**
     * What the policy said, and which rule said it.
     *
     * @param rule the label of the deciding rule, or {@code (default)} when nothing matched —
     *             quoted verbatim in the 403 body and in the network log, so the agent can report
     *             why a connection failed instead of guessing at it
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
     * The address to connect to, or why not.
     *
     * <p>Follows §18.3 exactly: every candidate is evaluated in resolver order, the first one
     * that is allowed is the one to use, and if none is, the denial reported is the one belonging
     * to the <em>first</em> candidate. Reporting the first rather than the last matters for the
     * message the agent reads — for a name that resolves to several private addresses, "denied by
     * the private-ranges rule" is the truth, while the verdict of some later address would be an
     * arbitrary pick among equals.
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
     * All criteria present must match; a rule with none matches everything (§18.3).
     *
     * <p>The asymmetry is deliberate and is the reason this is not a fold over three booleans: an
     * <em>absent</em> criterion is "don't care", while a <em>present but unmatched</em> one makes
     * the whole rule miss. A rule listing only {@code cidrs} must therefore apply to every host
     * name and port — which is exactly what the shipped deny rule relies on.
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
