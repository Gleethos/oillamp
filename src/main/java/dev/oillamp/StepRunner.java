package dev.oillamp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import sprouts.Pair;
import sprouts.Tuple;

/**
 * Carries out a {@link Plan} — spec §24.4.
 *
 * <p>The switch below has no {@code default} branch, so adding a {@link Step} is a compile error
 * here until someone decides how to perform it. That is the whole point of modelling effects as
 * data: a new kind of change cannot be introduced without being executed, logged and dry-runnable.
 *
 * <p>In dry-run mode nothing happens at all — the same plan is announced rather than performed,
 * which is what makes {@code --dry-run} trustworthy instead of a parallel code path that drifts
 * (FR-12, NFR-04).
 *
 * <p>Deliberately <b>package-private</b>: it executes a plan and emits events. On the effects
 * allowlist.
 */
final class StepRunner {

    private final Machine machine;
    private final Context context;

    public StepRunner(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /** Runs every step, stopping at the first failure. Returns the problems it collected. */
    public Result<Plan> run(Plan plan) {
        context.emit(new LampEvent.PhaseStarted(plan.phase()));
        Instant phaseStarted = machine.now();
        Tuple<Problem> warnings = Tuple.of(Problem.class);

        for (Step step : plan.steps()) {
            if (context.options().dryRun()) {
                context.emit(new LampEvent.StepPlanned(step.info()));
                continue;
            }
            Optional<String> skip = reasonToSkip(step);
            if (skip.isPresent()) {
                context.emit(new LampEvent.StepSkipped(step.info(), skip.get()));
                continue;
            }
            context.emit(new LampEvent.StepStarted(step.info()));
            Instant started = machine.now();
            Result<Step> outcome = perform(step);
            switch (outcome) {
                case Result.Ok<Step> ok -> {
                    warnings = warnings.addAll(ok.warnings());
                    context.emit(new LampEvent.StepSucceeded(step.info(),
                            Duration.between(started, machine.now())));
                }
                case Result.Err<Step> err -> {
                    return Result.err(err.problems().addAll(warnings));
                }
            }
        }
        context.emit(new LampEvent.PhaseFinished(plan.phase(),
                Duration.between(phaseStarted, machine.now())));
        return Result.ok(plan, warnings);
    }

    /**
     * Whether this step is already done. Reported rather than silently passed over, so the log
     * shows why a run on an existing lamp is nearly empty.
     */
    private Optional<String> reasonToSkip(Step step) {
        return switch (step) {
            case Step.WriteFile write when write.policy() == Step.WritePolicy.IF_ABSENT
                                            && Filesystem.exists(write.path()) ->
                    Optional.of("already exists — leaving your version alone");
            case Step.GenerateSshKey key when Filesystem.exists(key.privateKey()) ->
                    Optional.of("the key already exists");
            case Step.CreateDirectory directory when Filesystem.exists(directory.path()) ->
                    Optional.of("already exists");
            case Step ignored -> Optional.empty();
        };
    }

    private Result<Step> perform(Step step) {
        try {
            return switch (step) {
                case Step.CreateDirectory s -> {
                    Filesystem.createDirectory(s.path(), s.mode());
                    yield Result.ok(step);
                }
                case Step.WriteFile s -> {
                    Filesystem.writeFile(s.path(), s.content(), s.mode());
                    yield Result.ok(step);
                }
                case Step.CopyFile s -> {
                    Filesystem.copyFile(s.from(), s.to(), s.mode());
                    yield Result.ok(step);
                }
                case Step.CreateSymlink s -> {
                    Filesystem.createSymlink(s.link(), s.target());
                    yield Result.ok(step);
                }
                case Step.WriteLampMeta s -> {
                    Filesystem.writeFile(s.path(), LampClassifier.render(s.meta()),
                            PosixMode.PUBLIC_FILE);
                    yield Result.ok(step);
                }
                case Step.RemovePath s -> {
                    Filesystem.deleteIfPresent(s.path());
                    yield Result.ok(step);
                }
                case Step.GenerateSshKey s -> generateKey(s);
                case Step.InstallPackages s -> installPackages(s);
                case Step.AddSubIds s -> addSubIds(s);
                case Step.PodmanMigrate ignored -> podman(step, "system", "migrate");
                case Step.ChownForContainer s -> chownForContainer(s);
                case Step.RemoveContainer s -> podman(step, "rm", "-f", s.name().value());
                case Step.DeleteContainerOwnedFiles s -> deleteContainerOwnedFiles(s);
                case Step.ExtractImageContext ignored ->
                        Result.err(Problems.internal("StepRunner",
                                "building the sandbox image arrives with milestone M3"));
                case Step.BuildImage ignored ->
                        Result.err(Problems.internal("StepRunner",
                                "building the sandbox image arrives with milestone M3"));
            };
        } catch (IOException e) {
            return Result.err(Problems.internal("StepRunner." + step.kind(),
                    step.describe() + " failed: " + Problems.reason(e)));
        }
    }

    // ─── the steps that shell out ──────────────────────────────────────────────────────────

    private Result<Step> generateKey(Step.GenerateSshKey step) {
        Machine.Outcome outcome = run("ssh-keygen", Duration.ofSeconds(30),
                "ssh-keygen", "-t", "ed25519", "-N", "", "-C", step.comment(),
                "-f", step.privateKey().toString());
        if (outcome.succeeded()) return Result.ok(step);
        return Result.err(Problems.sshKeygenFailed(evidenceOf(outcome,
                "ssh-keygen", "-t", "ed25519", "-N", "", "-C", step.comment(),
                "-f", step.privateKey().toString())));
    }

    private Result<Step> installPackages(Step.InstallPackages step) {
        Tuple<String> argv = Tuple.of(String.class, "sudo", "apt-get", "install", "-y",
                                      "--no-install-recommends");
        for (String pkg : step.packages()) argv = argv.add(pkg);
        Machine.Outcome outcome = run("apt-get", Duration.ofMinutes(10), argv);
        if (outcome.succeeded()) return Result.ok(step);
        return Result.err(Problems.installFailed(
                evidenceOf(outcome, argv), context.installLog().orElse(Path.of("(no log)"))));
    }

    private Result<Step> addSubIds(Step.AddSubIds step) {
        String range = step.range().asUsermodArgument();
        Tuple<String> argv = Tuple.of(String.class, "sudo", "usermod",
                "--add-subuids", range, "--add-subgids", range, step.user());
        Machine.Outcome outcome = run("usermod", Duration.ofSeconds(60), argv);
        if (outcome.succeeded()) return Result.ok(step);
        return Result.err(Problems.hostNoSubIds(step.user())
                .withEvidence(evidenceOf(outcome, argv)));
    }

    /**
     * Hands a directory to a container user. {@code podman unshare} runs inside the user
     * namespace, so "uid 1001" means the container's infra user and podman maps it to the right
     * host subuid itself — no arithmetic on {@code /etc/subuid} needed (§9.2).
     */
    private Result<Step> chownForContainer(Step.ChownForContainer step) {
        Tuple<String> argv = Tuple.of(String.class, "podman", "unshare", "chown",
                step.containerUid() + ":" + step.containerGid(), step.path().toString());
        Machine.Outcome outcome = run("podman", Duration.ofSeconds(30), argv);
        if (!outcome.succeeded())
            return Result.err(Problems.lampNotWritable(step.path(),
                    "could not hand " + step.path() + " to the sandbox's infra user")
                    .withEvidence(evidenceOf(outcome, argv)));
        return Result.ok(step);
    }

    private Result<Step> deleteContainerOwnedFiles(Step.DeleteContainerOwnedFiles step) {
        Tuple<String> argv = Tuple.of(String.class, "podman", "unshare", "rm", "-f");
        for (Path file : step.files()) argv = argv.add(file.toString());
        Machine.Outcome outcome = run("podman", Duration.ofSeconds(60), argv);
        if (outcome.succeeded()) return Result.ok(step);
        // Retention failing is a nuisance, not a reason to refuse to start a session.
        return Result.ok(step, Tuple.of(Problem.class,
                Problems.internal("retention", "could not delete old recordings: "
                        + outcome.errorOutput().trim())));
    }

    private Result<Step> podman(Step step, String... arguments) {
        Tuple<String> argv = Tuple.of(String.class, "podman").addAll(arguments);
        Machine.Outcome outcome = run("podman", Duration.ofMinutes(2), argv);
        if (outcome.succeeded()) return Result.ok(step);
        return Result.err(Problems.internal("podman " + String.join(" ", arguments),
                outcome.errorOutput().trim()).withEvidence(evidenceOf(outcome, argv)));
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private Machine.Outcome run(String tag, Duration timeout, String... argv) {
        return run(tag, timeout, Tuple.of(String.class, argv));
    }

    private Machine.Outcome run(String tag, Duration timeout, Tuple<String> argv) {
        Machine.Command command = new Machine.Command(argv,
                sprouts.Association.between(String.class, String.class),
                Optional.empty(), timeout, tag);
        context.emit(new LampEvent.Output(tag, "$ " + redacted(command)));
        Machine.Outcome outcome = machine.run(command);
        for (String line : outcome.errorOutput().split("\n", -1))
            if (!line.isBlank()) context.emit(new LampEvent.Output(tag, line));
        return outcome;
    }

    /** Secrets never reach the log: anything that looks like a credential is masked (§26.1). */
    private static String redacted(Machine.Command command) {
        StringBuilder out = new StringBuilder();
        for (String token : command.argv())
            out.append(out.isEmpty() ? "" : " ").append(looksSecret(token) ? "***" : token);
        for (Pair<String, String> variable : command.environment())
            if (looksSecret(variable.first()))
                out.append(' ').append(variable.first()).append("=***");
        return out.toString();
    }

    private static boolean looksSecret(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("KEY=") || upper.contains("TOKEN") || upper.contains("PASSWORD")
            || upper.endsWith("_KEY");
    }

    private static Problem.Evidence.Command evidenceOf(Machine.Outcome outcome, String... argv) {
        return evidenceOf(outcome, Tuple.of(String.class, argv));
    }

    private static Problem.Evidence.Command evidenceOf(Machine.Outcome outcome, Tuple<String> argv) {
        return new Problem.Evidence.Command(argv, outcome.exitCode(),
                tail(outcome.errorOutput(), 10), Duration.ZERO);
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n", -1);
        int from = Math.max(0, all.length - lines);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < all.length; i++)
            out.append(out.isEmpty() ? "" : "\n").append(all[i]);
        return out.toString();
    }
}
