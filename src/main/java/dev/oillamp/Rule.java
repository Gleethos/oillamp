package dev.oillamp;

import sprouts.Tuple;

/// One `[[network.rules]]` entry of the network policy.
///
/// Every criterion the rule lists must match. A criterion it does not list is ignored, and a
/// rule with no criteria matches everything. Rules are checked from top to bottom and the first
/// match wins, which is why allow rules for internal services go above the shipped deny rule.
///
/// The `label` is required because users see it: it appears in the network log and in
/// the body of the `403` the agent receives, so the agent can report why a connection was
/// refused.
record Rule(String label, Decision action,
                   Tuple<HostPattern> hosts, Tuple<PortRange> ports, Tuple<Cidr> cidrs) {

    public Rule {
        if (label.isBlank())
            throw new IllegalArgumentException("Every network rule needs a label — it is what denials quote");
    }
}
