package dev.oillamp;

import dev.lamp.Problem;

import dev.lamp.Problem.Evidence;
import sprouts.Tuple;
import java.util.Optional;

/// Decides whether the sandbox desktop is drawn by the host's graphics card (`gles2`) or by
/// the processor (`pixman`).
///
/// A GPU must never stop a session. With `display.gpu = "auto"`, every unmet condition
/// means software rendering, with the reason printed. Only `"on"` turns an unmet condition
/// into an error, because the user asked for the GPU explicitly.
///
/// The conditions: a render node exists, the user is in the group that owns it, its driver is
/// one of the open-source Mesa drivers known to work without a monitor, and podman uses crun,
/// because passing the host's render group into the container needs
/// `--group-add keep-groups`, which only crun supports.
final class DesktopRendererUtil {

    private DesktopRendererUtil() {}

    /// The decision, including the reason, which is printed at startup.
    public sealed interface Decision {
        record Hardware(GpuFacts.RenderNode node) implements Decision {}
        /// Software rendering, and why.
        ///
        /// @param remedy what the user can do about it, if anything, such as the `usermod`
        ///               command that adds them to the render group
        record Software(String reason, Tuple<Problem.Fix> remedy) implements Decision {
            Software(String reason) { this(reason, Tuple.of(Problem.Fix.class)); }
        }
        record Refused(Problem problem) implements Decision {}

        /// The renderer name sway is started with: `gles2` for the GPU, `pixman` for software.
        default String renderer() {
            return this instanceof Hardware ? "gles2" : "pixman";
        }
    }

    public static final Problem.Code GPU_REQUIRED_BUT_UNAVAILABLE = new Problem.Code("OIL-GPU-003");

    public static Decision decide(GpuMode mode, GpuFacts gpu, UserInfo user, Optional<PodmanFacts> podman) {
        if (mode == GpuMode.OFF)
            return new Decision.Software("display.gpu is \"off\"");

        Optional<Obstacle> obstacle = firstObstacle(gpu, user, podman);
        if (obstacle.isEmpty()) {
            for (GpuFacts.RenderNode node : gpu.renderNodes())
                if (node.hasSupportedDriver() && user.isInGroup(node.owningGroup()))
                    return new Decision.Hardware(node);
        }

        String reason = obstacle.map(Obstacle::reason).orElse("no usable render node");
        if (mode == GpuMode.ON)
            return new Decision.Refused(new Problem(
                    GPU_REQUIRED_BUT_UNAVAILABLE, Problem.Severity.ERROR,
                    "GPU required but unavailable",
                    reason,
                    "display.gpu is set to \"on\", which means you would rather be told than "
                  + "silently get software rendering",
                    Tuple.of(Evidence.class, new Evidence.Value("render nodes",
                            gpu.renderNodes().isEmpty() ? "none" : describeNodes(gpu))),
                    Tuple.of(Problem.Fix.class,
                            Problem.Fix.of("set display.gpu = \"auto\" to fall back to software rendering"),
                            Problem.Fix.of("or fix the underlying cause named above")),
                    Optional.empty()));
        return new Decision.Software(reason,
                obstacle.map(Obstacle::remedy).orElse(Tuple.of(Problem.Fix.class)));
    }

    /// Why the GPU is not being used, and what to do about it, if anything.
    private record Obstacle(String reason, Tuple<Problem.Fix> remedy) {
        static Obstacle of(String reason) { return new Obstacle(reason, Tuple.of(Problem.Fix.class)); }
        static Obstacle of(String reason, Problem.Fix... fixes) {
            return new Obstacle(reason, Tuple.of(Problem.Fix.class, fixes));
        }
    }

    private static Optional<Obstacle> firstObstacle(GpuFacts gpu, UserInfo user, Optional<PodmanFacts> podman) {
        if (gpu.renderNodes().isEmpty())
            return Optional.of(Obstacle.of("this machine has no /dev/dri render node"));
        if (podman.isEmpty())
            return Optional.of(Obstacle.of("podman is not available, so the render node cannot be passed in"));
        if (!podman.get().supportsKeepGroups())
            return Optional.of(Obstacle.of("the OCI runtime is " + podman.get().ociRuntime()
                             + ", but passing the render group into the container needs crun"));
        for (GpuFacts.RenderNode node : gpu.renderNodes()) {
            if (!node.hasSupportedDriver())
                return Optional.of(Obstacle.of(node.path() + " is driven by " + node.driver()
                                 + ", which is not one of the open Mesa drivers oillamp trusts headless"));
            if (!user.isInGroup(node.owningGroup()))
                // oillamp prints this fix but does not run it: it needs root, it changes the
                // user's account rather than the lamp, and the new group only applies after the
                // user logs in again, so this session would stay on software rendering anyway.
                return Optional.of(Obstacle.of(
                        "you are not in the '" + node.owningGroup() + "' group that owns " + node.path(),
                        Problem.Fix.run("run this on this machine, in your own terminal, then log out "
                                      + "and back in — a new group only reaches processes started "
                                      + "after a fresh login",
                                "sudo usermod -aG " + node.owningGroup() + " " + user.name())));
        }
        return Optional.empty();
    }

    /// The line printed at startup when the GPU is not used: the reason, followed by the command
    /// that fixes it when there is one. The reason alone would leave the user to work out the fix.
    public static Optional<String> noteLine(Decision decision) {
        return noteFor(decision).map(note -> note.whatHappened()
                + note.fixes().stream()
                      .filter(fix -> fix.command().isPresent())
                      .findFirst()
                      .map(fix -> "\n           " + fix.description() + ":\n               "
                                + fix.command().orElseThrow())
                      .orElse(""));
    }

    /// The informational note shown when the session falls back to software rendering.
    public static Optional<Problem> noteFor(Decision decision) {
        return switch (decision) {
            case Decision.Hardware ignored -> Optional.empty();
            case Decision.Software software ->
                    Optional.of(ProblemCatalogUtil.gpuSoftware(software.reason(), software.remedy()));
            case Decision.Refused refused   -> Optional.of(refused.problem());
        };
    }

    private static String describeNodes(GpuFacts gpu) {
        StringBuilder out = new StringBuilder();
        for (GpuFacts.RenderNode node : gpu.renderNodes())
            out.append(out.isEmpty() ? "" : ", ")
               .append(node.path()).append(" (").append(node.driver())
               .append(", group ").append(node.owningGroup()).append(')');
        return out.toString();
    }
}
