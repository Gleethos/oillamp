package dev.oillamp;


/**
 * A fixed tunnel from inside the sandbox to one host-reachable endpoint — spec §18.5 (D-13).
 *
 * <p>Forwards are the deliberate, visible exception to the egress policy: the agent's tools
 * expect a plain base URL for the company LLM, and routing that through an HTTP proxy policy
 * would be both fragile and pointless. Because they bypass the policy, they are named, listed
 * in the agent guide, and logged like everything else.
 */
record Forward(String name, int port, HostAndPort target) {

    /** The in-container port the egress proxy occupies — a forward may not take it (§18.5). */
    public static final int PROXY_PORT = 3128;

    public Forward {
        if (!name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException("A forward name is lowercase letters, digits and dashes: " + name);
        if (port < 1024 || port > 65535)
            throw new IllegalArgumentException("A forward port must be in 1024-65535, got: " + port);
        if (port == PROXY_PORT)
            throw new IllegalArgumentException("Port " + PROXY_PORT + " is the egress proxy's");
    }

    /** {@code fwd-<name>.sock} in the lamp's host socket directory. */
    public String socketFileName() { return "fwd-" + name + ".sock"; }

    /** What the agent's tools put in a base URL. */
    public String inContainerUrl(String path) { return "http://127.0.0.1:" + port + path; }
}
