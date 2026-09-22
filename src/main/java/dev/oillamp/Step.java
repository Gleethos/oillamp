package dev.oillamp;

import java.nio.file.Path;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/**
 * One change oillamp intends to make to the world — spec §24.4.
 *
 * <p>Every effect oillamp performs during setup is first described as one of these values, and
 * only then executed. That is what makes {@code --dry-run} honest (FR-12): it is not a separate
 * code path that might drift from the real one, it is the same plan with the execution left out.
 * It is also what makes NFR-04 (transparency) cheap — the log is just the plan, annotated.
 *
 * <p>{@link #describe()} is the one line the console and {@code --dry-run} show; {@link #detail()}
 * is the full story for the session log.
 *
 * <p>Deliberately <b>package-private</b>: one described effect. Steps are the mechanism that keeps
 * {@code --dry-run} and the real run on the same code path, and adding a step kind must never be an
 * API change.
 */
sealed interface Step {

    /** Whether an existing file may be overwritten. */
    enum WritePolicy {
        /** Always rewrite — for files oillamp owns, like the generated ssh_config. */
        ALWAYS,
        /** Only create if missing — for anything the user or the agent may have edited. */
        IF_ABSENT
    }

    record InstallPackages(DistroFamily family, ValueSet<String> packages,
                           Association<String, String> reasons) implements Step {}

    record AddSubIds(String user, IdRange range) implements Step {}

    /** Re-initialises podman's storage after the id range changed — otherwise podman keeps the old map. */
    record PodmanMigrate() implements Step {}

    record CreateDirectory(Path path, PosixMode mode) implements Step {}

    record WriteFile(Path path, String content, PosixMode mode, WritePolicy policy) implements Step {}

    record CopyFile(Path from, Path to, PosixMode mode) implements Step {}

    record CreateSymlink(Path link, Path target) implements Step {}

    /**
     * Hands a directory to a container user via {@code podman unshare chown} — spec §9.2.
     * This is how the recordings directory ends up writable only by the infra user, which is
     * what stops the agent from tampering with its own recording (NFR-06).
     */
    record ChownForContainer(Path path, int containerUid, int containerGid, PosixMode mode) implements Step {}

    record GenerateSshKey(Path privateKey, String comment) implements Step {}

    record ExtractImageContext(Path targetDir) implements Step {}

    record BuildImage(ImageTag tag, Path context,
                      Association<String, String> buildArgs, boolean noCache) implements Step {}

    record RemoveContainer(ContainerName name, String reason) implements Step {}

    /**
     * Start the sandbox — spec §15.3.
     *
     * <p>The whole argument list is data, so that {@code --dry-run} can show exactly what will be
     * run. That matters more here than anywhere else in oillamp: these flags are the sandbox's
     * guarantees, and a user who wants to check that {@code --network=none} is really being passed
     * should not have to read the source to find out.
     */
    record RunContainer(ContainerName name, ImageTag image, Tuple<String> argv) implements Step {}

    /**
     * Wait for the sandbox to say it is ready, by writing {@code ready.json} — spec §16.
     *
     * <p>A step rather than a bare sleep, because it can fail in a way worth reporting: the
     * container may exit during startup, and then the useful thing to show the user is its log,
     * not a timeout.
     */
    record AwaitReady(ContainerName name, Path readyFile, SessionId session,
                      java.time.Duration timeout) implements Step {}

    /** One socket the sandbox is expected to be answering on, and what it is for. */
    record Endpoint(String what, Path socket) {}

    /**
     * Connect to each of the sandbox's sockets before calling the session good — spec §16.
     *
     * <p>{@code ready.json} is the sandbox's own account of itself, and a report written by the
     * thing being reported on is worth checking. This step is the host doing exactly what the
     * human is about to do — open the socket — so that "the desktop is ready" is a statement
     * oillamp has tested rather than one it was told.
     */
    record CheckEndpoints(ContainerName name, Tuple<Endpoint> endpoints) implements Step {}

    /** Files owned by a container uid, so they need {@code podman unshare rm} to delete. */
    record DeleteContainerOwnedFiles(Tuple<Path> files, String reason) implements Step {}

    record RemovePath(Path path, String reason) implements Step {}

    record WriteLampMeta(Path path, LampMeta meta) implements Step {}

    /** Stable type name, safe for tests and logs to match on. */
    default String kind() { return getClass().getSimpleName(); }

    /** One line, for the console and {@code --dry-run}. */
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
            case WriteLampMeta s   -> "write lamp identity " + s.path()
                                      + " (agent " + s.meta().agentId() + ")";
        };
    }

    /** Everything worth keeping in the log, including the reason this step exists. */
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
            // Listed individually rather than behind a `default`, so that adding a Step
            // forces a decision about how it appears in the log (spec section 23, rule 2).
            case RunContainer s -> {
                // The full argument list, one flag per line. These flags *are* the sandbox: no
                // network, read-only root, the user mapping of §9.2. Anyone auditing what oillamp
                // actually does should find it here rather than in the source.
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
                               + "ssh listener have all proved themselves (§16)"
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
