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
    public static final Code NET_SOCKET_PATH_LONG  = new Code("OIL-NET-001");
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

    public static Problem sshKeygenFailed(Evidence.Command command) {
        return error(SSH_KEYGEN_FAILED, "Key generation failed",
                "ssh-keygen exited with code " + command.exitCode(),
                "the lamp needs its own key pair; oillamp never reuses your personal SSH keys for "
              + "the sandbox")
            .withEvidence(command);
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

    private static Problem error(Code code, String title, String whatHappened, String whyItMatters) {
        return new Problem(code, Severity.ERROR, title, whatHappened, whyItMatters,
                Tuple.of(Evidence.class), Tuple.of(Fix.class), Optional.empty());
    }
}
