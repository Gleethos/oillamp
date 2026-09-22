package dev.oillamp;

import dev.oillamp.Problem.Evidence;
import java.util.Optional;


/**
 * Decides whether the sandbox desktop gets hardware rendering — spec §14.3 (D-24).
 *
 * <p>The guiding rule is that a GPU is welcome but must never stop a session. In {@code auto},
 * every condition that is not met simply means software rendering, with the reason stated. Only
 * {@code on} turns an unmet condition into an error, because there the user asked explicitly.
 *
 * <p>The conditions are conservative on purpose: a render node has to exist, the user has to be
 * in its group, the driver has to be one of the open Mesa drivers known to work headless, and
 * the OCI runtime has to be crun — because passing the host's render group into the container
 * needs {@code --group-add keep-groups}, which only crun implements (§13.1).
 *
 * <p>Deliberately <b>package-private</b>: GPU selection is D-24's "auto, and never block the
 * session" policy. The fallback behaviour is what is promised; this class is how it is decided
 * today.
 */
final class Gpu {

    private Gpu() {}

    /** The outcome, including why — the reason is printed at startup and written to ready.json. */
    public sealed interface Decision {
        record Hardware(GpuFacts.RenderNode node) implements Decision {}
        record Software(String reason) implements Decision {}
        record Refused(Problem problem) implements Decision {}

        /** The wlroots renderer this decision implies (§13.4). */
        default String renderer() {
            return this instanceof Hardware ? "gles2" : "pixman";
        }
    }

    public static final Problem.Code GPU_REQUIRED_BUT_UNAVAILABLE = new Problem.Code("OIL-GPU-003");

    public static Decision decide(GpuMode mode, GpuFacts gpu, UserInfo user, Optional<PodmanFacts> podman) {
        if (mode == GpuMode.OFF)
            return new Decision.Software("display.gpu is \"off\"");

        Optional<String> obstacle = firstObstacle(gpu, user, podman);
        if (obstacle.isEmpty()) {
            for (GpuFacts.RenderNode node : gpu.renderNodes())
                if (node.hasSupportedDriver() && user.isInGroup(node.owningGroup()))
                    return new Decision.Hardware(node);
        }

        String reason = obstacle.orElse("no usable render node");
        if (mode == GpuMode.ON)
            return new Decision.Refused(new Problem(
                    GPU_REQUIRED_BUT_UNAVAILABLE, Problem.Severity.ERROR,
                    "GPU required but unavailable",
                    reason,
                    "display.gpu is set to \"on\", which means you would rather be told than "
                  + "silently get software rendering",
                    sprouts.Tuple.of(Evidence.class, new Evidence.Value("render nodes",
                            gpu.renderNodes().isEmpty() ? "none" : describeNodes(gpu))),
                    sprouts.Tuple.of(Problem.Fix.class,
                            Problem.Fix.of("set display.gpu = \"auto\" to fall back to software rendering"),
                            Problem.Fix.of("or fix the underlying cause named above")),
                    Optional.empty()));
        return new Decision.Software(reason);
    }

    private static Optional<String> firstObstacle(GpuFacts gpu, UserInfo user, Optional<PodmanFacts> podman) {
        if (gpu.renderNodes().isEmpty())
            return Optional.of("this machine has no /dev/dri render node");
        if (podman.isEmpty())
            return Optional.of("podman is not available, so the render node cannot be passed in");
        if (!podman.get().supportsKeepGroups())
            return Optional.of("the OCI runtime is " + podman.get().ociRuntime()
                             + ", but passing the render group into the container needs crun");
        for (GpuFacts.RenderNode node : gpu.renderNodes()) {
            if (!node.hasSupportedDriver())
                return Optional.of(node.path() + " is driven by " + node.driver()
                                 + ", which is not one of the open Mesa drivers oillamp trusts headless");
            if (!user.isInGroup(node.owningGroup()))
                return Optional.of("you are not in the '" + node.owningGroup()
                                 + "' group that owns " + node.path());
        }
        return Optional.empty();
    }

    /** The informational note shown when the session falls back to software rendering. */
    public static Optional<Problem> noteFor(Decision decision) {
        return switch (decision) {
            case Decision.Hardware ignored -> Optional.empty();
            case Decision.Software software -> Optional.of(Problems.gpuSoftware(software.reason()));
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
