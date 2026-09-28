package dev.oillamp;


/// One `[[network.forwards]]` entry: a fixed tunnel from `127.0.0.1:<port>` inside the
/// sandbox to one address the host can reach, such as a company LLM service.
///
/// Forwards bypass the network policy on purpose. Agent tools expect a plain base URL, and the
/// target is one the user chose. Because they bypass the policy, they are named in the
/// configuration, listed in the agent guide and logged like proxy connections.
record Forward(String name, int port, HostAndPort target) {

    /// The port the egress proxy listens on inside the sandbox. A forward may not use it.
    public static final int PROXY_PORT = 3128;
    /// The port inside the sandbox where the harnesses reach the model service, through oillamp.
    public static final int MODEL_PORT = 3129;

    public Forward {
        if (!name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException("A forward name is lowercase letters, digits and dashes: " + name);
        if (port < 1024 || port > 65535)
            throw new IllegalArgumentException("A forward port must be in 1024-65535, got: " + port);
        if (port == PROXY_PORT)
            throw new IllegalArgumentException("Port " + PROXY_PORT + " is the egress proxy's");
        if (port == MODEL_PORT)
            throw new IllegalArgumentException("Port " + MODEL_PORT + " is the model relay's");
    }

    /// `fwd-<name>.sock` in the lamp's host socket directory.
    public String socketFileName() { return "fwd-" + name + ".sock"; }

    /// What the agent's tools put in a base URL.
    public String inContainerUrl(String path) { return "http://127.0.0.1:" + port + path; }
}
