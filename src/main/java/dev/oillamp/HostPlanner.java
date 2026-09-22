package dev.oillamp;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/**
 * Decides what has to be fixed on the host before a sandbox can run — spec §11, Phase A of §10.5.
 *
 * <p>Pure, so that "a stock Ubuntu with nothing installed" and "Fedora" and "podman present but
 * blocked by AppArmor" are all unit tests rather than virtual machines.
 *
 * <p>Two properties matter more than they look:
 * <ul>
 *   <li><b>Every deficiency is collected</b>, not just the first. A user missing three packages
 *       and a subuid range should learn all of it in one run (§27.1).</li>
 *   <li><b>Podman checks are skipped while podman is still being installed.</b> On a fresh machine
 *       the first pass cannot ask a program that is not there yet; §10.5 re-probes after the
 *       fixes and the second pass ({@link Options#afterFixes()}) insists that everything is right.</li>
 * </ul>
 *
 * <p>Deliberately <b>package-private</b>: the decision "what must be fixed before a sandbox can
 * run". Users meet it through {@code doctor} and {@code at --dry-run}, which is the only shape of
 * it worth promising.
 */
final class HostPlanner {

    private HostPlanner() {}

    /**
     * How much oillamp is allowed to do, and how strict this pass is.
     *
     * @param autoInstall              may oillamp change this machine? ({@code --no-install} / {@code host.auto_install})
     * @param requiresGraphicalSession true for commands that open windows; false for {@code doctor}
     * @param afterFixes               the verification pass after Phase A's fixes: plan nothing, demand everything
     * @param willExecute              false for a dry run, where steps are only described
     */
    public record Options(boolean autoInstall, boolean requiresGraphicalSession,
                          boolean afterFixes, boolean willExecute) {
        public Options verifying() {
            return new Options(autoInstall, requiresGraphicalSession, true, willExecute);
        }
    }

    public static Result<Plan> plan(HostFacts facts, HostRequirements requirements, Options options) {
        if (!facts.isLinux())
            return Result.err(Problems.hostNotLinux(facts.os().osName()));

        Tuple<Problem> problems = Tuple.of(Problem.class);
        Tuple<Step> steps = Tuple.of(Step.class);

        if (!facts.isAptBased())
            problems = problems.add(Problems.hostNotApt(facts.os().id(), requirements.packages()));

        if (options.requiresGraphicalSession() && !facts.hasGraphicalSession())
            problems = problems.add(Problems.hostNoGraphics());

        // ── packages ───────────────────────────────────────────────────────────────────────
        Tuple<String> missing = requirements.missingFrom(facts.installedPackages());
        // If podman is not installed at all, "podman not found" tells the user nothing they were
        // not just told. Report the missing package and leave it at that.
        boolean podmanIsAbsent = missing.contains("podman");
        if (!missing.isEmpty()) {
            if (!options.autoInstall() || options.afterFixes()) {
                problems = problems.add(Problems.packagesMissing(missing, requirements.installCommand(missing)));
            } else if (options.willExecute() && !facts.sudo().canInstall()) {
                problems = problems.add(Problems.noSudo(sudoReason(facts.sudo())));
                problems = problems.add(Problems.packagesMissing(missing, requirements.installCommand(missing)));
            } else {
                steps = steps.add(installStep(requirements, missing));
            }
        }

        // ── subordinate id range ───────────────────────────────────────────────────────────
        if (facts.subIds() instanceof SubIdFacts.Missing gap) {
            if (!options.autoInstall() || options.afterFixes()) {
                problems = problems.add(Problems.hostNoSubIds(facts.user().name()));
            } else if (options.willExecute() && !facts.sudo().canInstall()) {
                if (problems.none(p -> p.code().equals(Problems.PKG_NO_SUDO)))
                    problems = problems.add(Problems.noSudo(sudoReason(facts.sudo())));
                problems = problems.add(Problems.hostNoSubIds(facts.user().name()));
            } else {
                IdRange range = SubIdAllocator.allocate(
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
            problems = problems.add(Problems.noTerminal(Terminals.supportedNames()));

        Tuple<Problem> errors = problems.retainIf(Problem::isError);
        if (!errors.isEmpty())
            return Result.err(problems);
        return Result.ok(Plan.of(LampEvent.Phase.HOST, steps), problems);
    }

    private static Tuple<Problem> podmanProblems(HostFacts facts) {
        Tuple<Problem> problems = Tuple.of(Problem.class);
        if (facts.podman().isEmpty())
            return problems.add(Problems.podmanUnusable(
                    "`podman version --format json` did not produce a usable answer"));

        PodmanFacts podman = facts.podman().get();
        if (!podman.isAtLeastMinimum())
            problems = problems.add(Problems.podmanTooOld(podman.version(), PodmanFacts.MINIMUM_VERSION));
        if (!podman.rootless())
            problems = problems.add(Problems.podmanNotRootless(
                    "podman info reports host.security.rootless = false"));

        if (facts.userns() instanceof UsernsFacts.Fails failure) {
            problems = problems.add(failure.apparmorRestricted()
                    ? Problems.apparmorBlocked(failure.evidence())
                    : Problems.usernsBroken(failure.evidence()));
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
