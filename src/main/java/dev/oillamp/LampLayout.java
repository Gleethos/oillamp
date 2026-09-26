package dev.oillamp;

import java.nio.file.Path;

/// Every path belonging to a lamp, computed from the lamp's root directory, its [AgentId] and
/// the host's runtime directory (`$XDG_RUNTIME_DIR`). The layout is described in
/// `docs/ARCHITECTURE.md`, "The lamp directory on disk".
///
/// Build lamp paths with these methods rather than by joining strings. Keeping them in one place
/// keeps the two layers apart: the agent's world is [#agentDir()], and the keys, logs,
/// recordings and the network policy are beside it, out of the agent's reach.
///
/// The paths fall into two places with different lifetimes:
///
/// - **The lamp**, under [#root()]: on disk until `oillamp remove`. [#agentDir()] is mounted as
///   `/home/agent`; of the state directory, only [#sessionDir()], the three socket directories
///   and [#recordingsDir()] are mounted, each on its own.
/// - **The runtime directory**, [#runtimeDir()], `$XDG_RUNTIME_DIR/oillamp/<agent id>/`,
///   usually under `/run/user/<uid>`: in memory, emptied at reboot and made again by the lamp
///   phase at every start. It holds [#runDir()], with the host-only sockets
///   ([#controlSocket()] and the two SSH relay sockets), which are outside the lamp so that
///   nothing mounted into the container can reach them. And it holds [#shortSockets()], a symlink
///   to [#socketsDir()] in the lamp.
///
/// The host addresses every socket through the runtime directory, never through the lamp,
/// because Unix socket paths are limited to 107 bytes and lamp paths can be longer. That is why
/// [#vncSocket()], [#agentSshSocket()] and [#proxySocket()] start with [#shortSockets()], although
/// the files are in the lamp.
record LampLayout(Path root, AgentId agentId, Path xdgRuntimeDir) {

    public LampLayout {
        if (!root.isAbsolute())
            throw new IllegalArgumentException("A lamp layout needs an absolute root, got: " + root);
        if (!xdgRuntimeDir.isAbsolute())
            throw new IllegalArgumentException("XDG_RUNTIME_DIR must be absolute, got: " + xdgRuntimeDir);
    }

    /// Paths that do not depend on the agent id, so they can be found from the root alone.
    ///
    /// `oillamp remove` needs them for a lamp someone already tried to delete with
    /// `rm -rf`: that removes `lamp.json`, so the agent id is lost, and then stops at the
    /// files it has no permission to delete.
    static Path stateDirOf(Path root) { return root.resolve(".oillamp"); }
    static Path configOf(Path root)   { return root.resolve("oillamp.toml"); }
    static Path readmeOf(Path root)   { return root.resolve("README.txt"); }
    /// The prefix of every agent directory's name, so it can be found in a lamp without `lamp.json`.
    static final String AGENT_DIR_PREFIX = "agent-lamp-";

    /// The lamp's human name — the directory name the user thinks in.
    public String name() {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }

    // ─── the user's layer ──────────────────────────────────────────────────────────────────

    /// The configuration file. It is outside the agent directory so the agent cannot change its own policy.
    public Path config()        { return configOf(root); }
    public Path readme()        { return readmeOf(root); }

    // ─── the state directory (mode 0700; only some parts are mounted into the container) ──

    public Path stateDir()      { return stateDirOf(root); }
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

    /// Rendered fresh each session and mounted read-only at `/oillamp/session`.
    public Path sessionDir()       { return stateDir().resolve("session"); }
    public Path runtimeEnvFile()   { return sessionDir().resolve("runtime.env"); }
    public Path authorizedKeys()   { return sessionDir().resolve("authorized_keys"); }
    public Path sshdHostKey()      { return sessionDir().resolve("ssh_host_ed25519_key"); }
    public Path agentGuide()       { return sessionDir().resolve("agent-guide.md"); }

    public Path imageDir()      { return stateDir().resolve("image"); }
    public Path imageContext()  { return imageDir().resolve("context"); }

    /// `host/` and `agent/` belong to the user, `infra/` to the infra user. Each of the three is
    /// mounted on its own under `/oillamp/sockets`; this parent is never mounted, because the
    /// agent runs as the user who owns it and could rearrange what is inside.
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

    // ─── the agent directory (mounted into the container as /home/agent) ───────────────────

    public Path agentDir()   { return root.resolve(AGENT_DIR_PREFIX + agentId.value()); }
    public Path workspace()  { return agentDir().resolve("workspace"); }
    public Path libs()       { return agentDir().resolve("libs"); }
    public Path screenshots(){ return agentDir().resolve("screenshots"); }
    public Path agentsMd()   { return agentDir().resolve("AGENTS.md"); }
    /// The agent's own `~/.bashrc`, written once and then left alone.
    ///
    /// It exists because `/etc/profile` is read by _login_ shells only. An agent
    /// that drives this sandbox with `ssh <lamp> 'some command'` gets a non-login shell,
    /// which would otherwise start with no proxy variables, no `DISPLAY` and no `sdk`.
    /// Bash reads this file in exactly that case, and in every interactive non-login shell.
    public Path agentBashrc(){ return agentDir().resolve(".bashrc"); }

    // ─── the short runtime directory: host-only sockets, and a symlink to socketsDir ────────

    public Path runtimeDir()        { return xdgRuntimeDir.resolve("oillamp").resolve(agentId.value()); }
    /// Symlink to [#socketsDir()], so socket paths stay under the 107-byte limit.
    public Path shortSockets()      { return runtimeDir().resolve("sockets"); }
    public Path vncSocket()         { return shortSockets().resolve("infra").resolve("vnc.sock"); }
    public Path agentSshSocket()    { return shortSockets().resolve("agent").resolve("ssh.sock"); }
    public Path proxySocket()       { return shortSockets().resolve("host").resolve("proxy.sock"); }
    public Path forwardSocket(String name) {
        return shortSockets().resolve("host").resolve("fwd-" + name + ".sock");
    }

    /// Host-only sockets. The container never sees this directory, so the agent cannot reach them.
    public Path runDir()            { return runtimeDir().resolve("run"); }
    public Path controlSocket()     { return runDir().resolve("control.sock"); }
    /// Accepts exactly one connection per session: the terminal window oillamp opened.
    public Path primarySshSocket()  { return runDir().resolve("ssh-primary.sock"); }
    public Path extraSshSocket()    { return runDir().resolve("ssh.sock"); }

    // ─── names derived from the agent id ───────────────────────────────────────────────────

    public ContainerName containerName() { return ContainerName.of(agentId); }
    public String containerHostname()    { return "lamp-" + agentId.value(); }
    /// The `Host` stanza in the generated ssh_config, and the pinned entry in known_hosts.
    public String sshHostAlias()         { return "lamp-" + agentId.value(); }
}
