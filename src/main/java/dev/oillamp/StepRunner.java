package dev.oillamp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import sprouts.Pair;
import sprouts.Tuple;

/**
 * Carries out a {@link Plan}, step by step, and reports each step as an event.
 *
 * <p>In a dry run it only reports each step as planned and does nothing. Because it is the only
 * place steps are carried out, the dry run and the real run cannot drift apart.
 *
 * <p>The {@code switch} in {@link #perform} has no {@code default} branch, so a new kind of
 * {@link Step} does not compile until this class knows how to carry it out.
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
     * Whether this step is already done, and why. Skipped steps are reported, so it is clear why a
     * run on an existing lamp changes almost nothing.
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
                case Step.RemoveTree s -> removeTree(s);
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
     * Copies the image's files out of the jar onto disk, where {@code podman build} can read them,
     * with the modes from the manifest. Without its executable bit the entrypoint could not start.
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
        // Generous, because a first build downloads a desktop and a JDK. The build's output is
        // passed on line by line as it arrives, so the console can show what the build is doing
        // during the minutes it takes.
        Machine.Outcome outcome = runStreaming("podman build", Duration.ofMinutes(45),
                Tuple.of(String.class, argv.toArray(String[]::new)));
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
     * Waits for {@code ready.json}, checking every 250 ms, and explains a failure with the
     * container's log.
     *
     * <p>A file in the shared socket directory is a simple signal that needs no extra channel from
     * the container to the host. Each pass also checks that the container is still running, so a
     * container that dies while starting is reported at once, with its log, instead of after the
     * full timeout.
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
     * True once {@code ready.json} exists and carries this session's id.
     *
     * <p>The socket directory outlives the container, so the previous session's file may still be
     * there. Checking only that the file exists would report a new session ready before its
     * container had started.
     */
    private boolean readyForThisSession(Step.AwaitReady step) {
        return Filesystem.readString(step.readyFile())
                .filter(json -> json.contains("\"session\":\"" + step.session().value() + "\""))
                .isPresent();
    }

    /**
     * Connects to every socket the session depends on.
     *
     * <p>Connecting is the only test that tells a listening server apart from a leftover file with
     * the right name. A refusal becomes a problem with the container's log attached.
     */
    private Result<Step> checkEndpoints(Step.CheckEndpoints step) {
        for (Step.Endpoint endpoint : step.endpoints()) {
            // Uses socat through Machine rather than a Java socket, so the simulated machine can
            // model a socket that refuses connections.
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
                    // Only the end, with the error output last: podman prints the reason for a
                    // failure there, and a whole image build's output is hundreds of kilobytes.
                    tail((finished.standardOutput() + "\n" + finished.standardError()).strip(), 40)));
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
     * Gives a directory to a container user. {@code podman unshare} runs the command inside
     * podman's user namespace, where uid 1001 means the container's infra user, and podman
     * translates it to the right subordinate id on the host.
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
        // Not fatal: the entrypoint deletes these files too, and checking that the sandbox's
        // sockets answer catches the case where neither worked.
        return Result.ok(step, Tuple.of(Problem.class,
                Problems.internal("cleanup", "could not delete files " + step.reason() + ": "
                        + outcome.errorOutput().trim())));
    }

    /**
     * Deletes a directory tree that this user only partly owns.
     *
     * <p>First with {@code podman unshare rm -rf}, which can delete the infra user's files (the
     * infra sockets and the recordings). A plain {@code rm -rf} fails on those with "Permission
     * denied".
     *
     * <p>Then with a plain delete for whatever is left. That covers a machine without podman, where
     * a lamp that never ran contains only this user's files.
     */
    private Result<Step> removeTree(Step.RemoveTree step) {
        if (!Filesystem.exists(step.path())) return Result.ok(step);
        Machine.Outcome outcome = run("podman", Duration.ofMinutes(2), Tuple.of(String.class,
                "podman", "unshare", "rm", "-rf", step.path().toString()));
        if (outcome.succeeded() && !Filesystem.exists(step.path())) return Result.ok(step);

        Tuple<Path> survivors = Filesystem.deleteTree(step.path());
        if (survivors.isEmpty()) return Result.ok(step);
        return Result.err(Problems.lampNotRemoved(survivors.first(), outcome instanceof Machine.Outcome.NotFound
                ? "podman is not installed, so the files owned by the sandbox's own users are out of reach"
                : outcome.errorOutput().strip().isBlank()
                    ? "it is owned by another user"
                    : outcome.errorOutput().strip()));
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

    /** Like {@link #run(String, Duration, Tuple)}, but reports each output line as it is printed. */
    private Machine.Outcome runStreaming(String tag, Duration timeout, Tuple<String> argv) {
        Machine.Command command = new Machine.Command(argv,
                sprouts.Association.between(String.class, String.class),
                Optional.empty(), timeout, tag, false);
        context.emit(new LampEvent.Output(tag, "$ " + redacted(command)));
        // Standard output and standard error arrive on two threads; report one line at a time.
        Object oneAtATime = new Object();
        return machine.run(command, line -> {
            synchronized (oneAtATime) { context.emit(new LampEvent.Output(tag, line)); }
        });
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

    /** Masks anything that looks like a credential before a command line is reported. */
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
