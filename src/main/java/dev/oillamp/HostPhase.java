package dev.oillamp;

import java.nio.file.Path;

import dev.lamp.ExitStatus;
import dev.lamp.Problem;

import sprouts.Tuple;

/// The host phase: makes sure this machine can run a sandbox. Used by `oillamp at` and, without
/// changing anything, by `doctor` and `config check`.
///
/// It probes the host, plans the fixes, runs them, and then **probes and plans again**. The
/// second round is necessary: the first probe may have run before podman was installed, so its
/// answers about podman meant nothing.
final class HostPhase {

    private final Machine machine;
    private final Context context;

    public HostPhase(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /// The facts and the outcome, so the caller can reuse the facts without probing a third time.
    ///
    /// Nothing in the result has been reported yet, warnings included: the caller reports it,
    /// once, whichever way the phase went.
    public record Outcome(HostFacts facts, Result<Plan> result) {
        public boolean succeeded() { return result.isOk(); }
    }

    /// Probes the host, fixes what it is allowed to fix, then probes again and verifies.
    ///
    /// @param installing whether this run may install what is missing. `doctor` and
    ///                   `config check` pass [Installing#NEVER], which is not the same
    ///                   as a user declining with `--no-install`: it changes what the user is
    ///                   told to do about missing packages.
    public Outcome prepare(Path lampPathHint, boolean requiresDisplay,
                           Installing installing) {
        // The package names are the same for every family oillamp knows, so one probe suffices;
        // the planner refuses a non-APT distribution afterwards, with the list to install by hand.
        HostRequirements requirements =
                HostRequirements.forFamily(DistroFamily.DEBIAN);
        HostFacts facts = HostProbe.probe(machine, requirements, lampPathHint);
        // A dry run is allowed to plan things it could not currently carry out: the user asked
        // what oillamp *would* do, and "I cannot sudo right now" is not an answer to that.
        HostPlanUtil.Options options = new HostPlanUtil.Options(
                installing, requiresDisplay, false, !context.options().dryRun());

        Result<Plan> planned = HostPlanUtil.plan(facts, requirements, options);
        if (planned instanceof Result.Err<Plan> failure)
            return new Outcome(facts, failure);

        Plan plan = ((Result.Ok<Plan>) planned).value();
        describe(facts);

        if (plan.isEmpty())
            return new Outcome(facts, planned);

        Result<Plan> executed = new StepRunner(machine, context).run(plan);
        if (executed instanceof Result.Err<Plan> failure)
            return new Outcome(facts, failure);
        Plan done = ((Result.Ok<Plan>) executed).value();
        if (context.options().dryRun())
            return new Outcome(facts, Result.ok(done, planned.warnings().addAll(executed.warnings())));

        // Re-probe: the machine is not the one we planned against any more.
        HostFacts after = HostProbe.probe(machine, requirements, lampPathHint);
        Result<Plan> verified = HostPlanUtil.plan(after, requirements, options.verifying());
        if (verified instanceof Result.Err<Plan> failure)
            return new Outcome(after, Result.err(failure.problems()));
        describe(after);
        return new Outcome(after, Result.ok(((Result.Ok<Plan>) verified).value(),
                                            verified.warnings().addAll(executed.warnings())));
    }

    private void describe(HostFacts facts) {
        context.ok("host", facts.os().prettyName() + ", " + facts.session().describe());
        facts.podman().ifPresent(podman -> context.ok("host",
                "podman " + podman.version()
                        + (podman.rootless() ? ", rootless" : ", NOT rootless")
                        + ", " + podman.ociRuntime()));
    }

    /// Exit code 3 when the host is not ready (missing packages, podman or subordinate ids), otherwise 1.
    public static ExitStatus exitStatusFor(Tuple<Problem> problems) {
        for (Problem problem : problems) {
            String code = problem.code().value();
            if (code.startsWith("OIL-PKG-") || code.startsWith("OIL-PODMAN-")
                    || code.equals("OIL-HOST-010"))
                return ExitStatus.PREREQUISITES_MISSING;
        }
        return ExitStatus.ERROR;
    }
}
