package dev.oillamp;

import dev.oillamp.Problem.Code;
import dev.oillamp.Problem.Evidence;
import dev.oillamp.Problem.Fix;
import dev.oillamp.Problem.Severity;
import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;

/**
 * Every problem oillamp can report, with its code and wording.
 *
 * <p>Each method builds one kind of problem with a fixed title and "why it matters", plus the
 * evidence and fixes for the case at hand. Keeping the wording here keeps the messages consistent
 * and easy to review. The codes must never change meaning, because users and scripts match on
 * them; the wording can be improved freely. The codes are also listed in
 * {@code docs/ARCHITECTURE.md}.
 */
final class Problems {

    private Problems() {}

    // ─── codes ─────────────────────────────────────────────────────────────────────────────

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
    public static final Code LAMP_INVALID_PATH     = new Code("OIL-LAMP-001");   // not reported anywhere
    public static final Code LAMP_NOT_EMPTY        = new Code("OIL-LAMP-002");
    public static final Code LAMP_FORBIDDEN_PATH   = new Code("OIL-LAMP-003");
    public static final Code LAMP_NEWER_SCHEMA     = new Code("OIL-LAMP-004");
    public static final Code LAMP_BAD_FILESYSTEM   = new Code("OIL-LAMP-005");
    public static final Code LAMP_NOT_WRITABLE     = new Code("OIL-LAMP-006");
    public static final Code LAMP_STILL_RUNNING    = new Code("OIL-LAMP-007");
    public static final Code LAMP_NOT_REMOVED      = new Code("OIL-LAMP-008");
    public static final Code NO_SUCH_RECORDING     = new Code("OIL-LAMP-009");
    public static final Code RECORDING_NOT_OPENED  = new Code("OIL-LAMP-010");
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
    public static final Code NET_FORWARD_UNREACHABLE = new Code("OIL-NET-010");
    public static final Code SESSION_NOT_RUNNING   = new Code("OIL-SESSION-001");
    public static final Code SESSION_UNREACHABLE   = new Code("OIL-SESSION-002");
    public static final Code IMAGE_BUILD_FAILED    = new Code("OIL-IMAGE-001");
    public static final Code SANDBOX_START_FAILED  = new Code("OIL-SANDBOX-001");
    public static final Code SANDBOX_DIED          = new Code("OIL-SANDBOX-002");
    public static final Code SANDBOX_NOT_READY     = new Code("OIL-SANDBOX-003");
    public static final Code SANDBOX_ENDPOINT_DEAD = new Code("OIL-SANDBOX-004");
    public static final Code SANDBOX_NOT_REMOVED   = new Code("OIL-SANDBOX-005");
    public static final Code EXEC_NOT_FOUND        = new Code("OIL-EXEC-001");
    public static final Code EXEC_TIMED_OUT        = new Code("OIL-EXEC-002");   // not reported anywhere
    public static final Code INTERNAL              = new Code("OIL-INTERNAL-001");

    // ─── network ───────────────────────────────────────────────────────────────────────────

    /**
     * A forward's target could not be reached. A warning, not an error: the service being down is
     * no reason to stop the session, but inside the sandbox it only looks like a closed connection,
     * so the user is told here.
     */
    public static Problem forwardUnreachable(Forward forward, String why) {
        return warning(NET_FORWARD_UNREACHABLE, "A forward target cannot be reached",
                "the forward '" + forward.name() + "' could not connect to " + forward.target(),
                "the sandbox sees this as a closed connection on 127.0.0.1:" + forward.port()
              + ", which says nothing about why")
            .withEvidence(new Evidence.Value("target", forward.target().toString()))
            .withEvidence(new Evidence.Value("reason", why))
            .withFix(Fix.of("check the target is up and reachable from this machine, "
                          + "including any VPN the sandbox cannot see for itself"));
    }

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
            .withFix(Fix.run("run `oillamp at <dir>`, which picks a free range and adds it; or add one "
                           + "yourself that does not overlap any other line in /etc/subuid, for example",
                             "sudo usermod --add-subuids 100000-165535 --add-subgids 100000-165535 " + user));
    }

    // ─── packages and sudo ─────────────────────────────────────────────────────────────────

    public static Problem packagesMissing(Tuple<String> missing, String installCommand,
                                          Installing installing) {
        // The second fix depends on why oillamp is not installing them itself. A `doctor` user
        // never passed --no-install, so telling them to drop it would be confusing.
        String letOillampDoIt = switch (installing) {
            case ALLOWED, DECLINED ->
                    "or let oillamp install them by running without --no-install";
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

    /**
     * {@code oillamp remove} asked to delete a lamp that is in use.
     *
     * <p>Deleting the agent's home under a running container would leave the session using
     * directories that no longer exist, and a container with no lamp left to stop it with.
     */
    public static Problem lampStillRunning(Path root, String what) {
        return new Problem(LAMP_STILL_RUNNING, Severity.ERROR, "That lamp is in use",
                what,
                "removing a lamp deletes the agent's home, and doing that under a running "
              + "container would leave the session working in directories that no longer exist",
                Tuple.of(Evidence.class, new Evidence.File(root, "the lamp being removed")),
                Tuple.of(Fix.class,
                        Fix.run("shut the session down first", "oillamp stop " + root),
                        Fix.run("then remove it", "oillamp remove " + root + " --yes")),
                Optional.empty());
    }

    /**
     * Part of a lamp survived {@code oillamp remove}.
     *
     * <p>The infra sockets and recordings belong to the infra user, which this user can only delete
     * through podman's user namespace. {@code remove} does that, so reaching this means podman was
     * missing or refused.
     */
    public static Problem lampNotRemoved(Path path, String why) {
        return error(LAMP_NOT_REMOVED, "Part of the lamp could not be removed",
                path + " is still there: " + why,
                "some of a lamp is owned by the sandbox's own users, which is what stops the "
              + "agent tampering with its recording — and what stops a plain `rm -rf` here")
            .withEvidence(new Evidence.File(path, "could not be deleted"))
            .withFix(Fix.run("delete it from inside podman's user namespace",
                    "podman unshare rm -rf " + path));
    }

    /**
     * {@code oillamp recordings --open} named a session with no recording.
     *
     * <p>Lists the sessions that do have recordings, since the usual cause is a mistyped id.
     */
    public static Problem noSuchRecording(String session, Path lamp, Path directory,
                                          Tuple<RecordingFile> existing) {
        Tuple<String> ids = Tuple.of(String.class);
        for (RecordingFile file : existing)
            if (file.session().isPresent()) ids = ids.add(file.session().get().value());
        return error(NO_SUCH_RECORDING, "No recording of that session",
                "there is no recording of session '" + session + "' in " + directory,
                ids.isEmpty()
                        ? "this lamp has no recordings at all — recording is off unless "
                        + "`recording.enabled = true` is set in oillamp.toml"
                        : "the sessions that were recorded are: " + String.join(", ", ids))
            .withFix(Fix.run("see the ones that are there", "oillamp recordings " + lamp));
    }

    /**
     * The desktop would not open a recording.
     *
     * <p>oillamp hands the file to {@code xdg-open}, so this usually means no program is set up to
     * open {@code .mkv} files, or there is no desktop.
     */
    public static Problem recordingNotOpened(Path file, String why) {
        return error(RECORDING_NOT_OPENED, "That recording could not be opened",
                why,
                "oillamp asks the desktop to open the file rather than choosing a video player "
              + "for you, so this is the desktop saying it has nothing for a .mkv")
            .withEvidence(new Evidence.File(file, "the recording"))
            .withFix(Fix.run("play it with whatever you have", "ffplay " + file))
            .withFix(Fix.of("or open " + file + " from your file manager"));
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

    // ─── the windows of a session ──────────────────────────────────────────────────────────

    /**
     * The terminal window was started but never connected.
     *
     * <p>Without a timeout, a sandbox would keep running with nobody in it and nothing to end it.
     * The cause is usually the terminal's arguments, so the fix suggests running the command by
     * hand.
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

    /** The terminal emulator itself would not start, as opposed to starting and never connecting. */
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
     * The viewer window closed straight after opening. A warning, not an error: the session still
     * works, and the user can open another viewer with {@code oillamp view}.
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

    /** A second connection to the primary SSH socket, which belongs to the terminal window only. */
    public static Problem extraPrimaryRejected(Path socket) {
        return warning(SSH_PRIMARY_TAKEN, "A second connection to the session's own socket was refused",
                socket + " accepts one connection per session, and it is already in use",
                "closing that one terminal is what ends the session, so the slot cannot be shared; "
              + "use `oillamp shell <dir>` for extra shells, which do not end anything")
            .withEvidence(new Evidence.File(socket, "the primary SSH relay"))
            .withFix(Fix.of("open extra shells with `oillamp shell <dir>`"));
    }

    // ─── reaching a running session ────────────────────────────────────────────────────────

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
     * <p>Includes the last lines of the container's log, the only place the entrypoint explains
     * why it stopped.
     */
    public static Problem sandboxDied(String container, String log) {
        return error(SANDBOX_DIED, "The sandbox stopped while starting up",
                "container " + container + " exited before it reported itself ready",
                "the desktop, the VNC server and the ssh listener all have to come up; the "
              + "entrypoint stops the sandbox rather than leave a half-working one")
            .withEvidence(new Evidence.Value("last lines of the sandbox log", log))
            .withFix(Fix.of("the log above is from inside the sandbox and names which part failed"));
    }

    /**
     * Neither {@code podman stop} nor {@code podman rm -f} worked, so the container is still there.
     * Not an internal error: it is a problem with podman or the machine, and the fix is a command.
     */
    public static Problem containerNotRemoved(String container, String stopError, String removeError) {
        return error(SANDBOX_NOT_REMOVED, "The sandbox container is still there",
                "neither stopping nor removing container " + container + " worked",
                "a container left behind keeps its memory and CPU reservations, and the next "
              + "session on this lamp will find the name taken")
            .withEvidence(new Evidence.Value("podman stop",
                    stopError.isBlank() ? "(no output)" : stopError))
            .withEvidence(new Evidence.Value("podman rm -f",
                    removeError.isBlank() ? "(no output)" : removeError))
            .withFix(Fix.run("remove it by hand", "podman rm -f " + container))
            .withFix(Fix.run("if that fails, podman itself may be wedged",
                             "podman system migrate"));
    }

    public static Problem sandboxNotReady(String container, java.time.Duration waited, String log) {
        return error(SANDBOX_NOT_READY, "The sandbox did not become ready in time",
                "container " + container + " was still starting after " + waited.toSeconds() + "s",
                "oillamp waits for the sandbox to say it is ready rather than guessing, so that a "
              + "session never starts against a desktop that is not there yet")
            .withEvidence(new Evidence.Value("last lines of the sandbox log", log))
            .withFix(Fix.of("if the log shows it was still working, the machine may just be slow; "
                          + "run oillamp again, since a second start is usually faster"))
            .withFix(Fix.run("stop the container that is still running", "podman rm -f " + container));
    }

    /**
     * The sandbox said it was ready, but one of its sockets refuses connections.
     *
     * <p>A server can die after {@code ready.json} is written, or fail to start and leave the
     * previous session's socket file in place. Either way the sandbox looks healthy until
     * something connects, which is why oillamp connects before saying the session is ready.
     */
    public static Problem sandboxEndpointDead(String what, java.nio.file.Path socket,
                                              String container, String log) {
        return error(SANDBOX_ENDPOINT_DEAD, "The sandbox is not answering on " + what,
                socket + " refused the connection, although the sandbox reported itself ready",
                "oillamp connects to every socket it is about to hand you, so that a session it "
              + "calls ready is one you can actually reach")
            .withEvidence(new Evidence.Value("socket", socket.toString()))
            .withEvidence(new Evidence.Value("last lines of the sandbox log", log))
            // Only point at the log when it has something in it.
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
                "every external command has a timeout so a hung tool can never hang oillamp")
            .withEvidence(command);
    }

    /** The user typed something oillamp does not understand. Exit code 2. */
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
            .withFix(Fix.of("re-run with --verbose and include the whole output in a bug report"));
    }

    public static Problem gpuSoftware(String reason, Tuple<Fix> remedy) {
        return new Problem(GPU_SOFTWARE, Severity.INFO, "GPU not used",
                reason,
                "the desktop falls back to software rendering, which is slower but always works",
                Tuple.of(Evidence.class),
                remedy.add(Fix.of("or set display.gpu = \"off\" in oillamp.toml to silence this")),
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

    /** A readable reason from an exception: its message, or its class name if it has none. */
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
