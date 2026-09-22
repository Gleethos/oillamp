package dev.oillamp;

import sprouts.Tuple;

/**
 * Phase A: make this machine able to run a sandbox — spec §10.5, §11.
 *
 * <p>Runs the cycle the spec insists on: probe, plan, execute, then <b>probe again and verify</b>.
 * The second probe is not belt-and-braces — the first one ran on a machine that did not have
 * podman yet, so its answers about podman were meaningless. Only after the fixes have been
 * applied can oillamp truthfully say the host is ready.
 *
 * <p>Deliberately <b>package-private</b>: Phase A of §10.5, wired together. On the effects
 * allowlist. Users meet it as {@code doctor} and as the first half of {@code at}.
 */
final class HostPhase {

    private final Machine machine;
    private final Context context;

    public HostPhase(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /** The facts and the outcome, so the caller can reuse the facts without probing a third time. */
    public record Outcome(HostFacts facts, Result<Plan> result) {
        public boolean succeeded() { return result.isOk(); }
    }

    /**
     * Probes the host, fixes what it is allowed to fix, then probes again and verifies.
     *
     * @param installing whether this run may install what is missing. {@code doctor} and
     *                   {@code config check} pass {@link Installing#NEVER}, which is not the same
     *                   as a user declining with {@code --no-install}: it changes what the user is
     *                   told to do about missing packages.
     */
    public Outcome prepare(java.nio.file.Path lampPathHint, boolean requiresDisplay,
                           Installing installing) {
        // The package names are the same for every family oillamp knows, so one probe suffices;
        // the planner refuses a non-APT distribution afterwards, with the list to install by hand.
        HostRequirements requirements =
                HostRequirements.forFamily(DistroFamily.DEBIAN);
        HostFacts facts = HostProbe.probe(machine, requirements, lampPathHint);
        // A dry run is allowed to plan things it could not currently carry out: the user asked
        // what oillamp *would* do, and "I cannot sudo right now" is not an answer to that.
        HostPlanner.Options options = new HostPlanner.Options(
                installing, requiresDisplay, false, !context.options().dryRun());

        Result<Plan> planned = HostPlanner.plan(facts, requirements, options);
        if (planned instanceof Result.Err<Plan> failure)
            return new Outcome(facts, failure);

        Plan plan = ((Result.Ok<Plan>) planned).value();
        describe(facts);

        if (plan.isEmpty()) {
            context.report(planned.warnings());
            return new Outcome(facts, planned);
        }

        Result<Plan> executed = new StepRunner(machine, context).run(plan);
        if (executed instanceof Result.Err<Plan> failure)
            return new Outcome(facts, failure);
        if (context.options().dryRun())
            return new Outcome(facts, executed);

        // Re-probe: the machine is not the one we planned against any more.
        HostFacts after = HostProbe.probe(machine, requirements, lampPathHint);
        Result<Plan> verified = HostPlanner.plan(after, requirements, options.verifying());
        if (verified instanceof Result.Err<Plan> failure)
            return new Outcome(after, Result.err(failure.problems()));
        describe(after);
        context.report(verified.warnings());
        return new Outcome(after, verified);
    }

    private void describe(HostFacts facts) {
        context.ok("host", facts.os().prettyName() + ", " + facts.session().describe());
        facts.podman().ifPresent(podman -> context.ok("host",
                "podman " + podman.version()
                        + (podman.rootless() ? ", rootless" : ", NOT rootless")
                        + ", " + podman.ociRuntime()));
    }

    /** The exit code the spec assigns to a host that is not ready — {@code 3}, not a generic error. */
    public static ExitStatus exitStatusFor(Tuple<Problem> problems) {
        for (Problem problem : problems) {
            String code = problem.code().value();
            if (code.startsWith("OIL-PKG-") || code.startsWith("OIL-PODMAN-")
                    || code.equals("OIL-HOST-010"))
                return ExitStatus.PREREQUISITES_MISSING;
        }
        return ExitStatus.ERROR;
    }

    /** Announces the phase even when there is nothing to do, so the console reads consistently. */
    public void announce() {
        context.emit(new LampEvent.PhaseStarted(LampEvent.Phase.HOST));
    }
}
