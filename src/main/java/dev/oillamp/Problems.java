package dev.oillamp;

import dev.oillamp.Problem.Code;
import dev.oillamp.Problem.Evidence;
import dev.oillamp.Problem.Fix;
import dev.oillamp.Problem.Severity;
import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;

/**
 * The problem catalog of spec §27.3, as pure data.
 *
 * <p>Each code has one fixed title, one fixed "why it matters", and default fixes; call sites
 * add the concrete evidence. Keeping the wording here — rather than at the throw site — is what
 * makes the messages consistent and reviewable, and lets tests assert on a code rather than prose.
 *
 * <p>Deliberately <b>package-private</b>: the catalogue of everything oillamp can report. The
 * problem <em>codes</em> are the stable thing scripts match on (§27.2) — this class is only where
 * their text lives, and that text should improve freely.
 */
final class Problems {

    private Problems() {}

    // ─── codes (spec §27.3) ────────────────────────────────────────────────────────────────

    public static final Code HOST_NOT_LINUX        = new Code("OIL-HOST-001");
    public static final Code HOST_NOT_APT          = new Code("OIL-HOST-002");
    public static final Code HOST_NO_GRAPHICS      = new Code("OIL-HOST-003");
    public static final Code HOST_NO_SUBIDS        = new Code("OIL-HOST-010");
    public static final Code PKG_MISSING           = new Code("OIL-PKG-001");
    public static final Code PKG_NO_SUDO           = new Code("OIL-PKG-002");
    public static final Code PKG_INSTALL_FAILED    = new Code("OIL-PKG-003");
    public static final Code PODMAN_TOO_OLD        = new Code("OIL-PODMAN-001");
    public static final Code PODMAN_NOT_ROOTLESS   = new Code("OIL-PODMAN-002");
    public static final Code PODMAN_USERNS_BROKEN  = new Code("OIL-PODMAN-003");
    public static final Code PODMAN_APPARMOR       = new Code("OIL-PODMAN-004");
    public static final Code LAMP_INVALID_PATH     = new Code("OIL-LAMP-001");
    public static final Code LAMP_NOT_EMPTY        = new Code("OIL-LAMP-002");
    public static final Code LAMP_FORBIDDEN_PATH   = new Code("OIL-LAMP-003");
    public static final Code LAMP_NEWER_SCHEMA     = new Code("OIL-LAMP-004");
    public static final Code LAMP_BAD_FILESYSTEM   = new Code("OIL-LAMP-005");
    public static final Code LAMP_NOT_WRITABLE     = new Code("OIL-LAMP-006");
    public static final Code LOCK_BUSY             = new Code("OIL-LOCK-001");
    public static final Code LOCK_RECOVERED        = new Code("OIL-LOCK-002");
    public static final Code CONFIG_UNPARSEABLE    = new Code("OIL-CONFIG-001");
    public static final Code CONFIG_UNKNOWN_KEY    = new Code("OIL-CONFIG-002");
    public static final Code CONFIG_WRONG_TYPE     = new Code("OIL-CONFIG-003");
    public static final Code CONFIG_INVALID_VALUE  = new Code("OIL-CONFIG-004");
    public static final Code GPU_SOFTWARE          = new Code("OIL-GPU-001");
    public static final Code SSH_KEYGEN_FAILED     = new Code("OIL-SSH-001");
    public static final Code TERM_NONE_FOUND       = new Code("OIL-TERM-001");
    public static final Code TERM_NO_CONNECT       = new Code("OIL-TERM-002");
    public static final Code TERM_NOT_STARTED      = new Code("OIL-TERM-003");
    public static final Code VIEWER_DIED           = new Code("OIL-VIEW-001");
    public static final Code SSH_PRIMARY_TAKEN     = new Code("OIL-SSH-002");
    public static final Code NET_SOCKET_PATH_LONG  = new Code("OIL-NET-001");
    public static final Code NET_CANNOT_LISTEN     = new Code("OIL-NET-002");
    public static final Code SESSION_NOT_RUNNING   = new Code("OIL-SESSION-001");
    public static final Code SESSION_UNREACHABLE   = new Code("OIL-SESSION-002");
    public static final Code IMAGE_BUILD_FAILED    = new Code("OIL-IMAGE-001");
    public static final Code SANDBOX_START_FAILED  = new Code("OIL-SANDBOX-001");
    public static final Code SANDBOX_DIED          = new Code("OIL-SANDBOX-002");
    public static final Code SANDBOX_NOT_READY     = new Code("OIL-SANDBOX-003");
    public static final Code SANDBOX_ENDPOINT_DEAD = new Code("OIL-SANDBOX-004");
    public static final Code EXEC_NOT_FOUND        = new Code("OIL-EXEC-001");
    public static final Code EXEC_TIMED_OUT        = new Code("OIL-EXEC-002");
    public static final Code INTERNAL              = new Code("OIL-INTERNAL-001");

    // ─── host ──────────────────────────────────────────────────────────────────────────────

    public static Problem hostNotLinux(String osName) {
        return error(HOST_NOT_LINUX, "Unsupported operating system",
                "oillamp is running on " + osName + ", but the sandbox needs Linux",
                "the sandbox is a rootless Podman container using Linux user namespaces; "
              + "there is no equivalent on this platform")
            .withEvidence(new Evidence.Value("os.name", osName));
    }

    public static Problem hostNotApt(String distroId, Tuple<String> requiredPackages) {
        return error(HOST_NOT_APT, "Unsupported Linux distribution (no APT)",
                "the distribution reports ID=" + distroId + ", which oillamp cannot install packages on",
                "v1 installs host prerequisites with apt-get; on other distributions you have to "
              + "install them yourself, after which oillamp works normally")
            .withEvidence(new Evidence.File(Path.of("/etc/os-release"), "ID=" + distroId))
            .withEvidence(new Evidence.Value("packages oillamp needs", String.join(" ", requiredPackages)))
            .withFix(Fix.of("install the listed packages with your distribution's package manager, "
                          + "then run oillamp again"))
            .withFix(Fix.run("skip the check once the packages are there", "oillamp doctor"));
    }

    public static Problem hostNoGraphics() {
        return error(HOST_NO_GRAPHICS, "No graphical session found",
                "neither WAYLAND_DISPLAY nor DISPLAY is set in this environment",
                "oillamp opens two windows — a terminal and the desktop viewer — and cannot do that "
              + "without a graphical session")
            .withFix(Fix.of("run oillamp from a terminal inside your desktop session, not over a plain SSH login"))
            .withFix(Fix.run("host checks that do not need a display still work", "oillamp doctor"));
    }

    public static Problem hostNoSubIds(String user) {
        return error(HOST_NO_SUBIDS, "No subordinate UID/GID range for the user",
                "/etc/subuid and /etc/subgid contain no range of at least 65536 ids for " + user,
                "rootless Podman maps container users onto these ids; without them the sandbox "
              + "cannot start, and the infra user that guards monitoring cannot exist")
            .withEvidence(new Evidence.File(Path.of("/etc/subuid"), "no usable entry for " + user))
            .withFix(Fix.run("oillamp can add a free range for you",
                             "sudo usermod --add-subuids 100000-165535 --add-subgids 100000-165535 " + user));
    }

    // ─── packages and sudo ─────────────────────────────────────────────────────────────────

    public static Problem packagesMissing(Tuple<String> missing, String installCommand,
                                          Installing installing) {
        // The second remedy has to match why oillamp is not installing them itself. Telling a
        // `doctor` user to "drop --no-install" names a flag they never passed, and reads as though
        // oillamp cannot install packages at all - which is the opposite of true (FR-60).
        String letOillampDoIt = switch (installing) {
            case ALLOWED, DECLINED ->
                    "or let oillamp install them by dropping --no-install / setting host.auto_install = true";
            case NEVER ->
                    "or run `oillamp at <dir>`, which installs them for you after one sudo prompt "
                  + "(this command only ever looks)";
        };
        return error(PKG_MISSING, "Required host packages missing",
                "these packages are not installed: " + String.join(" ", missing),
                "oillamp drives podman, connects SSH through a Unix socket with socat, and opens "
              + "the desktop with a VNC viewer; each missing package disables one of those")
            .withFix(Fix.run("install them", installCommand))
            .withFix(Fix.of(letOillampDoIt));
    }

    public static Problem noSudo(String detail) {
        return error(PKG_NO_SUDO, "Cannot run sudo",
                detail,
                "host packages and the subordinate id range can only be set up with root rights")
            .withFix(Fix.of("run oillamp from an interactive terminal so sudo can ask for your password"))
            .withFix(Fix.of("or install the prerequisites yourself and re-run with --no-install"));
    }

    public static Problem installFailed(Evidence.Command command, Path installLog) {
        return error(PKG_INSTALL_FAILED, "Package installation failed",
                "apt-get exited with code " + command.exitCode(),
                "without the prerequisites oillamp cannot start a sandbox")
            .withEvidence(command)
            .withEvidence(new Evidence.File(installLog, "full installation transcript"))
            .withFix(Fix.run("update the package lists and try again", "sudo apt-get update"))
            .withFix(Fix.of("check the transcript for the underlying apt error"));
    }

    // ─── podman ────────────────────────────────────────────────────────────────────────────

    /** The package is installed but the program did not answer — a broken or shadowed install. */
    static Problem podmanUnusable(String detail) {
        return error(PODMAN_TOO_OLD, "Podman is installed but did not respond",
                detail,
                "oillamp asks podman for its version and configuration before using it; a podman "
              + "that cannot answer cannot run the sandbox either")
            .withFix(Fix.run("see what it says", "podman version"))
            .withFix(Fix.of("check that the podman on PATH is the distribution's, not a stale "
                          + "binary in ~/.local/bin or /usr/local/bin"));
    }

    public static Problem podmanTooOld(String found, String required) {
        return error(PODMAN_TOO_OLD, "Podman too old or not found",
                "found podman " + found + ", but oillamp needs at least " + required,
                "older versions lack the rootless user-namespace and keep-id mapping options "
              + "the sandbox relies on")
            .withEvidence(new Evidence.Value("podman version", found))
            .withFix(Fix.run("install a newer podman", "sudo apt-get install -y podman"));
    }

    public static Problem podmanNotRootless(String detail) {
        return error(PODMAN_NOT_ROOTLESS, "Podman is not running rootless",
                detail,
                "oillamp deliberately never runs a container as real root; a rootful podman would "
              + "give the agent far more of the host than the sandbox is meant to allow")
            .withFix(Fix.of("run oillamp as your normal user, not with sudo"))
            .withFix(Fix.run("check what podman reports", "podman info --format json"));
    }

    public static Problem usernsBroken(Evidence.Command evidence) {
        return error(PODMAN_USERNS_BROKEN, "Rootless user namespaces do not work",
                "the functional check `podman unshare true` failed",
                "every part of the sandbox — the read-only image, the two container users, the "
              + "mapping of your host user onto the agent — is built on user namespaces")
            .withEvidence(evidence)
            .withFix(Fix.run("re-initialise podman's storage after an id-range change", "podman system migrate"))
            .withFix(Fix.run("look for the underlying error", "podman unshare true"));
    }

    public static Problem apparmorBlocked(Evidence.Command evidence) {
        return error(PODMAN_APPARMOR, "Blocked by Ubuntu's AppArmor user-namespace restriction",
                "kernel.apparmor_restrict_unprivileged_userns is 1 and `podman unshare true` fails",
                "Ubuntu 23.10 and newer only let programs with a matching AppArmor profile create "
              + "unprivileged user namespaces, which rootless Podman needs")
            .withEvidence(evidence)
            .withEvidence(new Evidence.Value("kernel.apparmor_restrict_unprivileged_userns", "1"))
            .withFix(Fix.of("use the Ubuntu-packaged podman — a binary installed elsewhere does not "
                          + "match the shipped AppArmor profile"))
            .withFix(Fix.run("reload the profiles", "sudo systemctl reload apparmor"))
            .withFix(Fix.run("last resort, and only if you accept that this weakens a system-wide "
                           + "hardening measure for every program, not just oillamp",
                             "sudo sysctl kernel.apparmor_restrict_unprivileged_userns=0"));
    }

    // ─── lamp ──────────────────────────────────────────────────────────────────────────────

    public static Problem lampForbiddenPath(Path requested, String why) {
        return error(LAMP_FORBIDDEN_PATH, "Refusing to use a system or home directory as a lamp",
                requested + " " + why,
                "a lamp owns the directory it lives in — it writes state into it and mounts part of "
              + "it into the container as the agent's home; pointing that at a system path or at "
              + "your home directory itself would put your own files inside the sandbox")
            .withEvidence(new Evidence.Value("requested path", requested.toString()))
            .withFix(Fix.of("choose a dedicated directory, for example ~/lamps/my-feature"));
    }

    public static Problem lampNotEmpty(Path root, Tuple<String> sampleEntries) {
        return error(LAMP_NOT_EMPTY, "Directory is not empty and not a lamp",
                root + " already contains files, but no .oillamp/lamp.json",
                "oillamp will not scatter lamp state over a directory you are using for something else")
            .withEvidence(new Evidence.Value("found", String.join(", ", sampleEntries)))
            .withFix(Fix.of("pick an empty or non-existent directory"))
            .withFix(Fix.run("or initialise this one anyway, if you are sure",
                             "oillamp at " + root + " --init"));
    }

    public static Problem lampNewerSchema(Path root, int found, int supported) {
        return error(LAMP_NEWER_SCHEMA, "Lamp created by a newer oillamp",
                "the lamp declares schema version " + found + ", this build understands " + supported,
                "a newer layout may hold state this version would misread or destroy")
            .withEvidence(new Evidence.File(root.resolve(".oillamp/lamp.json"), "schemaVersion=" + found))
            .withFix(Fix.of("upgrade oillamp to a version that understands this lamp"));
    }

    public static Problem lampBadFilesystem(Path root, String fsType) {
        return error(LAMP_BAD_FILESYSTEM, "Lamp is on a filesystem without Unix socket support",
                root + " is on a " + fsType + " filesystem",
                "the sandbox talks to the host only through Unix domain sockets inside the lamp; "
              + "network and FAT-style filesystems cannot host them")
            .withEvidence(new Evidence.Value("filesystem type", fsType))
            .withFix(Fix.of("put the lamp on a local filesystem, for example under your home directory"));
    }

    public static Problem lampNotWritable(Path root, String reason) {
        return error(LAMP_NOT_WRITABLE, "Cannot write to the lamp directory",
                reason,
                "oillamp stores the lamp's identity, keys, logs and recordings there")
            .withEvidence(new Evidence.File(root, reason));
    }

    public static Problem lockBusy(Path root, long supervisorPid, String startedAt) {
        return new Problem(LOCK_BUSY, Severity.ERROR, "Lamp is already running",
                "a session started at " + startedAt + " holds " + root + " (supervisor pid " + supervisorPid + ")",
                "one lamp runs at most one sandbox, so two sessions cannot fight over the same agent "
              + "home, the same container name and the same sockets",
                Tuple.of(Evidence.class,
                        new Evidence.Value("supervisor pid", Long.toString(supervisorPid)),
                        new Evidence.Value("session started", startedAt)),
                Tuple.of(Fix.class,
                        Fix.run("watch that session's desktop", "oillamp view " + root),
                        Fix.run("open another shell in it", "oillamp shell " + root),
                        Fix.run("shut it down", "oillamp stop " + root)),
                Optional.empty());
    }

    public static Problem lockRecovered(Path root, String what) {
        return new Problem(LOCK_RECOVERED, Severity.WARNING, "Cleaned up after a previous crashed session",
                what,
                "a supervisor died without running its shutdown sequence; oillamp removed the "
              + "leftovers so this run can start cleanly",
                Tuple.of(Evidence.class, new Evidence.File(root, "stale session state")),
                Tuple.of(Fix.class),
                Optional.empty());
    }

    // ─── configuration ─────────────────────────────────────────────────────────────────────

    public static Problem configUnparseable(Path file, String detail) {
        return error(CONFIG_UNPARSEABLE, "Config file cannot be parsed (TOML syntax)",
                detail,
                "oillamp cannot tell what policy you intended, and guessing would be worse than stopping")
            .withEvidence(new Evidence.File(file, detail));
    }

    public static Problem configUnknownKey(Path file, String keyPath, String didYouMean) {
        return error(CONFIG_UNKNOWN_KEY, "Unknown config key",
                "'" + keyPath + "' is not a key oillamp knows",
                "a typo in a key name would otherwise be silently ignored — and a silently ignored "
              + "network rule is a hole in the sandbox")
            .withEvidence(new Evidence.Config(file, keyPath, "", didYouMean));
    }

    public static Problem configWrongType(Path file, String keyPath, String value, String expected) {
        return error(CONFIG_WRONG_TYPE, "Wrong config value type",
                "'" + keyPath + "' is " + value + ", which is not " + expected,
                "oillamp binds configuration to typed values so the rest of the program cannot "
              + "misinterpret it later")
            .withEvidence(new Evidence.Config(file, keyPath, value, expected));
    }

    public static Problem configInvalidValue(Path file, String keyPath, String value, String expected) {
        return error(CONFIG_INVALID_VALUE, "Invalid config value",
                "'" + keyPath + "' is " + value + ", but " + expected,
                "the value parses but cannot work; catching it now beats a confusing failure "
              + "halfway through starting a session")
            .withEvidence(new Evidence.Config(file, keyPath, value, expected));
    }

    // ─── misc ──────────────────────────────────────────────────────────────────────────────

    public static Problem noTerminal(Tuple<String> supported) {
        return error(TERM_NONE_FOUND, "No supported terminal emulator found",
                "none of the known terminal emulators is on PATH",
                "oillamp opens your shell into the sandbox in a terminal window; without one there "
              + "is nowhere to put it")
            .withEvidence(new Evidence.Value("looked for", String.join(", ", supported)))
            .withFix(Fix.run("install one", "sudo apt-get install -y ptyxis"))
            .withFix(Fix.of("or point oillamp at yours with terminal.command in oillamp.toml"));
    }

    public static Problem socketPathTooLong(Path path, int limit) {
        return error(NET_SOCKET_PATH_LONG, "Socket path too long",
                path + " is " + path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + " bytes, the kernel limit is " + limit,
                "Unix socket paths are capped by the kernel; oillamp routes them through a short "
              + "directory under XDG_RUNTIME_DIR for exactly this reason")
            .withFix(Fix.of("make sure XDG_RUNTIME_DIR is set to a short path such as /run/user/1000"));
    }

    public static Problem cannotListen(Path socket, String reason) {
        return error(NET_CANNOT_LISTEN, "Cannot listen on a socket",
                "oillamp could not bind " + socket + ": " + reason,
                "the terminal and the viewer reach the sandbox through these sockets; without "
              + "them there is no way into the session")
            .withEvidence(new Evidence.File(socket, reason))
            .withFix(Fix.of("check that $XDG_RUNTIME_DIR exists and belongs to you"))
            .withFix(Fix.run("clear anything a crashed session left behind", "rm -f " + socket));
    }

    // ─── the windows of a session (§17.4, §15) ─────────────────────────────────────────────

    /**
     * The terminal window was started but never connected.
     *
     * <p>Reported rather than waited on forever, because the alternative is the worst outcome
     * this tool has: a sandbox running with nobody in it and nothing that will ever end it. What
     * the user sees is a window that opened and did nothing, so the evidence has to be the
     * command that was run — the failure is almost always in the terminal's own arguments.
     */
    public static Problem terminalDidNotConnect(java.time.Duration waited) {
        return error(TERM_NO_CONNECT, "The terminal window did not connect",
                "the terminal was started but no shell reached the sandbox within "
                        + waited.toSeconds() + "s",
                "the terminal window is the session: oillamp ends a session nobody is in rather "
              + "than leave a sandbox running unattended")
            .withFix(Fix.of("run the ssh command above by hand — its output says what ssh could not do"))
            .withFix(Fix.of("or name a terminal you know works with terminal.profile in oillamp.toml"));
    }

    /** The terminal emulator itself would not start — a different failure from not connecting. */
    public static Problem terminalNotStarted(Tuple<String> argv, String reason, String output) {
        Problem problem = error(TERM_NOT_STARTED, "The terminal window could not be opened",
                "starting " + argv.first() + " failed: " + reason,
                "without a terminal there is no shell in the sandbox, so the session has nothing "
              + "to be for")
            .withEvidence(new Evidence.Command(argv, 127, output, java.time.Duration.ZERO))
            .withFix(Fix.of("check that " + argv.first() + " starts from this terminal"))
            .withFix(Fix.of("or name another one with terminal.profile in oillamp.toml"));
        return output.isBlank() ? problem : problem.withEvidence(
                new Evidence.Excerpt("what it printed", output));
    }

    /**
     * The viewer window closed straight after opening — a warning, never an error.
     *
     * <p>§10.6 is deliberate about this: the viewer's lifetime is independent of the session's.
     * A session with no view of the desktop is degraded, not broken, and ending it would throw
     * away work over a window the user can reopen with {@code oillamp view}.
     */
    public static Problem viewerDiedImmediately(Tuple<String> argv, int exitCode, String output) {
        return warning(VIEWER_DIED, "The viewer window closed immediately",
                argv.first() + " exited with code " + exitCode + " a moment after it was started",
                "the session is running and you can still reach it; you simply cannot see the "
              + "desktop until a viewer stays open")
            .withEvidence(new Evidence.Command(argv, exitCode, output, java.time.Duration.ZERO))
            .withFix(Fix.of("run the command above by hand to see what the viewer objects to"))
            .withFix(Fix.of("open another one with `oillamp view <dir>` once it is fixed"));
    }

    /** A second connection to the primary socket, which belongs to one terminal only (§17.3). */
    public static Problem extraPrimaryRejected(Path socket) {
        return warning(SSH_PRIMARY_TAKEN, "A second connection to the session's own socket was refused",
                socket + " accepts one connection per session, and it is already in use",
                "closing that one terminal is what ends the session, so the slot cannot be shared; "
              + "use `oillamp shell <dir>` for extra shells, which do not end anything")
            .withEvidence(new Evidence.File(socket, "the primary relay (D-09)"))
            .withFix(Fix.of("open extra shells with `oillamp shell <dir>`"));
    }

    // ─── reaching a running session (§26.6) ────────────────────────────────────────────────

    public static Problem noSessionRunning(Path lamp, String command) {
        return error(SESSION_NOT_RUNNING, "No session is running for this lamp",
                "there is no supervisor listening for " + lamp,
                "`oillamp " + command + "` talks to a running session; there is nothing to talk to yet")
            .withFix(Fix.run("start one", "oillamp at " + lamp));
    }

    public static Problem supervisorUnreachable(Path socket, String reason) {
        return error(SESSION_UNREACHABLE, "The running session did not answer",
                "its control socket " + socket + " is there but did not respond: " + reason,
                "the supervisor may have been killed without tidying up, which leaves the socket "
              + "file behind")
            .withFix(Fix.of("`oillamp stop <dir>` cleans up after a session that died this way"));
    }

    public static Problem sshKeygenFailed(Evidence.Command command) {
        return error(SSH_KEYGEN_FAILED, "Key generation failed",
                "ssh-keygen exited with code " + command.exitCode(),
                "the lamp needs its own key pair; oillamp never reuses your personal SSH keys for "
              + "the sandbox")
            .withEvidence(command);
    }

    // ─── the image and the sandbox ─────────────────────────────────────────────────────────

    public static Problem podmanFailed(String what, int exitCode, String output) {
        Code code = what.contains("build") ? IMAGE_BUILD_FAILED : SANDBOX_START_FAILED;
        return error(code,
                what.contains("build") ? "Could not build the sandbox image" : "Could not start the sandbox",
                what + " exited with code " + exitCode,
                what.contains("build")
                    ? "the sandbox runs from an image oillamp builds; without it there is nothing to start"
                    : "the sandbox is the container; if it will not start there is no session")
            .withEvidence(new Evidence.Value("output", output))
            .withFix(Fix.of("the output above is podman's own — it usually names the cause exactly"))
            .withFix(Fix.run("check the machine is still able to run containers", "oillamp doctor"));
    }

    /**
     * The container exited while oillamp was waiting for it to become ready.
     *
     * <p>Carries the container's own log, because that is the only place the reason exists: the
     * entrypoint knows why it gave up and says so, and without this the user sees a timeout and a
     * container that is simply gone.
     */
    public static Problem sandboxDied(String container, String log) {
        return error(SANDBOX_DIED, "The sandbox stopped while starting up",
                "container " + container + " exited before it reported itself ready",
                "the desktop, the VNC server and the ssh listener all have to come up; the "
              + "entrypoint stops the sandbox rather than leave a half-working one")
            .withEvidence(new Evidence.Value("last lines of the sandbox log", log))
            .withFix(Fix.of("the log above is from inside the sandbox and names which part failed"));
    }

    public static Problem sandboxNotReady(String container, java.time.Duration waited, String log) {
        return error(SANDBOX_NOT_READY, "The sandbox did not become ready in time",
                "container " + container + " was still starting after " + waited.toSeconds() + "s",
                "oillamp waits for the sandbox to say it is ready rather than guessing, so that a "
              + "session never starts against a desktop that is not there yet")
            .withEvidence(new Evidence.Value("last lines of the sandbox log", log))
            .withFix(Fix.of("if the log shows it still working, the machine may simply be slow — "
                          + "raise the timeout with session.ready_timeout"))
            .withFix(Fix.run("stop the container that is still running", "podman rm -f " + container));
    }

    /**
     * The sandbox said it was ready, but one of its sockets refuses connections.
     *
     * <p>This is the problem that exists because the sandbox reports on itself. A server can die
     * after writing {@code ready.json}, or fail to bind and leave the previous session's socket
     * file standing in for it — and in both cases the sandbox looks healthy from the outside while
     * the human's viewer is refused. Finding that here, rather than letting the user find it, is
     * the whole point of connecting before saying the session is up.
     */
    public static Problem sandboxEndpointDead(String what, java.nio.file.Path socket,
                                              String container, String log) {
        return error(SANDBOX_ENDPOINT_DEAD, "The sandbox is not answering on " + what,
                socket + " refused the connection, although the sandbox reported itself ready",
                "oillamp connects to every socket it is about to hand you, so that a session it "
              + "calls ready is one you can actually reach")
            .withEvidence(new Evidence.Value("socket", socket.toString()))
            .withEvidence(new Evidence.Value("last lines of the sandbox log", log))
            // Only promise the log when there is one. A container that died before it could say
            // anything is a different situation, and pointing at an empty log for the reason
            // sends the reader looking for something that is not there.
            .withFix(hasContent(log)
                    ? Fix.of("the log above is from inside the sandbox and names the process that failed")
                    : Fix.of("the sandbox logged nothing, so it stopped before it could report a "
                           + "reason — an image that no longer matches its tag is the usual cause"))
            .withFix(Fix.of("starting the session again clears anything an earlier one left behind"))
            .withFix(Fix.run("stop the container that is still running", "podman rm -f " + container));
    }

    /** Whether a captured log holds anything a reader could act on. */
    private static boolean hasContent(String log) {
        return !log.isBlank() && !log.startsWith("(");
    }

    public static Problem commandNotFound(String executable) {
        return error(EXEC_NOT_FOUND, "Command not found",
                executable + " is not on PATH",
                "oillamp shells out to a small, fixed set of tools and cannot continue without this one")
            .withEvidence(new Evidence.Value("executable", executable));
    }

    public static Problem commandTimedOut(Evidence.Command command) {
        return error(EXEC_TIMED_OUT, "Command timed out",
                "the command did not finish within " + command.took(),
                "every external command has a timeout so a hung tool can never hang oillamp (NFR-02)")
            .withEvidence(command);
    }

    /** The user typed something oillamp does not understand — exit code 2, never a stack trace. */
    static Problem usage(String whatHappened, String usage) {
        return error(new Code("OIL-USAGE-001"), "Invalid command line",
                whatHappened,
                "oillamp stopped before doing anything, so nothing on your machine changed")
            .withEvidence(new Evidence.Excerpt("usage", usage));
    }

    public static Problem internal(String where, String detail) {
        return error(INTERNAL, "Unexpected internal error (please report)",
                detail,
                "this is a bug in oillamp, not something you did wrong")
            .withEvidence(new Evidence.Value("where", where))
            .withFix(Fix.of("re-run with --debug and attach the session log to a bug report"));
    }

    public static Problem gpuSoftware(String reason) {
        return new Problem(GPU_SOFTWARE, Severity.INFO, "GPU not used",
                reason,
                "the desktop falls back to software rendering, which is slower but always works (D-24)",
                Tuple.of(Evidence.class),
                Tuple.of(Fix.class, Fix.of("set display.gpu = \"off\" in oillamp.toml to silence this")),
                Optional.empty());
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** An unexpected failure, rendered as a problem rather than dumped as a stack trace. */
    static Problem crash(Throwable failure) {
        StringBuilder trace = new StringBuilder(failure.toString());
        StackTraceElement[] frames = failure.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, 8); i++)
            trace.append("\n  at ").append(frames[i]);
        return internal(topFrameOf(failure), reason(failure))
                .withEvidence(new Evidence.Excerpt("stack trace", trace.toString()));
    }

    private static String topFrameOf(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        return frames.length == 0 ? "unknown" : frames[0].getClassName() + "." + frames[0].getMethodName();
    }

    /**
     * A human-readable reason from an exception. Some exceptions carry no message at all, and
     * "null" is never a useful thing to show a user.
     */
    public static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static Problem warning(Code code, String title, String whatHappened, String whyItMatters) {
        return new Problem(code, Severity.WARNING, title, whatHappened, whyItMatters,
                Tuple.of(Evidence.class), Tuple.of(Fix.class), Optional.empty());
    }

    private static Problem error(Code code, String title, String whatHappened, String whyItMatters) {
        return new Problem(code, Severity.ERROR, title, whatHappened, whyItMatters,
                Tuple.of(Evidence.class), Tuple.of(Fix.class), Optional.empty());
    }
}
