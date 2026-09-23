package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import sprouts.Tuple;

/**
 * A machine described rather than owned — the seam that lets scenarios run the real command-line
 * entry point against "Ubuntu without podman" or "Fedora" or "no display".
 *
 * <p>It answers with genuine command output — real {@code /etc/os-release} text, real
 * {@code podman info} JSON, real {@code dpkg-query} lines — so the production parsers are what
 * runs. A simulation that returned pre-parsed facts would quietly stop testing the half of the
 * code most likely to break when a tool changes its output.
 *
 * <p>Deliberately <b>package-private</b>: reached only through {@link Machine#simulated()}. The
 * builder on {@code Machine.Simulation} is the API a scenario writes against; this is what it
 * constructs.
 */
final class SimulatedMachine implements Machine {

    /** How {@code sudo} behaves on the simulated machine. */
    public enum Sudo { PASSWORDLESS, NEEDS_PASSWORD, UNAVAILABLE }

    private final String operatingSystemName;
    private final Map<String, String> systemFiles;
    private final Map<String, String> environment;
    private final Map<String, Outcome> scriptedCommands;
    private final Map<String, Path> executables;
    private final Map<String, List<Path>> systemDirectories;
    private final Instant clock;
    private final boolean clockRuns;
    /** Wall-clock reference for a running clock, taken once so that {@code now()} stays monotonic. */
    private final Instant built = Instant.now();
    private final String randomToken;
    private final boolean interactive;
    private final java.util.Set<String> passThrough;
    private final java.util.Set<String> deadEndpoints;
    private final java.util.Set<String> refusedWindows;
    private final Duration terminalStaysOpen;
    private final boolean terminalConnects;
    private final RealMachine realMachine = new RealMachine();

    /**
     * The sockets the simulated container is listening on, and whether it is still up.
     *
     * <p>Real sockets, not files. The relays of §17.3 are the part of a session that has no pure
     * core to test — they bind, accept and copy bytes — so a simulation that answered "a socket
     * is a file that exists" would leave the one class the session's lifetime depends on with no
     * scenario over it at all. Binding for real costs a few lines here and makes every session
     * scenario an actual test of the relay.
     */
    private final java.util.Map<String, java.nio.channels.ServerSocketChannel> listening =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean containerRunning;

    private SimulatedMachine(Builder builder) {
        this.passThrough = java.util.Set.copyOf(builder.passThrough);
        this.deadEndpoints = java.util.Set.copyOf(builder.deadEndpoints);
        this.refusedWindows = java.util.Set.copyOf(builder.refusedWindows);
        this.terminalStaysOpen = builder.terminalStaysOpen;
        this.terminalConnects = builder.terminalConnects;
        this.operatingSystemName = builder.operatingSystemName;
        this.systemFiles = Map.copyOf(builder.systemFiles);
        this.environment = Map.copyOf(builder.environment);
        this.scriptedCommands = new LinkedHashMap<>(builder.scriptedCommands);
        this.executables = Map.copyOf(builder.executables);
        this.systemDirectories = Map.copyOf(builder.systemDirectories);
        this.clock = builder.clock;
        this.clockRuns = builder.clockRuns;
        this.randomToken = builder.randomToken;
        this.interactive = builder.interactive;
    }

    /**
     * A clock that stands still, unless a scenario asked for one that moves.
     *
     * <p>Standing still is what makes session ids, file names and retention decisions repeatable.
     * But a supervisor is the one part of oillamp whose job includes waiting, and a timeout that
     * can never be reached cannot be tested at all — so a scenario about waiting can ask for time
     * to pass, and only those scenarios pay for it.
     */
    @Override public Instant now() {
        return clockRuns ? clock.plus(Duration.between(built, Instant.now())) : clock;
    }

    @Override public String operatingSystemName() { return operatingSystemName; }

    @Override public Optional<String> environmentVariable(String name) {
        return Optional.ofNullable(environment.get(name)).filter(value -> !value.isEmpty());
    }

    @Override public String randomToken(int length) {
        String value = randomToken;
        while (value.length() < length) value += value;
        return value.substring(0, length);
    }

    @Override public boolean isInteractive() { return interactive; }

    @Override public Outcome run(Command command) {
        if (passThrough.contains(command.executable())) return realMachine.run(command);
        String commandLine = command.commandLine();
        // A scenario that scripts a command outranks the built-in simulation of it. Without this
        // the behaviours modelled below could not be made to fail, and a scenario that cannot
        // fail is not evidence of anything.
        for (Map.Entry<String, Outcome> scripted : scriptedCommands.entrySet())
            if (commandLine.startsWith(scripted.getKey())) return scripted.getValue();
        if (commandLine.startsWith("podman run ")) return startSimulatedSandbox(command);
        if (commandLine.startsWith("podman stop ") || commandLine.startsWith("podman rm "))
            return stopSimulatedSandbox();
        if (commandLine.startsWith("podman container inspect")) return inspectSimulatedSandbox(command);
        if (commandLine.startsWith("podman unshare rm ")) return simulatedUnshareRemove(command);
        if (commandLine.contains("UNIX-CONNECT:")) return simulatedConnect(command);
        if (!executables.containsKey(command.executable()))
            return new Outcome.NotFound(command.executable());
        return new Outcome.Finished(0, "", "", Duration.ofMillis(1));
    }

    /**
     * Models what the <em>container</em> does on startup, not what oillamp does.
     *
     * <p>The real entrypoint starts sway, wayvnc and the ssh listener and then writes
     * {@code ready.json} into the mounted sockets directory (§16). Nothing else tells the host it
     * is ready, so a simulation that skipped this would leave every {@code oillamp at} scenario
     * waiting for a file that never arrives.
     *
     * <p>Note where the path comes from: the {@code --volume} argument oillamp actually passed. A
     * simulation that wrote to a path of its own choosing would keep working if oillamp forgot the
     * mount; this one fails exactly as the real sandbox would, because the container would have
     * nowhere to write either.
     *
     * <p>It deliberately does not simulate the desktop, the VNC server or the recording. Those are
     * what the spikes of §33 are for — a simulation can only ever replay what we already believe,
     * and believing sway works is not the same as knowing it.
     */
    private Outcome startSimulatedSandbox(Command command) {
        Optional<Path> sockets = mountedHostPath(command, "/oillamp/sockets");
        if (sockets.isEmpty())
            return new Outcome.Finished(125, "",
                    "Error: the sandbox has nowhere to report readiness — "
                  + "no --volume was mounted at /oillamp/sockets\n", Duration.ofMillis(30));
        // Same reasoning as the sockets mount: the session id comes from the runtime.env the host
        // actually wrote, so a host that stops writing it fails here exactly as the real
        // entrypoint does — it refuses to start without OILLAMP_SESSION.
        Optional<String> session = simulatedSessionId(command);
        if (session.isEmpty())
            return new Outcome.Finished(70, "",
                    "[entrypoint] runtime.env is missing OILLAMP_SESSION\n", Duration.ofMillis(30));
        try {
            Path infra = sockets.get().resolve("infra");
            java.nio.file.Files.createDirectories(infra);
            java.nio.file.Files.createDirectories(sockets.get().resolve("agent"));
            // Bound through the short path of D-25, not through the lamp. A lamp lives where the
            // user keeps their projects and its path is easily over the kernel's 107-byte cap on
            // a socket address, which is the entire reason the runtime directory and its symlink
            // exist. Binding here the way the container does means a broken symlink fails in the
            // simulation exactly as it would on a desktop.
            Path shortSockets = shortSocketsDirectory(command).orElse(sockets.get());
            // The sockets before the readiness file, in that order, because that is the promise
            // ready.json makes: both servers are already accepting connections.
            bind(shortSockets.resolve("infra").resolve("vnc.sock"));
            bind(shortSockets.resolve("agent").resolve("ssh.sock"));
            java.nio.file.Files.writeString(infra.resolve("ready.json"),
                    "{\"renderer\":\"pixman\",\"gpu_fallback\":false,\"simulated\":true,"
                  + "\"session\":\"" + session.get() + "\"}\n");
        } catch (java.io.IOException e) {
            return new Outcome.Finished(125, "", "Error: " + e.getMessage() + "\n", Duration.ofMillis(30));
        }
        containerRunning = true;
        return new Outcome.Finished(0, "simulated-container-id\n", "", Duration.ofMillis(120));
    }

    /**
     * {@code $XDG_RUNTIME_DIR/oillamp/<agentId>/sockets} — the short path the host connects on.
     *
     * <p>Taken from the container's own name rather than from a field, so that a simulation can
     * never bind somewhere the host would not look: if oillamp stopped passing {@code --name},
     * this would stop finding it, exactly as the host would.
     */
    private Optional<Path> shortSocketsDirectory(Command command) {
        Tuple<String> argv = command.argv();
        for (int i = 0; i < argv.size() - 1; i++) {
            if (!argv.get(i).equals("--name")) continue;
            String agentId = argv.get(i + 1).replaceFirst("^oillamp-", "");
            return Optional.ofNullable(environment.get("XDG_RUNTIME_DIR"))
                    .map(runtime -> Path.of(runtime, "oillamp", agentId, "sockets"));
        }
        return Optional.empty();
    }

    /**
     * Starts listening where the container's server would.
     *
     * <p>A socket named by {@link Simulation#endpointRefusingConnections} is created as an
     * ordinary file instead, and nothing binds it — which is precisely the failure that got this
     * simulation written: wayvnc could not take a path the previous session had left behind, so
     * the file was there and no server was.
     */
    private void bind(Path socket) throws java.io.IOException {
        Path fileName = socket.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        java.nio.file.Files.deleteIfExists(socket);
        if (deadEndpoints.contains(name)) {
            java.nio.file.Files.writeString(socket, "");
            return;
        }
        java.nio.channels.ServerSocketChannel server =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        server.bind(java.net.UnixDomainSocketAddress.of(socket));
        listening.put(socket.toString(), server);
        Thread.ofVirtual().name("simulated-sandbox-" + name).start(() -> accept(server));
    }

    /** A simulated server: it answers, reads whatever is sent, and goes away when the client does. */
    private static void accept(java.nio.channels.ServerSocketChannel server) {
        while (server.isOpen()) {
            try {
                java.nio.channels.SocketChannel client = server.accept();
                Thread.ofVirtual().start(() -> {
                    try (client) {
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4096);
                        while (client.read(buffer) >= 0) buffer.clear();
                    } catch (java.io.IOException closed) {
                        // The other end went away, which is the normal end of a connection.
                    }
                });
            } catch (java.io.IOException closed) {
                return;
            }
        }
    }

    /** {@code podman stop} and {@code podman rm}: the container and its sockets are gone. */
    private Outcome stopSimulatedSandbox() {
        containerRunning = false;
        for (java.nio.channels.ServerSocketChannel server : listening.values()) {
            try {
                server.close();
            } catch (java.io.IOException ignored) {
                // Closing a socket that is already closed is not a failure worth modelling.
            }
        }
        listening.clear();
        return new Outcome.Finished(0, "simulated-container-id\n", "", Duration.ofMillis(40));
    }

    /**
     * Answers the two questions the supervisor asks about a container it is watching.
     *
     * <p>Without this, a simulated {@code podman container inspect} would succeed with no output,
     * the supervisor would read that as "not running", and every simulated session would report
     * its sandbox as having died the moment it started.
     */
    private Outcome inspectSimulatedSandbox(Command command) {
        String format = "";
        Tuple<String> argv = command.argv();
        for (int i = 0; i < argv.size() - 1; i++)
            if (argv.get(i).equals("--format")) format = argv.get(i + 1);
        if (!containerRunning)
            return new Outcome.Finished(125, "",
                    "Error: no such container\n", Duration.ofMillis(5));
        String answer = format.contains("ExitCode") ? "0" : "true";
        return new Outcome.Finished(0, answer + "\n", "", Duration.ofMillis(5));
    }

    /**
     * Deletes for real, because the scenarios that matter here are about files that outlive a
     * container: a socket the previous session left behind, and a {@code ready.json} that answers
     * a question the host has not asked yet. A simulation that only pretended to delete them
     * would make every one of those scenarios pass while the bug stayed.
     */
    private Outcome simulatedUnshareRemove(Command command) {
        Tuple<String> argv = command.argv();
        // `-r` matters: `oillamp remove` passes whole directories, and a simulation that could
        // only unlink single files would send every removal down the fallback path instead of the
        // one the real command takes.
        boolean recursive = false;
        for (String argument : argv)
            if (argument.startsWith("-") && argument.contains("r")) recursive = true;
        for (int i = 3; i < argv.size(); i++) {
            String argument = argv.get(i);
            if (argument.startsWith("-")) continue;
            Path path = Path.of(argument);
            if (recursive) {
                Tuple<Path> survivors = Filesystem.deleteTree(path);
                if (!survivors.isEmpty())
                    return new Outcome.Finished(1, "",
                            "rm: cannot remove '" + survivors.first() + "': Permission denied\n",
                            Duration.ofMillis(5));
                continue;
            }
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (java.io.IOException e) {
                return new Outcome.Finished(1, "",
                        "rm: cannot remove '" + argument + "': " + e.getMessage() + "\n",
                        Duration.ofMillis(5));
            }
        }
        return new Outcome.Finished(0, "", "", Duration.ofMillis(20));
    }

    /**
     * Models connecting to a Unix socket — the check that tells a listening server apart from a
     * file with the right name (§16).
     *
     * <p>In simulation a socket "answers" when the file is there, which is enough to catch the
     * host forgetting to create one or clean one up. {@link Simulation#endpointRefusingConnections}
     * models the other case, where the file exists and nothing is behind it — the shape of the
     * failure that let a session with a dead VNC server report itself healthy.
     */
    private Outcome simulatedConnect(Command command) {
        Optional<Path> socket = unixConnectTarget(command);
        if (socket.isEmpty()) return new Outcome.Finished(0, "", "", Duration.ofMillis(5));
        // Really connect. The simulated container binds real sockets, so this is the same
        // question the host's check asks and the same one the user's viewer asks a second later:
        // is something listening, or is this only a file with the right name?
        try (java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open(
                java.net.UnixDomainSocketAddress.of(socket.get()))) {
            return channel.isConnected()
                    ? new Outcome.Finished(0, "", "", Duration.ofMillis(5))
                    : new Outcome.Finished(1, "", "socat: E connect: not connected\n", Duration.ofMillis(5));
        } catch (java.io.IOException refused) {
            return new Outcome.Finished(1, "",
                    "socat: E connect(, AF=1 \"" + socket.get() + "\"): "
                  + Problems.reason(refused) + "\n",
                    Duration.ofMillis(5));
        }
    }

    /**
     * The socket a command line is aimed at, wherever {@code UNIX-CONNECT:} appears in it.
     *
     * <p>It appears in two shapes, and both matter. The readiness check passes it as its own
     * argument ({@code socat -u /dev/null UNIX-CONNECT:/path}), while the terminal's ssh command
     * carries it inside one ({@code -o ProxyCommand=socat - UNIX-CONNECT:/path}) — which is how
     * a shell reaches the sandbox, and therefore the one a simulated session has to find.
     */
    private static Optional<Path> unixConnectTarget(Command command) {
        for (String argument : command.argv()) {
            int at = argument.indexOf("UNIX-CONNECT:");
            if (at < 0) continue;
            String rest = argument.substring(at + "UNIX-CONNECT:".length());
            int end = rest.indexOf(' ');
            String path = end < 0 ? rest : rest.substring(0, end);
            if (!path.isBlank()) return Optional.of(Path.of(path));
        }
        return Optional.empty();
    }

    /** The session id the host wrote into runtime.env, which the real entrypoint insists on. */
    private static Optional<String> simulatedSessionId(Command command) {
        Optional<Path> sessionDir = mountedHostPath(command, "/oillamp/session");
        if (sessionDir.isEmpty()) return Optional.empty();
        try {
            return java.nio.file.Files.readAllLines(sessionDir.get().resolve("runtime.env")).stream()
                    .filter(line -> line.startsWith("OILLAMP_SESSION="))
                    // The host writes shell quoting, because the entrypoint sources this file.
                    .map(line -> line.substring("OILLAMP_SESSION=".length()).strip()
                                     .replaceAll("^['\"]|['\"]$", ""))
                    .filter(value -> !value.isEmpty())
                    .findFirst();
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }

    /** The host side of {@code --volume <host>:<inContainer>}, if oillamp asked for that mount. */
    private static Optional<Path> mountedHostPath(Command command, String inContainer) {
        Tuple<String> argv = command.argv();
        for (int i = 0; i < argv.size() - 1; i++) {
            if (!argv.get(i).equals("--volume") && !argv.get(i).equals("-v")) continue;
            String mount = argv.get(i + 1);
            int separator = mount.indexOf(':');
            if (separator < 0) continue;
            String target = mount.substring(separator + 1);
            if (target.equals(inContainer) || target.startsWith(inContainer + ":"))
                return Optional.of(Path.of(mount.substring(0, separator)));
        }
        return Optional.empty();
    }

    /**
     * Opens a simulated window — and, if it is a shell, really connects it to the session.
     *
     * <p>This is where a simulated session stops being a mime. The terminal oillamp opens runs
     * {@code ssh} through {@code socat} to the primary relay socket, so the simulated window
     * looks for that socket in the argv it was given and connects to it for real. The whole of
     * §17.3 and §25.1 then runs as it would on a desktop: the relay accepts, the supervisor
     * reaches {@code Running}, and when the window closes a second later the session shuts down
     * because the terminal closed — which is the behaviour FR-06 promises and the one thing no
     * amount of unit testing can establish on its own.
     */
    @Override public Window launch(Command command, Window.Stdio stdio) {
        if (refusedWindows.contains(command.executable()))
            return Window.refused(command.executable(), "No such file or directory");
        if (!executables.containsKey(command.executable()))
            return Window.refused(command.executable(), "command not found");
        Optional<Path> socket = unixConnectTarget(command);
        return new SimulatedWindow(terminalConnects ? socket : Optional.empty(), terminalStaysOpen);
    }

    /** A window that is open for a while, holding a connection if it was given one to hold. */
    private static final class SimulatedWindow implements Window {

        private final java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        private volatile Optional<java.nio.channels.SocketChannel> connection = Optional.empty();

        private SimulatedWindow(Optional<Path> connectTo, Duration staysOpen) {
            Thread.ofVirtual().name("simulated-window").start(() -> {
                if (connectTo.isPresent()) {
                    try {
                        connection = Optional.of(java.nio.channels.SocketChannel.open(
                                java.net.UnixDomainSocketAddress.of(connectTo.get())));
                    } catch (java.io.IOException refused) {
                        // A window that cannot reach the session is one the user sees open and
                        // close again — which is exactly what the supervisor has to notice.
                    }
                }
                try {
                    Thread.sleep(staysOpen);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                close();
            });
        }

        @Override public long pid() { return 4242; }
        @Override public boolean isRunning() { return running.get(); }
        @Override public Optional<Integer> exitCode() {
            return running.get() ? Optional.empty() : Optional.of(0);
        }
        @Override public Optional<String> failure() { return Optional.empty(); }
        @Override public String output() { return ""; }
        @Override public int waitFor() {
            while (running.get()) {
                try {
                    Thread.sleep(Duration.ofMillis(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 130;
                }
            }
            return 0;
        }

        @Override public void close() {
            running.set(false);
            connection.ifPresent(channel -> {
                try {
                    channel.close();
                } catch (java.io.IOException ignored) {
                    // Already gone; the session sees the disconnection either way.
                }
            });
        }
    }

    @Override public Optional<String> readSystemFile(Path path) {
        return Optional.ofNullable(systemFiles.get(path.toString()));
    }

    @Override public Tuple<Path> listSystemDirectory(Path path) {
        List<Path> entries = systemDirectories.get(path.toString());
        return entries == null ? Tuple.of(Path.class) : Tuple.of(Path.class, entries);
    }

    @Override public Optional<Path> locateExecutable(String name) {
        return Optional.ofNullable(executables.get(name));
    }

    // ───────────────────────────────────────────────────────────────────────────────────────

    /** Assembles the canned answers. Driven by {@code Machine.Simulation}, which is the public face. */
    public static final class Builder {

        private String operatingSystemName = "Linux";
        private final Map<String, String> systemFiles = new TreeMap<>();
        private final Map<String, String> environment = new TreeMap<>();
        private final Map<String, Outcome> scriptedCommands = new LinkedHashMap<>();
        private final Map<String, Path> executables = new TreeMap<>();
        private final Map<String, List<Path>> systemDirectories = new TreeMap<>();
        private final List<String> installedPackages = new ArrayList<>();
        private final List<RenderNode> renderNodes = new ArrayList<>();
        private final List<String> foreignSubIds = new ArrayList<>();
        private final List<String> passThrough = new ArrayList<>();
        private final List<String> deadEndpoints = new ArrayList<>();
        private final List<String> refusedWindows = new ArrayList<>();

        /**
         * How long a simulated window stays open.
         *
         * <p>Long enough for the relay to accept the connection and the session to reach
         * {@code Running}, short enough that a scenario running a whole session finishes in the
         * time a scenario should. A real session ends when the user closes the window; a
         * simulated one ends when this elapses, and the path through the code is the same.
         */
        private Duration terminalStaysOpen = Duration.ofMillis(250);
        private boolean terminalConnects = true;

        private Instant clock = Instant.parse("2026-09-22T14:15:03Z");
        private boolean clockRuns = false;
        // Valid base32: the alphabet has no 0, 1, 8 or 9.
        private String randomToken = "k3v7x2ab";
        private boolean interactive = true;

        private String userName = "dev";
        private int uid = 1000;
        private int gid = 1000;
        private Path home = Path.of("/home/dev");
        private Tuple<String> groups = Tuple.of(String.class, "dev", "sudo");
        private int processors = 8;
        private String filesystemType = "ext2/ext3";

        private String osId = "ubuntu";
        private String osIdLike = "debian";
        private String osVersionId = "24.04";
        private String osPrettyName = "Ubuntu 24.04 LTS";

        private Optional<String> podmanVersion = Optional.empty();
        private String ociRuntime = "crun";
        private boolean rootless = true;
        private boolean usernsWorks = true;
        private boolean apparmorRestricted = false;

        private Optional<int[]> subIds = Optional.empty();
        private Sudo sudo = Sudo.PASSWORDLESS;

        private record RenderNode(String path, String group, String driver) {}

        public void operatingSystemName(String name) { this.operatingSystemName = name; }
        public void osRelease(String id, String idLike, String versionId, String prettyName) {
            this.osId = id; this.osIdLike = idLike; this.osVersionId = versionId; this.osPrettyName = prettyName;
        }
        public void user(String name, int uid, int gid, Path home) {
            this.userName = name; this.uid = uid; this.gid = gid; this.home = home;
        }
        public void groups(Tuple<String> groups) { this.groups = groups; }
        public void runtimeDirectory(Path path) { environment.put("XDG_RUNTIME_DIR", path.toString()); }
        public void processors(int count) { this.processors = count; }
        public void session(String waylandDisplay, String x11Display, String desktop) {
            environment.put("WAYLAND_DISPLAY", waylandDisplay);
            environment.put("DISPLAY", x11Display);
            environment.put("XDG_CURRENT_DESKTOP", desktop);
        }
        public void installPackages(Tuple<String> packages) {
            for (String pkg : packages) if (!installedPackages.contains(pkg)) installedPackages.add(pkg);
        }
        public void removePackages(Tuple<String> packages) {
            for (String pkg : packages) installedPackages.remove(pkg);
        }
        public void podman(String version, String ociRuntime, boolean rootless) {
            this.podmanVersion = Optional.of(version);
            this.ociRuntime = ociRuntime;
            this.rootless = rootless;
            installPackages(Tuple.of(String.class, "podman"));
        }
        public void noPodman() {
            this.podmanVersion = Optional.empty();
            removePackages(Tuple.of(String.class, "podman"));
        }
        public void usernsFails(boolean apparmor) { this.usernsWorks = false; this.apparmorRestricted = apparmor; }
        public void subIds(int start, int count) { this.subIds = Optional.of(new int[] { start, count }); }
        public void noSubIds() { this.subIds = Optional.empty(); }
        public void foreignSubIds(String user, int start, int count) {
            foreignSubIds.add(user + ":" + start + ":" + count);
        }
        public void executables(Tuple<String> names) {
            for (String name : names) executables.put(name, Path.of("/usr/bin", name));
        }
        public void clearTerminals() {
            for (var id : TerminalProfileId.values())
                executables.remove(id.executable());
        }
        public void removeExecutable(String name) { executables.remove(name); }
        public void sudo(Sudo sudo) { this.sudo = sudo; }
        public void interactive(boolean interactive) { this.interactive = interactive; }
        public void renderNode(String path, String group, String driver) {
            renderNodes.add(new RenderNode(path, group, driver));
        }
        public void clearRenderNodes() { renderNodes.clear(); }
        public void clock(Instant instant) { this.clock = instant; }
        public void clockRuns() { this.clockRuns = true; }
        public void randomToken(String token) { this.randomToken = token; }
        public void scriptCommand(String prefix, Outcome outcome) { scriptedCommands.put(prefix, outcome); }
        public void filesystemType(String type) { this.filesystemType = type; }
        public void endpointRefusingConnections(String socketFileName) {
            deadEndpoints.add(socketFileName);
        }
        public void windowRefusing(String executable) { refusedWindows.add(executable); }
        public void commandFailing(String prefix, int exitCode, String stderr) {
            scriptedCommands.put(prefix,
                    new Outcome.Finished(exitCode, "", stderr, Duration.ofMillis(5)));
        }
        public void terminalStaysOpen(Duration duration) { this.terminalStaysOpen = duration; }
        public void terminalNeverConnects() { this.terminalConnects = false; }

        public void passThrough(Tuple<String> executables) {
            for (String executable : executables) {
                passThrough.add(executable);
                executables(Tuple.of(String.class, executable));
            }
        }

        public Machine build() {
            environment.putIfAbsent("HOME", home.toString());
            environment.putIfAbsent("USER", userName);
            environment.putIfAbsent("XDG_RUNTIME_DIR", "/run/user/" + uid);

            systemFiles.put("/etc/os-release", osReleaseText());
            systemFiles.put("/etc/subuid", subIdText());
            systemFiles.put("/etc/subgid", subIdText());
            if (apparmorRestricted)
                systemFiles.put("/proc/sys/kernel/apparmor_restrict_unprivileged_userns", "1\n");

            executables.put("sh", Path.of("/bin/sh"));
            executables.put("id", Path.of("/usr/bin/id"));
            executables.put("nproc", Path.of("/usr/bin/nproc"));
            executables.put("stat", Path.of("/usr/bin/stat"));
            for (String pkg : installedPackages) {
                if (pkg.equals("openssh-client")) { executables.put("ssh", Path.of("/usr/bin/ssh"));
                                                    executables.put("ssh-keygen", Path.of("/usr/bin/ssh-keygen")); }
                if (pkg.equals("socat"))          executables.put("socat", Path.of("/usr/bin/socat"));
                if (pkg.equals("podman"))         executables.put("podman", Path.of("/usr/bin/podman"));
                if (pkg.equals("tigervnc-viewer"))executables.put("vncviewer", Path.of("/usr/bin/vncviewer"));
            }
            if (sudo != Sudo.UNAVAILABLE) executables.put("sudo", Path.of("/usr/bin/sudo"));

            List<Path> driNodes = new ArrayList<>();
            for (RenderNode node : renderNodes) {
                driNodes.add(Path.of(node.path()));
                String name = Path.of(node.path()).getFileName().toString();
                systemFiles.put("/sys/class/drm/" + name + "/device/uevent", "DRIVER=" + node.driver() + "\n");
                script("stat -c %G " + node.path(), node.group() + "\n");
            }
            if (!driNodes.isEmpty()) systemDirectories.put("/dev/dri", driNodes);

            script("id -u", uid + "\n");
            script("id -g", gid + "\n");
            script("id -Gn", String.join(" ", groups) + "\n");
            script("nproc", processors + "\n");
            script("dpkg-query", dpkgQueryOutput(), installedPackages.isEmpty() ? 1 : 0);
            script("sudo -n true", "", sudo == Sudo.PASSWORDLESS ? 0 : 1);

            podmanVersion.ifPresentOrElse(version -> {
                script("podman version --format json",
                        "{\"Client\":{\"Version\":\"" + version + "\"}}\n");
                script("podman info --format json",
                        """
                        {"host":{"security":{"rootless":%s},"ociRuntime":{"name":"%s"},\
                        "cgroupVersion":"v2"},"store":{"graphDriverName":"overlay"}}
                        """.formatted(rootless, ociRuntime));
                if (usernsWorks) script("podman unshare true", "");
                else scriptedCommands.put("podman unshare true", new Outcome.Finished(1, "",
                        apparmorRestricted
                            ? "Error: cannot clone: Operation not permitted\n"
                            + "user namespaces are not enabled in /proc/sys/user/max_user_namespaces\n"
                            : "Error: cannot set up namespace using \"/usr/bin/newuidmap\": exit status 1\n",
                        Duration.ofMillis(20)));
            }, () -> { });

            // A machine that has never run this lamp has neither. `podman image exists` and
            // `podman container exists` report absence with a non-zero exit, not with output, so
            // the default "any known executable succeeds" would have said both were present — and
            // oillamp would have skipped the build and then started a container from nothing.
            script("podman image exists", "", 1);
            script("podman container exists", "", 1);

            script("stat -f -c %T", filesystemType + "\n");
            return new SimulatedMachine(this);
        }

        private void script(String prefix, String output) { script(prefix, output, 0); }

        private void script(String prefix, String output, int exitCode) {
            scriptedCommands.putIfAbsent(prefix,
                    new Outcome.Finished(exitCode, output, "", Duration.ofMillis(5)));
        }

        private String osReleaseText() {
            return "PRETTY_NAME=\"" + osPrettyName + "\"\n"
                 + "NAME=\"" + osPrettyName + "\"\n"
                 + "VERSION_ID=\"" + osVersionId + "\"\n"
                 + "ID=" + osId + "\n"
                 + (osIdLike.isEmpty() ? "" : "ID_LIKE=" + osIdLike + "\n");
        }

        private String subIdText() {
            StringBuilder out = new StringBuilder();
            for (String foreign : foreignSubIds) {
                String[] parts = foreign.split(":", -1);
                out.append(parts[0]).append(':').append(parts[1]).append(':').append(parts[2]).append('\n');
            }
            subIds.ifPresent(range ->
                    out.append(userName).append(':').append(range[0]).append(':').append(range[1]).append('\n'));
            return out.toString();
        }

        /** Mimics {@code dpkg-query -W -f='${Package} ${Status}\n'}: found lines only, plus a non-zero exit. */
        private String dpkgQueryOutput() {
            StringBuilder out = new StringBuilder();
            for (String pkg : installedPackages)
                out.append(pkg).append(" install ok installed\n");
            return out.toString();
        }
    }
}
