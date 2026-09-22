package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/**
 * The machine oillamp is running on — the single boundary between decisions and effects.
 *
 * <p>Everything oillamp does that it cannot decide on its own passes through here: running a
 * command, reading a system file, looking up an executable, asking the time, asking for
 * randomness. The rest of oillamp is pure and takes the results as values (spec §23).
 *
 * <p>Keeping the seam this narrow is what makes the tool testable. A scenario describes a
 * machine — "Ubuntu 24.04, GNOME on Wayland, no podman installed, sudo needs a password" — and
 * then runs the real command-line entry point against it. No mocking of oillamp's own types is
 * needed, and no test has to install anything.
 *
 * <p>Note what is <em>not</em> here: ordinary file reads and writes inside the lamp directory.
 * Those use the real filesystem even in tests, because the lamp's whole security story is made
 * of POSIX permission bits, ownership and symlinks (§9.2), and a simulated filesystem that got
 * those subtly wrong would be worse than no test at all.
 *
 * <p>Deliberately <b>public</b>: a caller must be able to supply one — that is what makes it a
 * seam. Scenarios describe a machine, {@code main} passes the real one, and a future GUI would do
 * the same.
 */
public interface Machine {

    /** A machine that really is this computer. */
    static Machine real() { return new RealMachine(); }

    /**
     * Starts describing a machine instead of using this one.
     *
     * <p>Described in the words a person would use — "Ubuntu 24.04 with GNOME on Wayland, podman
     * missing, sudo needs a password" — but answering with genuine command output underneath, so
     * the real parsers run. A simulation that handed back pre-parsed facts would quietly stop
     * testing the half of the code most likely to break when a tool changes its output.
     */
    static Simulation simulated() { return new Simulation(); }

    /** The current instant. Taken from here so that session ids and retention are testable. */
    Instant now();

    /** What this machine calls itself, as {@code os.name} reports it. oillamp requires Linux (FR-03). */
    String operatingSystemName();

    Optional<String> environmentVariable(String name);

    /**
     * Cryptographically strong randomness for the {@code agentId} that names a lamp forever.
     *
     * @param length how many characters, from the RFC 4648 lowercase base32 alphabet
     */
    String randomToken(int length);

    /**
     * Whether a human is at the other end: decides whether {@code sudo} may prompt for a
     * password (§11.2) and whether console output is coloured (§27.4).
     */
    boolean isInteractive();

    /** Runs an external command to completion, or until its timeout expires. */
    Outcome run(Command command);

    /**
     * Reads a file that belongs to the host rather than to a lamp — {@code /etc/os-release},
     * {@code /etc/subuid}, a {@code /proc/sys} entry. Empty when it does not exist or cannot be read.
     */
    Optional<String> readSystemFile(Path path);

    /** Lists a host directory such as {@code /dev/dri}. Empty when it does not exist. */
    Tuple<Path> listSystemDirectory(Path path);

    /** Finds an executable on {@code PATH}. */
    Optional<Path> locateExecutable(String name);

    /**
     * One external command, fully described before it runs — spec §26.1.
     *
     * <p>oillamp never invokes a shell: the argv is the argv, so nothing a configuration value
     * contains can turn into a second command. Every command has a timeout, so a hung tool can
     * never hang oillamp (NFR-02).
     */
    record Command(Tuple<String> argv,
                   Association<String, String> environment,
                   Optional<Path> workingDirectory,
                   Duration timeout,
                   String label) {

        public Command {
            if (argv.isEmpty())
                throw new IllegalArgumentException("A command needs at least an executable");
        }

        public static Command of(String... argv) {
            return new Command(Tuple.of(String.class, argv),
                               Association.between(String.class, String.class),
                               Optional.empty(), Duration.ofSeconds(30),
                               argv.length == 0 ? "" : argv[0]);
        }

        public Command withTimeout(Duration timeout) {
            return new Command(argv, environment, workingDirectory, timeout, label);
        }

        public Command labelled(String label) {
            return new Command(argv, environment, workingDirectory, timeout, label);
        }

        public Command withEnvironment(Association<String, String> environment) {
            return new Command(argv, environment, workingDirectory, timeout, label);
        }

        public String executable() { return argv.first(); }

        /** The command line as a human would type it — used in logs and in problem evidence. */
        public String commandLine() { return String.join(" ", argv); }
    }

    /** How a command ended. Sealed, so no caller can forget that "not found" is a real outcome. */
    sealed interface Outcome {

        record Finished(int exitCode, String standardOutput, String standardError, Duration took)
                implements Outcome {}

        record NotFound(String executable) implements Outcome {}

        record TimedOut(Duration after, String standardOutputSoFar) implements Outcome {}

        default boolean succeeded() {
            return this instanceof Finished finished && finished.exitCode() == 0;
        }

        /** Standard output, or empty text for the outcomes that never produced any. */
        default String output() {
            return switch (this) {
                case Finished finished -> finished.standardOutput();
                case NotFound ignored  -> "";
                case TimedOut timedOut -> timedOut.standardOutputSoFar();
            };
        }

        default String errorOutput() {
            return switch (this) {
                case Finished finished -> finished.standardError();
                case NotFound notFound -> notFound.executable() + ": command not found";
                case TimedOut timedOut -> "timed out after " + timedOut.after();
            };
        }

        default int exitCode() {
            return switch (this) {
                case Finished finished -> finished.exitCode();
                case NotFound ignored  -> 127;
                case TimedOut ignored  -> 124;
            };
        }
    }

    /**
     * Describes a machine.
     *
     * <p>Start from {@link #ubuntuWithEverything()} when the host is not what a scenario is
     * about, and take things away from it when it is.
     */
    final class Simulation {

        private final SimulatedMachine.Builder builder = new SimulatedMachine.Builder();

        Simulation() {}

        // ─── the distribution ──────────────────────────────────────────────────────────────

        public Simulation ubuntu(String versionId) {
            return distribution("ubuntu", "debian", versionId, "Ubuntu " + versionId + " LTS");
        }

        public Simulation debian(String versionId) {
            return distribution("debian", "", versionId, "Debian GNU/Linux " + versionId);
        }

        public Simulation fedora(String versionId) {
            return distribution("fedora", "", versionId, "Fedora Linux " + versionId);
        }

        public Simulation distribution(String id, String idLike, String versionId, String prettyName) {
            builder.osRelease(id, idLike, versionId, prettyName);
            return this;
        }

        /** Makes this machine claim not to be Linux at all, so FR-03's refusal can be exercised. */
        public Simulation notLinux(String osName) {
            builder.operatingSystemName(osName);
            return this;
        }

        // ─── the user and their directories ────────────────────────────────────────────────

        public Simulation user(String name, int uid, int gid, Path home) {
            builder.user(name, uid, gid, home);
            return this;
        }

        public Simulation memberOfGroups(String... groups) {
            builder.groups(Tuple.of(String.class, groups));
            return this;
        }

        /** Where {@code $XDG_RUNTIME_DIR} points — short-path sockets live under it (D-25). */
        public Simulation runtimeDirectory(Path path) {
            builder.runtimeDirectory(path);
            return this;
        }

        public Simulation processors(int count) {
            builder.processors(count);
            return this;
        }

        // ─── the desktop session ───────────────────────────────────────────────────────────

        public Simulation waylandSession(String desktop) {
            builder.session("wayland-0", "", desktop);
            return this;
        }

        public Simulation x11Session(String desktop) {
            builder.session("", ":0", desktop);
            return this;
        }

        /** No display at all — as when oillamp is run over a plain SSH login (OIL-HOST-003). */
        public Simulation noGraphicalSession() {
            builder.session("", "", "");
            return this;
        }

        // ─── host packages ─────────────────────────────────────────────────────────────────

        public Simulation withPackages(String... packages) {
            builder.installPackages(Tuple.of(String.class, packages));
            return this;
        }

        public Simulation withoutPackages(String... packages) {
            builder.removePackages(Tuple.of(String.class, packages));
            return this;
        }

        // ─── podman ────────────────────────────────────────────────────────────────────────

        public Simulation podman(String version, String ociRuntime) {
            builder.podman(version, ociRuntime, true);
            return this;
        }

        /** Podman present but running against a root daemon-style setup — refused by NFR-05. */
        public Simulation rootfulPodman(String version) {
            builder.podman(version, "runc", false);
            return this;
        }

        public Simulation withoutPodman() {
            builder.noPodman();
            return this;
        }

        /** Unprivileged user namespaces blocked, as on Ubuntu ≥ 23.10 (OIL-PODMAN-004). */
        public Simulation userNamespacesBlockedByAppArmor() {
            builder.usernsFails(true);
            return this;
        }

        public Simulation userNamespacesBroken() {
            builder.usernsFails(false);
            return this;
        }

        // ─── subordinate ids ───────────────────────────────────────────────────────────────

        public Simulation subordinateIds(int start, int count) {
            builder.subIds(start, count);
            return this;
        }

        public Simulation withoutSubordinateIds() {
            builder.noSubIds();
            return this;
        }

        /** Ranges belonging to other users, which a new allocation has to step around. */
        public Simulation subordinateIdsTakenBy(String otherUser, int start, int count) {
            builder.foreignSubIds(otherUser, start, count);
            return this;
        }

        // ─── tools on PATH ─────────────────────────────────────────────────────────────────

        public Simulation terminals(String... executables) {
            builder.executables(Tuple.of(String.class, executables));
            return this;
        }

        public Simulation withoutTerminals() {
            builder.clearTerminals();
            return this;
        }

        public Simulation withoutVncViewer() {
            builder.removeExecutable("vncviewer");
            return this;
        }

        // ─── sudo ──────────────────────────────────────────────────────────────────────────

        public Simulation passwordlessSudo() { builder.sudo(SimulatedMachine.Sudo.PASSWORDLESS); return this; }
        public Simulation sudoNeedsPassword() { builder.sudo(SimulatedMachine.Sudo.NEEDS_PASSWORD); return this; }
        public Simulation withoutSudo()       { builder.sudo(SimulatedMachine.Sudo.UNAVAILABLE); return this; }

        /** Whether a human is at the keyboard — decides if sudo may prompt and if output is coloured. */
        public Simulation interactive(boolean interactive) {
            builder.interactive(interactive);
            return this;
        }

        // ─── graphics ──────────────────────────────────────────────────────────────────────

        public Simulation renderNode(String path, String group, String driver) {
            builder.renderNode(path, group, driver);
            return this;
        }

        public Simulation withoutRenderNodes() {
            builder.clearRenderNodes();
            return this;
        }

        // ─── determinism ───────────────────────────────────────────────────────────────────

        public Simulation clockAt(Instant instant) {
            builder.clock(instant);
            return this;
        }

        /** Fixes the generated {@code agentId}, so paths and names are predictable in a scenario. */
        public Simulation generatedAgentId(String agentId) {
            builder.randomToken(agentId);
            return this;
        }

        /**
         * Lets these executables really run on this computer instead of being answered with a
         * canned result.
         *
         * <p>Needed when a scenario depends on what a command <em>produces</em> rather than on
         * what it reports — {@code ssh-keygen} is the case in point: a lamp is only really set up
         * if the key files exist afterwards, and no canned answer can make that true.
         */
        public Simulation reallyRuns(String... executables) {
            builder.passThrough(Tuple.of(String.class, executables));
            return this;
        }

        /** Overrides what one command does, for the rare scenario that is about a command failing. */
        public Simulation command(String commandLinePrefix, Outcome outcome) {
            builder.scriptCommand(commandLinePrefix, outcome);
            return this;
        }

        /** The filesystem the lamp directory is on, e.g. {@code nfs} to exercise OIL-LAMP-005. */
        public Simulation lampFilesystemType(String type) {
            builder.filesystemType(type);
            return this;
        }

        /** A stock, fully prepared Ubuntu desktop: the machine most scenarios are not about. */
        public Simulation ubuntuWithEverything() {
            return ubuntu("24.04")
                    .waylandSession("GNOME")
                    .withPackages("podman", "crun", "slirp4netns", "uidmap", "catatonit", "socat",
                                  "openssh-client", "tigervnc-viewer")
                    .podman("5.4.2", "crun")
                    .subordinateIds(100_000, 65_536)
                    .terminals("ptyxis")
                    .passwordlessSudo()
                    .interactive(true);
        }

        public Machine build() { return builder.build(); }
    }
}
