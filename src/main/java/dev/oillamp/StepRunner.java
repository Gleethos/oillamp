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
                case Step.ExtractImageContext s -> extractImageContext(s);
                case Step.BuildImage s -> buildImage(s);
                case Step.RunContainer s -> runContainer(s);
                case Step.AwaitReady s -> awaitReady(s);
                case Step.CheckEndpoints s -> checkEndpoints(s);
            };
        } catch (IOException e) {
            return Result.err(Problems.internal("StepRunner." + step.kind(),
                    step.describe() + " failed: " + Problems.reason(e)));
        }
    }

    // ─── the image and the container ───────────────────────────────────────────────────────

    /**
     * Unpacks the image's files out of the jar and onto disk, where podman can read them.
     *
     * <p>The mode from the manifest is applied rather than assumed: an entrypoint written without
     * its executable bit gives a container that dies immediately as pid 1, and the message podman
     * reports for that names the file but not the reason.
     */
    private Result<Step> extractImageContext(Step.ExtractImageContext step) throws IOException {
        Filesystem.createDirectories(step.targetDir(), PosixMode.PUBLIC_DIR);
        for (ImageResources.Entry entry : ImageResources.entries()) {
            Path target = step.targetDir().resolve(entry.path());
            Path parent = target.getParent();
            if (parent != null) Filesystem.createDirectories(parent, PosixMode.PUBLIC_DIR);
            Filesystem.writeBytes(target, ImageResources.read(entry.path()), entry.mode());
        }
        return Result.ok(step);
    }

    private Result<Step> buildImage(Step.BuildImage step) {
        java.util.List<String> argv = new java.util.ArrayList<>(
                java.util.List.of("podman", "build", "--tag", step.tag().value()));
        if (step.noCache()) argv.add("--no-cache");
        for (var argument : step.buildArgs()) {
            argv.add("--build-arg");
            argv.add(argument.first() + "=" + argument.second());
        }
        argv.add(step.context().toString());
        // Generous: a first build installs a desktop and a JDK over the network. The user is
        // watching a progress line, not a frozen terminal, because the build streams to the log.
        Machine.Outcome outcome = run("podman build", Duration.ofMinutes(45),
                                      argv.toArray(String[]::new));
        return outcomeToResult(step, outcome, "podman build");
    }

    private Result<Step> runContainer(Step.RunContainer step) {
        java.util.List<String> argv = new java.util.ArrayList<>(java.util.List.of("podman", "run"));
        for (String argument : step.argv()) argv.add(argument);
        Machine.Outcome outcome = run("podman run", Duration.ofMinutes(2),
                                      argv.toArray(String[]::new));
        return outcomeToResult(step, outcome, "podman run");
    }

    /**
     * Waits for the container's own readiness signal, and explains a failure with its log.
     *
     * <p>Polling a file is not elegant, but it is the only signal that crosses the user-namespace
     * boundary without giving the container a way to talk back — which §16 is careful not to do.
     * The container exiting is checked on every pass, so a sandbox that dies during startup is
     * reported in a second with its own log rather than after the full timeout with nothing.
     */
    private Result<Step> awaitReady(Step.AwaitReady step) {
        java.time.Instant deadline = machine.now().plus(step.timeout());
        while (machine.now().isBefore(deadline)) {
            if (readyForThisSession(step)) return Result.ok(step);
            if (!containerIsRunning(step.name())) {
                return Result.err(Problems.sandboxDied(step.name().value(),
                        lastLinesOfContainerLog(step.name())));
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.err(Problems.internal("AwaitReady", "interrupted while waiting"));
            }
        }
        return Result.err(Problems.sandboxNotReady(step.name().value(), step.timeout(),
                lastLinesOfContainerLog(step.name())));
    }

    /**
     * True once {@code ready.json} exists <em>and</em> belongs to this session.
     *
     * <p>The sockets directory is a bind mount that outlives the container, so a file from the
     * previous session is sitting there when the wait begins. Testing only for existence makes
     * every session after the first report itself ready instantly — against a container that has
     * not started yet. The session id is already in the file, so this costs nothing.
     */
    private boolean readyForThisSession(Step.AwaitReady step) {
        return Filesystem.readString(step.readyFile())
                .filter(json -> json.contains("\"session\":\"" + step.session().value() + "\""))
                .isPresent();
    }

    /**
     * Opens every socket the session is about to depend on — spec §16.
     *
     * <p>Connecting is the only test that tells a listening server apart from a file with the
     * right name, and it is exactly what the viewer and the shell will do moments later. A
     * refusal here becomes a problem with the sandbox's own log attached, rather than a green
     * tick followed by a connection the user has to diagnose themselves.
     */
    private Result<Step> checkEndpoints(Step.CheckEndpoints step) {
        for (Step.Endpoint endpoint : step.endpoints()) {
            // socat rather than a Java Unix socket, so that this effect goes through the same
            // seam as every other one and the simulation can model a socket that refuses.
            Machine.Outcome outcome = run("socat", Duration.ofSeconds(10),
                    Tuple.of(String.class, "socat", "-u", "/dev/null",
                             "UNIX-CONNECT:" + endpoint.socket()));
            if (!outcome.succeeded())
                return Result.err(Problems.sandboxEndpointDead(endpoint.what(), endpoint.socket(),
                        step.name().value(), lastLinesOfContainerLog(step.name())));
        }
        return Result.ok(step);
    }

    private boolean containerIsRunning(ContainerName name) {
        Machine.Outcome outcome = run("podman inspect", Duration.ofSeconds(15),
                "podman", "container", "inspect", "--format", "{{.State.Running}}", name.value());
        return outcome instanceof Machine.Outcome.Finished finished
                && finished.exitCode() == 0
                && finished.standardOutput().strip().equals("true");
    }

    private String lastLinesOfContainerLog(ContainerName name) {
        Machine.Outcome outcome = run("podman logs", Duration.ofSeconds(20),
                "podman", "logs", "--tail", "20", name.value());
        if (!(outcome instanceof Machine.Outcome.Finished finished)) return "(no log available)";
        String text = (finished.standardOutput() + finished.standardError()).strip();
        return text.isEmpty() ? "(the container logged nothing)" : text;
    }

    private Result<Step> outcomeToResult(Step step, Machine.Outcome outcome, String what) {
        return switch (outcome) {
            case Machine.Outcome.Finished finished when finished.exitCode() == 0 -> Result.ok(step);
            case Machine.Outcome.Finished finished -> Result.err(Problems.podmanFailed(
                    what, finished.exitCode(),
                    (finished.standardError() + finished.standardOutput()).strip()));
            case Machine.Outcome.NotFound notFound ->
                    Result.err(Problems.commandNotFound(notFound.executable()));
            case Machine.Outcome.TimedOut timedOut ->
                    Result.err(Problems.podmanFailed(what, -1,
                            "timed out after " + timedOut.after()));
        };
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
        // Not fatal on its own: the entrypoint clears anything left here before it binds, and
        // the endpoint check afterwards catches the case where that did not work either.
        return Result.ok(step, Tuple.of(Problem.class,
                Problems.internal("cleanup", "could not delete files " + step.reason() + ": "
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
                Optional.empty(), timeout, tag, false);
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
