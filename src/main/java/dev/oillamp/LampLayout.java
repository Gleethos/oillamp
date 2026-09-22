package dev.oillamp;

import java.nio.file.Path;

/**
 * Every path a lamp owns, derived purely from its root, its {@link AgentId} and the host's
 * runtime directory — spec §9.1.
 *
 * <p>Nothing else in oillamp is allowed to build a lamp path by concatenating strings. Having
 * exactly one function per path is what keeps the two-layer split of D-10 true: the agent's
 * world is {@link #agentDir()} and nothing else, while keys, logs, recordings and — critically —
 * the network policy live beside it in {@link #stateDir()}, out of the agent's reach.
 *
 * <p>Host-side socket paths go through {@link #runtimeDir()} rather than the lamp itself, because
 * Unix socket paths are capped at 107 bytes and lamp paths can be long (D-25).
 *
 * <p>Deliberately <b>package-private</b>: the layout of §9.1 is a promise about the
 * <em>directory</em>. It is not a promise about this class, which is simply where that layout is
 * written down once.
 */
record LampLayout(Path root, AgentId agentId, Path xdgRuntimeDir) {

    public LampLayout {
        if (!root.isAbsolute())
            throw new IllegalArgumentException("A lamp layout needs an absolute root, got: " + root);
        if (!xdgRuntimeDir.isAbsolute())
            throw new IllegalArgumentException("XDG_RUNTIME_DIR must be absolute, got: " + xdgRuntimeDir);
    }

    /** The lamp's human name — the directory name the user thinks in. */
    public String name() {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }

    // ─── the user's layer ──────────────────────────────────────────────────────────────────

    /** The policy file. Deliberately outside the agent dir: the agent cannot rewrite its own jail. */
    public Path config()        { return root.resolve("oillamp.toml"); }
    public Path readme()        { return root.resolve("README.txt"); }

    // ─── the state layer (mode 0700, never mounted as a whole) ─────────────────────────────

    public Path stateDir()      { return root.resolve(".oillamp"); }
    public Path lampMeta()      { return stateDir().resolve("lamp.json"); }
    public Path lockFile()      { return stateDir().resolve("lock"); }
    public Path sessionMeta()   { return stateDir().resolve("session.json"); }

    public Path keysDir()       { return stateDir().resolve("keys"); }
    public Path clientKey()     { return keysDir().resolve("client_ed25519"); }
    public Path clientKeyPub()  { return keysDir().resolve("client_ed25519.pub"); }
    public Path hostKey()       { return keysDir().resolve("host_ed25519"); }
    public Path hostKeyPub()    { return keysDir().resolve("host_ed25519.pub"); }

    public Path sshConfig()     { return stateDir().resolve("ssh_config"); }
    public Path knownHosts()    { return stateDir().resolve("known_hosts"); }

    /** Rendered fresh each session and mounted read-only at {@code /oillamp/session}. */
    public Path sessionDir()       { return stateDir().resolve("session"); }
    public Path runtimeEnvFile()   { return sessionDir().resolve("runtime.env"); }
    public Path authorizedKeys()   { return sessionDir().resolve("authorized_keys"); }
    public Path sshdHostKey()      { return sessionDir().resolve("ssh_host_ed25519_key"); }
    public Path agentGuide()       { return sessionDir().resolve("agent-guide.md"); }

    public Path imageDir()      { return stateDir().resolve("image"); }
    public Path imageContext()  { return imageDir().resolve("context"); }

    /** Mounted read-write at {@code /oillamp/sockets}; the three sub-directories have different owners. */
    public Path socketsDir()       { return stateDir().resolve("sockets"); }
    public Path hostSocketsDir()   { return socketsDir().resolve("host"); }
    public Path infraSocketsDir()  { return socketsDir().resolve("infra"); }
    public Path agentSocketsDir()  { return socketsDir().resolve("agent"); }
    public Path readyFile()        { return infraSocketsDir().resolve("ready.json"); }

    public Path recordingsDir() { return stateDir().resolve("recordings"); }
    public Path recording(SessionId session) { return recordingsDir().resolve(session.value() + ".mkv"); }

    public Path logsDir()       { return stateDir().resolve("logs"); }
    public Path sessionLog(SessionId s)   { return logsDir().resolve("oillamp-" + s.value() + ".log"); }
    public Path containerLog(SessionId s) { return logsDir().resolve("container-" + s.value() + ".log"); }
    public Path networkLog(SessionId s)   { return logsDir().resolve("network-" + s.value() + ".jsonl"); }
    public Path installLog(String stamp)  { return logsDir().resolve("install-" + stamp + ".log"); }

    // ─── the agent's layer (the only part mounted into the container as a home) ─────────────

    public Path agentDir()   { return root.resolve("agent-lamp-" + agentId.value()); }
    public Path workspace()  { return agentDir().resolve("workspace"); }
    public Path libs()       { return agentDir().resolve("libs"); }
    public Path screenshots(){ return agentDir().resolve("screenshots"); }
    public Path agentsMd()   { return agentDir().resolve("AGENTS.md"); }

    // ─── the short runtime dir (D-25): host-only sockets and the symlink to socketsDir ──────

    public Path runtimeDir()        { return xdgRuntimeDir.resolve("oillamp").resolve(agentId.value()); }
    /** Symlink to {@link #socketsDir()}, so socket paths stay under the 107-byte limit. */
    public Path shortSockets()      { return runtimeDir().resolve("sockets"); }
    public Path vncSocket()         { return shortSockets().resolve("infra").resolve("vnc.sock"); }
    public Path agentSshSocket()    { return shortSockets().resolve("agent").resolve("ssh.sock"); }
    public Path proxySocket()       { return shortSockets().resolve("host").resolve("proxy.sock"); }
    public Path forwardSocket(String name) {
        return shortSockets().resolve("host").resolve("fwd-" + name + ".sock");
    }

    /** Host-only sockets. The container never sees this directory, so the agent cannot reach them. */
    public Path runDir()            { return runtimeDir().resolve("run"); }
    public Path controlSocket()     { return runDir().resolve("control.sock"); }
    /** Accepts exactly one connection per session: the terminal window oillamp opened (D-09). */
    public Path primarySshSocket()  { return runDir().resolve("ssh-primary.sock"); }
    public Path extraSshSocket()    { return runDir().resolve("ssh.sock"); }

    // ─── derived names (spec §10.2) ────────────────────────────────────────────────────────

    public ContainerName containerName() { return ContainerName.of(agentId); }
    public String containerHostname()    { return "lamp-" + agentId.value(); }
    /** The {@code Host} stanza in the generated ssh_config, and the pinned entry in known_hosts. */
    public String sshHostAlias()         { return "lamp-" + agentId.value(); }
}
