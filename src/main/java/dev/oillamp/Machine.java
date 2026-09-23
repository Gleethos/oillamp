package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/**
 * The machine oillamp runs on: its interface to the outside world.
 *
 * <p>Running a command, opening a window, reading a system file, finding an executable, asking the
 * time and generating random ids all go through here. The rest of oillamp takes the results as
 * values.
 *
 * <p>This is what makes oillamp testable. A scenario describes a machine, for example "Ubuntu
 * 24.04, GNOME on Wayland, no podman, sudo needs a password", with {@link #simulated()}, and runs
 * the real entry point against it.
 *
 * <p>Not everything goes through here. Files inside the lamp directory are read and written by
 * {@link Filesystem} on the real disk, even in tests, because the lamp's security depends on real
 * permission bits, ownership and symlinks. The sockets of a running session ({@link Relay},
 * {@link Control}, {@link Egress}) are also real.
 *
 * <p>Public because callers supply it: {@code main} passes the real machine, tests a simulated one.
 */
public interface Machine {

    /** A machine that really is this computer. */
    static Machine real() { return new RealMachine(); }

    /**
     * Starts describing a simulated machine, for tests.
     *
     * <p>The simulated machine answers commands with realistic output (real {@code podman info}
     * JSON, real {@code dpkg-query} lines), so oillamp's real parsers are tested too.
     */
    static Simulation simulated() { return new Simulation(); }

    /** The current instant. Taken from here so that session ids and retention are testable. */
    Instant now();

    /** What this machine calls itself, as {@code os.name} reports it. oillamp requires Linux. */
    String operatingSystemName();

    Optional<String> environmentVariable(String name);

    /**
     * Cryptographically strong randomness for the {@code agentId} that names a lamp forever.
     *
     * @param length how many characters, from the RFC 4648 lowercase base32 alphabet
     */
    String randomToken(int length);

    /**
     * Whether standard input and output are a terminal. Decides whether {@code sudo} may ask for a
     * password and whether console output is coloured.
     */
    boolean isInteractive();

    /** Runs an external command to completion, or until its timeout expires. */
    Outcome run(Command command);

    /**
     * Runs a command like {@link #run(Command)}, and also passes each line of its output (standard
     * output and standard error) to {@code eachLine} as soon as it is printed.
     *
     * <p>Used for long commands, such as building the image, so that the user can see progress
     * while they run. The default implementation passes the lines on only once the command has
     * finished, which is enough for a simulated machine.
     */
    default Outcome run(Command command, java.util.function.Consumer<String> eachLine) {
        Outcome outcome = run(command);
        outcome.output().lines().forEach(eachLine);
        outcome.errorOutput().lines().forEach(eachLine);
        return outcome;
    }

    /**
     * Starts a process and does not wait for it: a terminal window, a viewer, or an interactive
     * shell.
     *
     * <p>Unlike {@link #run}, there is no timeout. These processes live as long as the user keeps
     * them open, and oillamp only needs to know whether they are still running.
     */
    Window launch(Command command, Window.Stdio stdio);

    /**
     * A process oillamp started but is not waiting for.
     *
     * <p>Not a {@code Process}, because a window that could not be started at all is a normal
     * outcome here ({@link #refused}), not an exception.
     */
    interface Window {

        /** Where the process's input and output go. */
        enum Stdio {
            /**
             * A window of its own. oillamp keeps whatever it prints, because a window that closes
             * straight away explains why only on its standard error.
             */
            DETACHED,
            /** The current terminal, for as long as the process runs. Used by {@code oillamp shell}. */
            TERMINAL
        }

        /** The process id, for the session log and for {@code status}. */
        long pid();

        boolean isRunning();

        /** How it ended, or empty while it is still running. */
        Optional<Integer> exitCode();

        /** Why it could not be started at all — empty when it was started. */
        Optional<String> failure();

        /** Whatever it has printed. Empty for a window that was given the terminal. */
        String output();

        /** Waits for it to end and returns its exit code. Used by {@code oillamp shell}. */
        int waitFor();

        /** Asks it to close. Never throws, because shutdown must not fail here. */
        void close();

        /** A window that never opened, because the executable is not there or could not run. */
        static Window refused(String executable, String reason) {
            return new Window() {
                @Override public long pid() { return -1; }
                @Override public boolean isRunning() { return false; }
                @Override public Optional<Integer> exitCode() { return Optional.empty(); }
                @Override public Optional<String> failure() {
                    return Optional.of(executable + ": " + reason);
                }
                @Override public String output() { return ""; }
                @Override public int waitFor() { return 127; }
                @Override public void close() { }
            };
        }
    }

    /**
     * Reads a system file such as {@code /etc/os-release}, {@code /etc/subuid} or a
     * {@code /proc/sys} entry. Empty when it does not exist or cannot be read.
     */
    Optional<String> readSystemFile(Path path);

    /** Lists a host directory such as {@code /dev/dri}. Empty when it does not exist. */
    Tuple<Path> listSystemDirectory(Path path);

    /** Finds an executable on {@code PATH}. */
    Optional<Path> locateExecutable(String name);

    /**
     * One external command, fully described before it runs.
     *
     * <p>The command is an argument list passed directly to the program, never a string given to a
     * shell, so nothing inside a configuration value can become a second command. Every command has
     * a timeout, so a tool that hangs cannot hang oillamp.
     */
    record Command(Tuple<String> argv,
                   Association<String, String> environment,
                   Optional<Path> workingDirectory,
                   Duration timeout,
                   String label,
                   boolean shielded) {

        public Command {
            if (argv.isEmpty())
                throw new IllegalArgumentException("A command needs at least an executable");
        }

        public static Command of(String... argv) {
            return new Command(Tuple.of(String.class, argv),
                               Association.between(String.class, String.class),
                               Optional.empty(), Duration.ofSeconds(30),
                               argv.length == 0 ? "" : argv[0], false);
        }

        /** The same, for an argv that was assembled rather than typed — a terminal or a viewer. */
        public static Command of(Tuple<String> argv) {
            return new Command(argv, Association.between(String.class, String.class),
                               Optional.empty(), Duration.ofSeconds(30),
                               argv.isEmpty() ? "" : argv.first(), false);
        }

        public Command withTimeout(Duration timeout) {
            return new Command(argv, environment, workingDirectory, timeout, label, shielded);
        }

        public Command labelled(String label) {
            return new Command(argv, environment, workingDirectory, timeout, label, shielded);
        }

        public Command withEnvironment(Association<String, String> environment) {
            return new Command(argv, environment, workingDirectory, timeout, label, shielded);
        }

        /**
         * Runs this command so that Ctrl-C in the terminal does not reach it.
         *
         * <p>Programs oillamp starts share its process group, so Ctrl-C in the terminal goes to all
         * of them. Users often press Ctrl-C again while shutdown is running, which would kill the
         * {@code podman stop} that is finishing the recording. A shielded command runs in its own
         * session ({@code setsid}), out of reach of the terminal's signals.
         */
        public Command shieldedFromSignals() {
            return new Command(argv, environment, workingDirectory, timeout, label, true);
        }

        public String executable() { return argv.first(); }

        /** The command line as a person would type it, for logs and problem evidence. */
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

        /** Makes this machine claim not to be Linux, to test that oillamp refuses to run. */
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

        /** Where {@code $XDG_RUNTIME_DIR} points. The short socket paths are under it. */
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

        /** No display at all, as when oillamp is run over a plain SSH login. */
        public Simulation noGraphicalSession() {
            builder.session("", "", "");
            return this;
        }

        // ─── host packages ─────────────────────────────────────────────────────────────────

        public Simulation withPackages(String... packages) {
            builder.installPackages(Tuple.of(String.class, packages));
            return this;
        }

        /**
         * A sandbox socket whose file exists but which refuses connections.
         *
         * <p>This happened for real: wayvnc could not bind because the previous session's socket
         * file was still there, so the path existed with nothing listening behind it.
         */
        public Simulation endpointRefusingConnections(String socketFileName) {
            builder.endpointRefusingConnections(socketFileName);
            return this;
        }

        /**
         * A window program that cannot be started at all. A viewer failing this way is a warning;
         * a terminal failing this way ends the session.
         */
        public Simulation windowRefusing(String executable) {
            builder.windowRefusing(executable);
            return this;
        }

        /** How long the simulated windows stay open before the user "closes" them. */
        public Simulation windowsStayOpenFor(java.time.Duration duration) {
            builder.terminalStaysOpen(duration);
            return this;
        }

        /**
         * The terminal window opens but its shell never connects to the sandbox, for example
         * because the terminal rejected its arguments. oillamp reports {@code OIL-TERM-002}.
         */
        public Simulation terminalThatNeverConnects() {
            builder.terminalNeverConnects();
            return this;
        }

        /**
         * A command, identified by the start of its command line, that always fails with the given
         * exit code and error output. For example {@code podman stop} dying with exit 130 and no
         * output, as it did when a second Ctrl-C reached it.
         */
        public Simulation commandFailing(String commandPrefix, int exitCode, String stderr) {
            builder.commandFailing(commandPrefix, exitCode, stderr);
            return this;
        }

        /**
         * A command that succeeds with the given output. For example {@code podman container
         * exists}, which the simulation otherwise answers with "no" for commands it does not model.
         */
        public Simulation commandSucceeding(String commandPrefix, String stdout) {
            builder.scriptCommand(commandPrefix,
                    new Outcome.Finished(0, stdout, "", Duration.ofMillis(1)));
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

        /** podman present but not rootless, which oillamp refuses. */
        public Simulation rootfulPodman(String version) {
            builder.podman(version, "runc", false);
            return this;
        }

        public Simulation withoutPodman() {
            builder.noPodman();
            return this;
        }

        /** User namespaces blocked by AppArmor, as can happen on Ubuntu 23.10 and newer. */
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

        /** Whether a person is at a terminal. Decides whether sudo may ask for a password and whether output is coloured. */
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

        /**
         * Lets the clock move. By default the simulated clock stands still, so session ids and
         * retention decisions are the same on every run. Scenarios about timeouts need it to move.
         */
        public Simulation clockRuns() {
            builder.clockRuns();
            return this;
        }

        /** Fixes the generated {@code agentId}, so paths and names are predictable in a scenario. */
        public Simulation generatedAgentId(String agentId) {
            builder.randomToken(agentId);
            return this;
        }

        /**
         * Lets these programs really run instead of being simulated. Needed when a scenario depends
         * on what a command creates, such as the key files {@code ssh-keygen} writes.
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

        /** The filesystem type the lamp is on, for example {@code nfs}, which oillamp refuses. */
        public Simulation lampFilesystemType(String type) {
            builder.filesystemType(type);
            return this;
        }

        /** An Ubuntu 24.04 desktop with everything oillamp needs. Most scenarios start from this. */
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
