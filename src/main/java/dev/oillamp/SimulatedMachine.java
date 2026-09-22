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
    private final String randomToken;
    private final boolean interactive;
    private final java.util.Set<String> passThrough;
    private final RealMachine realMachine = new RealMachine();

    private SimulatedMachine(Builder builder) {
        this.passThrough = java.util.Set.copyOf(builder.passThrough);
        this.operatingSystemName = builder.operatingSystemName;
        this.systemFiles = Map.copyOf(builder.systemFiles);
        this.environment = Map.copyOf(builder.environment);
        this.scriptedCommands = new LinkedHashMap<>(builder.scriptedCommands);
        this.executables = Map.copyOf(builder.executables);
        this.systemDirectories = Map.copyOf(builder.systemDirectories);
        this.clock = builder.clock;
        this.randomToken = builder.randomToken;
        this.interactive = builder.interactive;
    }

    @Override public Instant now() { return clock; }

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
        for (Map.Entry<String, Outcome> scripted : scriptedCommands.entrySet())
            if (commandLine.startsWith(scripted.getKey())) return scripted.getValue();
        if (!executables.containsKey(command.executable()))
            return new Outcome.NotFound(command.executable());
        return new Outcome.Finished(0, "", "", Duration.ofMillis(1));
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

        private Instant clock = Instant.parse("2026-09-22T14:15:03Z");
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
        public void randomToken(String token) { this.randomToken = token; }
        public void scriptCommand(String prefix, Outcome outcome) { scriptedCommands.put(prefix, outcome); }
        public void filesystemType(String type) { this.filesystemType = type; }
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
