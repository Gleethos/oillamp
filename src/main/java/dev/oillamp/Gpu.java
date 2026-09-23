package dev.oillamp;

import dev.oillamp.Problem.Evidence;
import sprouts.Tuple;
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
        /**
         * Software rendering, and why.
         *
         * @param remedy what the user would have to do about it, when there is something — "you
         *               are not in the render group" is a fact; the {@code usermod} line that
         *               fixes it is what they actually wanted to be told
         */
        record Software(String reason, Tuple<Problem.Fix> remedy) implements Decision {
            Software(String reason) { this(reason, Tuple.of(Problem.Fix.class)); }
        }
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
                    sprouts.Tuple.of(Evidence.class, new Evidence.Value("render nodes",
                            gpu.renderNodes().isEmpty() ? "none" : describeNodes(gpu))),
                    sprouts.Tuple.of(Problem.Fix.class,
                            Problem.Fix.of("set display.gpu = \"auto\" to fall back to software rendering"),
                            Problem.Fix.of("or fix the underlying cause named above")),
                    Optional.empty()));
        return new Decision.Software(reason,
                obstacle.map(Obstacle::remedy).orElse(Tuple.of(Problem.Fix.class)));
    }

    /** Why the GPU is not being used, and — where there is one — what to do about it. */
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
                // The one obstacle with a one-line answer, and the one oillamp will not carry out
                // itself: it needs root, it changes the user's account rather than this lamp, and
                // the new group only reaches processes started after a fresh login — so doing it
                // silently would still leave this session on software rendering.
                return Optional.of(Obstacle.of(
                        "you are not in the '" + node.owningGroup() + "' group that owns " + node.path(),
                        Problem.Fix.run("run this on this machine, in your own terminal, then log out "
                                      + "and back in — a new group only reaches processes started "
                                      + "after a fresh login",
                                "sudo usermod -aG " + node.owningGroup() + " " + user.name())));
        }
        return Optional.empty();
    }

    /**
     * The startup line: the reason, and the command that answers it where there is one.
     *
     * <p>Separate from {@link #noteFor} because the reason on its own is a dead end. "You are not
     * in the 'render' group" tells a user what is wrong and leaves them to work out that the
     * answer is one {@code usermod} and a fresh login — which is exactly the question that came
     * back the first time somebody read this line.
     */
    public static Optional<String> noteLine(Decision decision) {
        return noteFor(decision).map(note -> note.whatHappened()
                + note.fixes().stream()
                      .filter(fix -> fix.command().isPresent())
                      .findFirst()
                      .map(fix -> "\n           " + fix.description() + ":\n               "
                                + fix.command().orElseThrow())
                      .orElse(""));
    }

    /** The informational note shown when the session falls back to software rendering. */
    public static Optional<Problem> noteFor(Decision decision) {
        return switch (decision) {
            case Decision.Hardware ignored -> Optional.empty();
            case Decision.Software software ->
                    Optional.of(Problems.gpuSoftware(software.reason(), software.remedy()));
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
