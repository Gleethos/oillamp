package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import dev.lamp.Problem;

import com.fasterxml.jackson.databind.JsonNode;
import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// Finds out what this machine is, producing [HostFacts].
///
/// Every check may fail without throwing. A missing podman becomes an empty `Optional`, an
/// unreadable `/etc/subuid` becomes "no ranges", a failing `podman unshare` becomes a
/// `Fails` value with its evidence. So one failed check does not hide the others, and the
/// user learns everything that is wrong in one run.
///
/// Where possible it tries things rather than checking versions. It runs
/// `podman unshare true` because on Ubuntu 23.10 and newer AppArmor can block user namespaces
/// even for an up-to-date podman.
final class HostProbeUtil {

    private HostProbeUtil() {}

    private static final Duration QUICK = Duration.ofSeconds(5);

    public static HostFacts probe(Machine machine, HostRequirements requirements, Path lampPathHint) {
        OsRelease os = probeOsRelease(machine);
        UserInfo user = probeUser(machine);
        Optional<PodmanFacts> podman = probePodman(machine);

        return new HostFacts(
                os,
                user,
                runtimeDirectory(machine, user.uid()),
                probeSession(machine),
                probeInstalledPackages(machine, requirements),
                probeSubIds(machine, user),
                podman,
                probeUserNamespaces(machine, podman),
                machine.readSystemFile(Path.of("/sys/fs/selinux/enforce")).isPresent(),
                probeTerminals(machine),
                machine.locateExecutable("vncviewer"),
                probeGpu(machine),
                probeProcessors(machine),
                probeSudo(machine),
                probeFilesystemType(machine, lampPathHint));
    }

    // ─── individual probes ─────────────────────────────────────────────────────────────────

    private static OsRelease probeOsRelease(Machine machine) {
        String text = machine.readSystemFile(Path.of("/etc/os-release")).orElse("");
        String id = releaseValue(text, "ID", "unknown");
        String idLike = releaseValue(text, "ID_LIKE", "");
        Tuple<String> like = Tuple.of(String.class);
        for (String entry : idLike.split(" ", -1))
            if (!entry.isBlank()) like = like.add(entry);
        return new OsRelease(machine.operatingSystemName(), id, like,
                releaseValue(text, "VERSION_ID", ""),
                releaseValue(text, "PRETTY_NAME", id));
    }

    private static String releaseValue(String osRelease, String key, String fallback) {
        for (String line : osRelease.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(key + "=")) continue;
            String value = trimmed.substring(key.length() + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                value = value.substring(1, value.length() - 1);
            return value;
        }
        return fallback;
    }

    /// Where the short socket paths of every lamp go: `$XDG_RUNTIME_DIR`, or `/run/user/<uid>`,
    /// its usual value, where it is not set, as under `sudo -i` or in a cron job.
    ///
    /// The login system (systemd-logind on most distributions) creates it for each user at login.
    /// It is in memory, private to the user, and emptied at reboot, which suits what oillamp keeps
    /// there: sockets that only mean anything while a session runs. Each lamp gets
    /// `oillamp/<agent id>/` inside it; see [LampLayout].
    static Path runtimeDirectory(Machine machine) {
        return runtimeDirectory(machine, firstInteger(machine, 1000, "id", "-u"));
    }

    private static Path runtimeDirectory(Machine machine, int uid) {
        return machine.environmentVariable("XDG_RUNTIME_DIR").map(Path::of)
                .orElse(Path.of("/run/user/" + uid));
    }

    private static UserInfo probeUser(Machine machine) {
        String name = machine.environmentVariable("USER").orElse("unknown");
        Path home = machine.environmentVariable("HOME").map(Path::of).orElse(Path.of("/home", name));
        int uid = firstInteger(machine, 1000, "id", "-u");
        int gid = firstInteger(machine, 1000, "id", "-g");
        ValueSet<String> groups = ValueSet.of(String.class);
        Machine.Outcome outcome = machine.run(Machine.Command.of("id", "-Gn").withTimeout(QUICK));
        for (String group : outcome.output().trim().split("\\s+", -1))
            if (!group.isBlank()) groups = groups.add(group);
        return new UserInfo(name, uid, gid, home, groups);
    }

    private static GraphicalSession probeSession(Machine machine) {
        String desktop = machine.environmentVariable("XDG_CURRENT_DESKTOP").orElse("");
        Optional<String> wayland = machine.environmentVariable("WAYLAND_DISPLAY");
        if (wayland.isPresent()) return new GraphicalSession.Wayland(wayland.get(), desktop);
        Optional<String> x11 = machine.environmentVariable("DISPLAY");
        if (x11.isPresent()) return new GraphicalSession.X11(x11.get(), desktop);
        return new GraphicalSession.None();
    }

    /// Asks dpkg about exactly the packages oillamp needs. A package that is not installed makes
    /// dpkg-query exit non-zero while still reporting the others, so the exit code is ignored and
    /// the output is what counts.
    private static ValueSet<String> probeInstalledPackages(Machine machine, HostRequirements requirements) {
        Tuple<String> argv = Tuple.of(String.class, "dpkg-query", "-W", "-f=${Package} ${Status}\n");
        argv = argv.addAll(requirements.packages());
        Machine.Outcome outcome = machine.run(new Machine.Command(argv,
                Association.between(String.class, String.class),
                Optional.empty(), QUICK, "dpkg-query", false));
        ValueSet<String> installed = ValueSet.of(String.class);
        for (String line : outcome.output().split("\n", -1)) {
            if (!line.contains("install ok installed")) continue;
            String name = line.substring(0, line.indexOf(' ')).trim();
            if (!name.isEmpty()) installed = installed.add(name);
        }
        return installed;
    }

    private static SubIdFacts probeSubIds(Machine machine, UserInfo user) {
        Tuple<IdRange> uidRanges = parseSubIds(machine, "/etc/subuid");
        Tuple<IdRange> gidRanges = parseSubIds(machine, "/etc/subgid");
        Optional<IdRange> mineUid = firstRangeFor(machine, "/etc/subuid", user);
        Optional<IdRange> mineGid = firstRangeFor(machine, "/etc/subgid", user);
        if (mineUid.isPresent() && mineGid.isPresent()
                && mineUid.get().count() >= SubIdFacts.REQUIRED_SIZE
                && mineGid.get().count() >= SubIdFacts.REQUIRED_SIZE)
            return new SubIdFacts.Present(mineUid.get(), mineGid.get());
        return new SubIdFacts.Missing(uidRanges, gidRanges);
    }

    private static Tuple<IdRange> parseSubIds(Machine machine, String file) {
        Tuple<IdRange> ranges = Tuple.of(IdRange.class);
        for (String line : machine.readSystemFile(Path.of(file)).orElse("").split("\n", -1)) {
            String[] parts = line.trim().split(":", -1);
            if (parts.length != 3) continue;
            try {
                ranges = ranges.add(new IdRange(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            } catch (IllegalArgumentException ignored) {
                // A malformed line is not our business to fix; it just does not constrain us.
            }
        }
        return ranges;
    }

    private static Optional<IdRange> firstRangeFor(Machine machine, String file, UserInfo user) {
        for (String line : machine.readSystemFile(Path.of(file)).orElse("").split("\n", -1)) {
            String[] parts = line.trim().split(":", -1);
            if (parts.length != 3) continue;
            boolean mine = parts[0].equals(user.name()) || parts[0].equals(Integer.toString(user.uid()));
            if (!mine) continue;
            try {
                return Optional.of(new IdRange(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<PodmanFacts> probePodman(Machine machine) {
        Machine.Outcome version = machine.run(
                Machine.Command.of("podman", "version", "--format", "json").withTimeout(QUICK));
        if (!version.succeeded()) return Optional.empty();
        Machine.Outcome info = machine.run(
                Machine.Command.of("podman", "info", "--format", "json").withTimeout(QUICK));
        try {
            JsonNode versionNode = JsonUtil.READER.readTree(version.output());
            String versionText = versionNode.path("Client").path("Version").asText("0");
            JsonNode infoNode = info.succeeded() ? JsonUtil.READER.readTree(info.output()) : JsonUtil.object();
            JsonNode host = infoNode.path("host");
            return Optional.of(new PodmanFacts(
                    versionText,
                    host.path("security").path("rootless").asBoolean(false),
                    host.path("ociRuntime").path("name").asText("unknown"),
                    infoNode.path("store").path("graphDriverName").asText("unknown"),
                    host.path("cgroupVersion").asText("v2").equals("v2") ? 2 : 1));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return Optional.empty();
        }
    }

    private static UserNameSpaceFacts probeUserNamespaces(Machine machine, Optional<PodmanFacts> podman) {
        if (podman.isEmpty()) return new UserNameSpaceFacts.Works();   // nothing to test yet; HostPhase probes again after installing
        Machine.Outcome outcome = machine.run(
                Machine.Command.of("podman", "unshare", "true").withTimeout(QUICK));
        if (outcome.succeeded()) return new UserNameSpaceFacts.Works();
        boolean apparmor = machine
                .readSystemFile(Path.of("/proc/sys/kernel/apparmor_restrict_unprivileged_userns"))
                .map(String::trim).filter("1"::equals).isPresent();
        return new UserNameSpaceFacts.Fails(new Problem.Evidence.Command(
                Tuple.of(String.class, "podman", "unshare", "true"),
                outcome.exitCode(), tail(outcome.errorOutput(), 10), Duration.ZERO), apparmor);
    }

    private static Tuple<TerminalCandidate> probeTerminals(Machine machine) {
        Tuple<TerminalCandidate> found = Tuple.of(TerminalCandidate.class);
        for (TerminalProfileId id : TerminalProfileId.values()) {
            Optional<Path> executable = machine.locateExecutable(id.executable());
            if (executable.isPresent()) found = found.add(new TerminalCandidate(id, executable.get()));
        }
        return found;
    }

    private static GpuFacts probeGpu(Machine machine) {
        Tuple<GpuFacts.RenderNode> nodes = Tuple.of(GpuFacts.RenderNode.class);
        for (Path entry : machine.listSystemDirectory(Path.of("/dev/dri"))) {
            Path fileName = entry.getFileName();
            if (fileName == null || !fileName.toString().startsWith("renderD")) continue;
            String driver = machine
                    .readSystemFile(Path.of("/sys/class/drm", fileName.toString(), "device", "uevent"))
                    .map(text -> releaseValue(text, "DRIVER", "unknown"))
                    .orElse("unknown");
            String group = machine.run(Machine.Command.of("stat", "-c", "%G", entry.toString())
                    .withTimeout(QUICK)).output().trim();
            nodes = nodes.add(new GpuFacts.RenderNode(entry, group, driver));
        }
        return new GpuFacts(nodes);
    }

    private static int probeProcessors(Machine machine) {
        return Math.max(1, firstInteger(machine, 1, "nproc"));
    }

    private static SudoFacts probeSudo(Machine machine) {
        if (machine.locateExecutable("sudo").isEmpty())
            return new SudoFacts.Unavailable("sudo is not installed");
        Machine.Outcome outcome = machine.run(Machine.Command.of("sudo", "-n", "true").withTimeout(QUICK));
        if (outcome.succeeded()) return new SudoFacts.Passwordless();
        if (outcome instanceof Machine.Outcome.NotFound)
            return new SudoFacts.Unavailable("sudo is not installed");
        return new SudoFacts.NeedsPassword(machine.isInteractive());
    }

    private static String probeFilesystemType(Machine machine, Path path) {
        Machine.Outcome outcome = machine.run(
                Machine.Command.of("stat", "-f", "-c", "%T", path.toString()).withTimeout(QUICK));
        String type = outcome.output().trim();
        return type.isEmpty() ? "unknown" : type;
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private static int firstInteger(Machine machine, int fallback, String... argv) {
        Machine.Outcome outcome = machine.run(Machine.Command.of(argv).withTimeout(QUICK));
        try {
            return Integer.parseInt(outcome.output().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String tail(String text, int lines) {
        String[] all = text.split("\n", -1);
        int from = Math.max(0, all.length - lines);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < all.length; i++)
            out.append(out.isEmpty() ? "" : "\n").append(all[i]);
        return out.toString();
    }
}
