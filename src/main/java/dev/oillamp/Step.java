package dev.oillamp;

import java.nio.file.Path;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// One change oillamp intends to make, such as creating a directory or starting the container.
///
/// Every change oillamp makes during setup is first described as one of these and only then
/// carried out by [StepRunner]. A dry run builds the same steps and only prints them, so what
/// `--dry-run` shows is exactly what a real run would do.
///
/// [#describe()] is one line, printed for every step. [#detail()] is the full
/// description, including the reason where there is one; `--verbose` prints it.
sealed interface Step {

    /// Whether an existing file may be overwritten.
    enum WritePolicy {
        /// Always rewrite — for files oillamp owns, like the generated ssh_config.
        ALWAYS,
        /// Only create if missing — for anything the user or the agent may have edited.
        IF_ABSENT
    }

    record InstallPackages(DistroFamily family, ValueSet<String> packages,
                           Association<String, String> reasons) implements Step {}

    record AddSubIds(String user, IdRange range) implements Step {}

    /// Runs `podman system migrate` after the id range changed; otherwise podman keeps using the old one.
    record PodmanMigrate() implements Step {}

    record CreateDirectory(Path path, PosixMode mode) implements Step {}

    record WriteFile(Path path, String content, PosixMode mode, WritePolicy policy) implements Step {}

    record CopyFile(Path from, Path to, PosixMode mode) implements Step {}

    record CreateSymlink(Path link, Path target) implements Step {}

    /// Gives a directory to a container user with `podman unshare chown`. This is how the
    /// recordings directory becomes writable only by the infra user, so the agent cannot change its
    /// own recording.
    record ChownForContainer(Path path, int containerUid, int containerGid, PosixMode mode) implements Step {}

    record GenerateSshKey(Path privateKey, String comment) implements Step {}

    record ExtractImageContext(Path targetDir) implements Step {}

    record BuildImage(ImageTag tag, Path context,
                      Association<String, String> buildArgs, boolean noCache) implements Step {}

    record RemoveContainer(ContainerName name, String reason) implements Step {}

    /// Start the sandbox container with `podman run`.
    ///
    /// The full argument list is part of the step, so `--dry-run --verbose` shows it. These
    /// flags are the sandbox's security settings, and a user should be able to check that
    /// `--network=none` is passed without reading the source.
    record RunContainer(ContainerName name, ImageTag image, Tuple<String> argv) implements Step {}

    /// Wait until the container writes `ready.json` for this session.
    ///
    /// If the container exits while starting, the problem reported includes its log.
    record AwaitReady(ContainerName name, Path readyFile, SessionId session,
                      java.time.Duration timeout) implements Step {}

    /// One socket the sandbox is expected to be answering on, and what it is for.
    record Endpoint(String what, Path socket) {}

    /// Connect to each of the sandbox's sockets before telling the user the session is ready.
    ///
    /// `ready.json` is the container's own report. This step checks it by doing what the
    /// viewer and the terminal are about to do: connect.
    record CheckEndpoints(ContainerName name, Tuple<Endpoint> endpoints) implements Step {}

    /// Files owned by a container uid, so they need `podman unshare rm` to delete.
    record DeleteContainerOwnedFiles(Tuple<Path> files, String reason) implements Step {}

    record RemovePath(Path path, String reason) implements Step {}

    /// Deletes a whole directory tree, including files owned by a container user.
    ///
    /// Unlike [RemovePath], it deletes through `podman unshare`, because part of a lamp
    /// belongs to the infra user and the lamp's owner cannot delete it directly. Symlinks are
    /// removed, never followed; the runtime directory contains one that points into the lamp.
    record RemoveTree(Path path, String reason) implements Step {}

    record WriteLampMeta(Path path, LampMeta meta) implements Step {}

    /// Stable type name, safe for tests and logs to match on.
    default String kind() { return getClass().getSimpleName(); }

    /// One line, for the console and `--dry-run`.
    default String describe() {
        return switch (this) {
            case InstallPackages s -> "install " + s.packages().size() + " package(s): " + joined(s.packages());
            case AddSubIds s       -> "give " + s.user() + " the subordinate id range " + s.range().asUsermodArgument();
            case PodmanMigrate ignored -> "re-initialise podman storage for the new id range";
            case CreateDirectory s -> "create directory " + s.path() + " (mode " + s.mode() + ")";
            case WriteFile s       -> (s.policy() == WritePolicy.IF_ABSENT ? "create if absent " : "write ")
                                      + s.path() + " (mode " + s.mode() + ")";
            case CopyFile s        -> "copy " + s.from() + " to " + s.to() + " (mode " + s.mode() + ")";
            case CreateSymlink s   -> "link " + s.link() + " -> " + s.target();
            case ChownForContainer s -> "give " + s.path() + " to container uid " + s.containerUid()
                                      + " (mode " + s.mode() + ")";
            case GenerateSshKey s  -> "generate ed25519 key " + s.privateKey();
            case ExtractImageContext s -> "extract the image build context into " + s.targetDir();
            case BuildImage s      -> (s.noCache() ? "rebuild " : "build ") + "sandbox image " + s.tag();
            case RemoveContainer s -> "remove leftover container " + s.name() + " (" + s.reason() + ")";
            case RunContainer s    -> "start sandbox container " + s.name() + " from " + s.image();
            case AwaitReady s      -> "wait for the sandbox to report itself ready (up to "
                                      + s.timeout().toSeconds() + "s)";
            case CheckEndpoints s  -> "check the sandbox answers on all " + s.endpoints().size()
                                      + " of its sockets";
            case DeleteContainerOwnedFiles s -> "delete " + s.files().size() + " file(s) owned by the sandbox ("
                                      + s.reason() + ")";
            case RemovePath s      -> "remove " + s.path() + " (" + s.reason() + ")";
            case RemoveTree s      -> "delete " + s.path() + " and everything in it (" + s.reason() + ")";
            case WriteLampMeta s   -> "write lamp identity " + s.path()
                                      + " (agent " + s.meta().agentId() + ")";
        };
    }

    /// Everything worth keeping in the log, including the reason this step exists.
    default String detail() {
        return switch (this) {
            case InstallPackages s -> {
                StringBuilder out = new StringBuilder("install missing host packages via " + s.family() + ":");
                for (String pkg : s.packages())
                    out.append("\n  ").append(pkg).append(" — ").append(s.reasons().get(pkg).orElse("required"));
                yield out.toString();
            }
            case AddSubIds s -> "sudo usermod --add-subuids " + s.range().asUsermodArgument()
                              + " --add-subgids " + s.range().asUsermodArgument() + " " + s.user()
                              + "\n  rootless podman maps the container's users onto these host ids";
            case BuildImage s -> {
                StringBuilder out = new StringBuilder("podman build --tag " + s.tag() + " " + s.context());
                for (var arg : s.buildArgs())
                    out.append("\n  --build-arg ").append(arg.first()).append('=').append(arg.second());
                yield out.toString();
            }
            case WriteFile s -> s.path() + " (mode " + s.mode() + ", " + s.policy() + ")\n"
                              + indent(s.content());
            case DeleteContainerOwnedFiles s -> {
                StringBuilder out = new StringBuilder(s.reason() + ":");
                for (Path file : s.files()) out.append("\n  ").append(file);
                yield out.toString();
            }
            // Listed one by one instead of a `default`, so that adding a Step does not compile
            // until someone decides how it is described.
            case RunContainer s -> {
                // The full argument list, one flag per line. These flags define the sandbox (no
                // network, read-only root, the uid mapping), so anyone checking what oillamp does
                // should be able to read them here.
                StringBuilder out = new StringBuilder("podman run");
                for (String argument : s.argv())
                    out.append(argument.startsWith("-") ? "\n  " : " ").append(argument);
                yield out.toString();
            }
            case CheckEndpoints s -> {
                StringBuilder out = new StringBuilder("connect to each socket the session depends on:");
                for (Endpoint endpoint : s.endpoints())
                    out.append("\n  ").append(endpoint.socket()).append(" — ").append(endpoint.what());
                yield out.toString();
            }
            case AwaitReady s -> "waiting for " + s.readyFile()
                               + "\n  written by the container once sway, the VNC server and the "
                               + "ssh listener all accept connections"
                               + "\n  it must carry session " + s.session()
                               + ", or it is the previous session's file and says nothing about this one";
            case PodmanMigrate ignored      -> describe();
            case CreateDirectory ignored    -> describe();
            case CopyFile ignored           -> describe();
            case CreateSymlink ignored      -> describe();
            case ChownForContainer ignored  -> describe();
            case GenerateSshKey ignored     -> describe();
            case ExtractImageContext ignored-> describe();
            case RemoveContainer ignored    -> describe();
            case RemovePath ignored         -> describe();
            case RemoveTree s               -> "podman unshare rm -rf " + s.path()
                               + "\n  " + s.reason()
                               + "\n  through podman's user namespace, because part of a lamp belongs to the"
                               + "\n  sandbox's own users and this user cannot delete it directly";
            case WriteLampMeta ignored      -> describe();
        };
    }

    default LampEvent.StepInfo info() {
        return new LampEvent.StepInfo(kind(), describe(), detail());
    }

    private static String joined(ValueSet<String> values) {
        return String.join(" ", sorted(values));
    }

    private static java.util.List<String> sorted(ValueSet<String> values) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String value : values) out.add(value);
        java.util.Collections.sort(out);
        return out;
    }

    private static String indent(String text) {
        return text.lines().map(line -> "    " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
