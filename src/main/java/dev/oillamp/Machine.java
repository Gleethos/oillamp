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
     * Starts a process and does <em>not</em> wait for it — a window on the user's desktop.
     *
     * <p>The second kind of effect a session needs, and different enough from {@link #run} to be
     * its own method rather than a flag on it. A terminal window and a desktop viewer outlive the
     * call that opened them, have no timeout that would make sense, and are interesting mainly
     * for whether they are <em>still</em> there. Running them through {@code run} would mean
     * oillamp blocking for the length of the session on a command it started.
     *
     * <p>It is also what lets a scenario check the part of a session a human would otherwise have
     * to watch for: that two windows were opened, which command each was given, and that neither
     * of them was the terminal oillamp was launched from.
     */
    Window launch(Command command, Window.Stdio stdio);

    /**
     * A process oillamp started but is not waiting for.
     *
     * <p>Deliberately not a {@code Process}: a window that could not be opened at all is a normal
     * outcome here, not an exception, and the callers care about four questions only — is it
     * still there, how did it end, what did it say, and please go away now.
     */
    interface Window {

        /** Where the process's input and output go. */
        enum Stdio {
            /**
             * Its own window. oillamp keeps whatever it prints, because a window that dies on
             * opening says why on its standard error and nowhere else.
             */
            DETACHED,
            /**
             * This terminal, handed over for as long as the process runs — what {@code oillamp
             * shell} needs, since an interactive shell whose output was captured is not a shell.
             */
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

        /** Asks it to close, and stops caring. Never throws: shutdown must not fail here. */
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
         * Runs this command out of reach of the terminal's signals.
         *
         * <p>Children of oillamp share its process group, so Ctrl-C in the launching terminal
         * goes to <em>all</em> of them. That is right for the session, and wrong for the commands
         * that clean it up: a user who presses Ctrl-C a second time because shutdown is taking a
         * moment would otherwise kill the very {@code podman stop} that is finalising their
         * recording. Shielded commands get their own session, so only oillamp decides when they
         * end.
         */
        public Command shieldedFromSignals() {
            return new Command(argv, environment, workingDirectory, timeout, label, true);
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

        /**
         * A socket whose file is present but which refuses connections.
         *
         * <p>The shape of a real failure: wayvnc could not bind because the previous session's
         * socket file was already there, so the path looks exactly right and nothing is behind
         * it. Scenarios use this to check that oillamp finds out before the user does.
         */
        public Simulation endpointRefusingConnections(String socketFileName) {
            builder.endpointRefusingConnections(socketFileName);
            return this;
        }

        /**
         * A window that cannot be started at all — the executable is gone, or will not run.
         *
         * <p>The two windows fail very differently, which is the point of being able to describe
         * this: a viewer that will not open is a warning on a session that carries on, and a
         * terminal that will not open ends the session, because nobody is in the sandbox.
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
         * The terminal window opens but no shell ever reaches the sandbox — the terminal emulator
         * that starts and then fails on its own arguments, which §10.6 answers with OIL-TERM-002.
         */
        public Simulation terminalThatNeverConnects() {
            builder.terminalNeverConnects();
            return this;
        }

        /**
         * A command that fails however often it is run, with the given exit code and stderr.
         *
         * <p>Written for the shape that actually happened: a user pressed Ctrl-C a second time
         * while the session was shutting down, the signal reached {@code podman stop} because it
         * shares oillamp's process group, and it died with exit 130 and nothing on stderr. A
         * cleanup killed that way must not be reported as a bug in oillamp.
         */
        public Simulation commandFailing(String commandPrefix, int exitCode, String stderr) {
            builder.commandFailing(commandPrefix, exitCode, stderr);
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

        /**
         * Lets time pass on this machine, instead of standing still.
         *
         * <p>Only for scenarios about waiting — a session's timeouts, a heartbeat. Everything
         * else is better off with a clock that does not move, because that is what makes a
         * session id, a recording's name and a retention decision the same on every run.
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
