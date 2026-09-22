package dev.oillamp;

import sprouts.Tuple;

/**
 * One line of the egress policy — spec §18.3.
 *
 * <p>All criteria present in a rule must match (AND); a rule with no criteria matches everything.
 * Rules are evaluated top to bottom and the first match wins, which is why the shipped default
 * config tells users to put allow-exceptions <em>above</em> the deny rule (§20.2).
 *
 * <p>The {@code label} is required because it is what the user sees: it appears in the network
 * log and in the {@code 403} body the agent receives, so the agent can report <em>why</em>
 * something failed instead of guessing (§18.2).
 */
record Rule(String label, Decision action,
                   Tuple<HostPattern> hosts, Tuple<PortRange> ports, Tuple<Cidr> cidrs) {

    public Rule {
        if (label.isBlank())
            throw new IllegalArgumentException("Every network rule needs a label — it is what denials quote");
    }

    public boolean matchesEverything() { return hosts.isEmpty() && ports.isEmpty() && cidrs.isEmpty(); }
}
