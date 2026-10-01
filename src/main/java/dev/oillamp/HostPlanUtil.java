package dev.oillamp;

import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// Decides what has to be fixed on the host before a sandbox can run: packages to install, a
/// subordinate id range to add, or problems to report.
///
/// It is a pure function, so situations such as "a stock Ubuntu with nothing installed",
/// "Fedora" or "podman blocked by AppArmor" are tested without a virtual machine.
///
/// - **Every problem is collected**, not just the first. A user missing three packages and a
///   subordinate id range learns all of it in one run.
/// - **Podman is not checked while it is still to be installed.** After the fixes,
///   [HostPhase] probes again and plans with [Options#afterFixes()], which plans
///   nothing and reports anything still wrong.
final class HostPlanUtil {

    private HostPlanUtil() {}

    /// How much oillamp is allowed to do, and how strict this pass is.
    ///
    /// @param installing               may oillamp change this machine, and if not, why not
    /// @param requiresGraphicalSession true for commands that open windows; false for `doctor`
    /// @param afterFixes               the second pass after the fixes: plan nothing, report everything still wrong
    /// @param willExecute              false for a dry run, where steps are only described
    public record Options(Installing installing, boolean requiresGraphicalSession,
                          boolean afterFixes, boolean willExecute) {
        public Options verifying() {
            return new Options(installing, requiresGraphicalSession, true, willExecute);
        }

        /// True only when oillamp may actually fix what it finds on this run.
        public boolean mayInstall() {
            return installing.allowed();
        }
    }

    public static Result<Plan> plan(HostFacts facts, HostRequirements requirements, Options options) {
        if (!facts.isLinux())
            return Result.err(ProblemCatalogUtil.hostNotLinux(facts.os().osName()));

        Tuple<Problem> problems = Tuple.of(Problem.class);
        Tuple<Step> steps = Tuple.of(Step.class);

        if (!facts.isAptBased())
            problems = problems.add(ProblemCatalogUtil.hostNotApt(facts.os().id(), requirements.packages()));

        if (options.requiresGraphicalSession() && !facts.hasGraphicalSession())
            problems = problems.add(ProblemCatalogUtil.hostNoGraphics());

        // ── packages ───────────────────────────────────────────────────────────────────────
        Tuple<String> missing = requirements.missingFrom(facts.installedPackages());
        // If podman is not installed at all, "podman not found" tells the user nothing they were
        // not just told. Report the missing package and leave it at that.
        boolean podmanIsAbsent = missing.contains("podman");
        if (!missing.isEmpty()) {
            if (!options.mayInstall() || options.afterFixes()) {
                problems = problems.add(ProblemCatalogUtil.packagesMissing(
                        missing, requirements.installCommand(missing), options.installing()));
            } else if (options.willExecute() && !facts.sudo().canInstall()) {
                problems = problems.add(ProblemCatalogUtil.noSudo(sudoReason(facts.sudo())));
                problems = problems.add(ProblemCatalogUtil.packagesMissing(
                        missing, requirements.installCommand(missing), options.installing()));
            } else {
                steps = steps.add(installStep(requirements, missing));
            }
        }

        // ── subordinate id range ───────────────────────────────────────────────────────────
        if (facts.subIds() instanceof SubIdFacts.Missing gap) {
            if (!options.mayInstall() || options.afterFixes()) {
                problems = problems.add(ProblemCatalogUtil.hostNoSubIds(facts.user().name()));
            } else if (options.willExecute() && !facts.sudo().canInstall()) {
                if (problems.none(p -> p.code().equals(ProblemCatalogUtil.PKG_NO_SUDO)))
                    problems = problems.add(ProblemCatalogUtil.noSudo(sudoReason(facts.sudo())));
                problems = problems.add(ProblemCatalogUtil.hostNoSubIds(facts.user().name()));
            } else {
                IdRange range = SubIdRangeUtil.allocate(
                        gap.allocatedUidRanges().addAll(gap.allocatedGidRanges()), SubIdFacts.REQUIRED_SIZE);
                steps = steps.add(new Step.AddSubIds(facts.user().name(), range));
                // podman caches the id map, so it has to be told the map changed.
                steps = steps.add(new Step.PodmanMigrate());
            }
        }

        // ── podman itself ──────────────────────────────────────────────────────────────────
        if (!podmanIsAbsent) {
            problems = problems.addAll(podmanProblems(facts));
        }

        // ── a terminal to put the shell in ─────────────────────────────────────────────────
        if (options.requiresGraphicalSession() && facts.terminals().isEmpty())
            problems = problems.add(ProblemCatalogUtil.noTerminal(TerminalEmulatorUtil.supportedNames()));

        Tuple<Problem> errors = problems.retainIf(Problem::isError);
        if (!errors.isEmpty())
            return Result.err(problems);
        return Result.ok(Plan.of(LampEvent.Phase.HOST, steps), problems);
    }

    private static Tuple<Problem> podmanProblems(HostFacts facts) {
        Tuple<Problem> problems = Tuple.of(Problem.class);
        if (facts.podman().isEmpty())
            return problems.add(ProblemCatalogUtil.podmanUnusable(
                    "`podman version --format json` did not produce a usable answer"));

        PodmanFacts podman = facts.podman().get();
        if (!podman.isAtLeastMinimum())
            problems = problems.add(ProblemCatalogUtil.podmanTooOld(podman.version(), PodmanFacts.MINIMUM_VERSION));
        if (!podman.rootless())
            problems = problems.add(ProblemCatalogUtil.podmanNotRootless(
                    "podman info reports host.security.rootless = false"));

        if (facts.userns() instanceof UserNameSpaceFacts.Fails failure) {
            problems = problems.add(failure.apparmorRestricted()
                    ? ProblemCatalogUtil.apparmorBlocked(failure.evidence())
                    : ProblemCatalogUtil.usernsBroken(failure.evidence()));
        }
        return problems;
    }

    private static Step.InstallPackages installStep(HostRequirements requirements, Tuple<String> missing) {
        ValueSet<String> packages = ValueSet.of(String.class, missing);
        Association<String, String> reasons = Association.betweenSorted(String.class, String.class);
        for (String pkg : missing)
            reasons = reasons.put(pkg, requirements.reasons().get(pkg).orElse("required by oillamp"));
        return new Step.InstallPackages(requirements.family(), packages, reasons);
    }

    private static String sudoReason(SudoFacts sudo) {
        return switch (sudo) {
            case SudoFacts.Passwordless ignored -> "sudo works";
            case SudoFacts.NeedsPassword ignored ->
                    "sudo needs a password, but oillamp was not started from an interactive terminal";
            case SudoFacts.Unavailable u -> u.reason();
        };
    }
}
