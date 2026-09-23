package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;

/**
 * What each oillamp command actually does — the layer between argument parsing and the phases.
 *
 * <p>Nothing here decides anything: it sequences the phases, turns their results into exit codes
 * and lets {@link Context} carry the events out. The decisions all live in pure functions in the
 * core, which is why they are tested as such.
 *
 * <p>Deliberately <b>package-private</b>: it assembles argv for podman, ssh and apt. Those command
 * lines change whenever the tools do, which is precisely why nobody outside may depend on their
 * shape.
 */
final class Commands {

    private final Machine machine;
    private final Context context;

    public Commands(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /**
     * {@code oillamp doctor [<dir>]} — check everything, change nothing.
 *
 * <p>Deliberately does not require a graphical session, because "you are running this over
 * SSH with no display" is one of the things a user runs doctor to find out.
     */
    public ExitStatus doctor(Optional<Path> lampPath) {
        Context.Options original = context.options();
        HostPhase host = new HostPhase(machine, new Context(context::emit,
                original.withDryRun(true).withAutoInstall(false), context.version()));
        HostPhase.Outcome outcome = host.prepare(lampPath.orElse(Path.of(".")), false, Installing.NEVER);

        if (!outcome.succeeded()) {
            context.report(outcome.result().problems());
            return HostPhase.exitStatusFor(outcome.result().problems());
        }
        context.report(outcome.result().warnings());

        if (lampPath.isEmpty()) {
            context.ok("host", "this machine can run oillamp sandboxes");
            return ExitStatus.SUCCESS;
        }
        return checkConfiguration(lampPath.get(), false);
    }

    /**
     * {@code oillamp at <dir>} — prepare the host and the lamp, then run a session.
     *
     * <p>All four phases of §10.5, in order, each re-reading what the one before it changed. The
     * command does not return when the sandbox is up: it becomes the session's supervisor and
     * returns when the session is over, which is what lets one Ctrl-C, one closed window or one
     * {@code oillamp stop} take down everything it created.
     */
    public ExitStatus at(Path lampPath) {
        HostPhase.Outcome host = new HostPhase(machine, context).prepare(lampPath, true,
                context.options().autoInstall() ? Installing.ALLOWED : Installing.DECLINED);
        if (!host.succeeded()) {
            context.report(host.result().problems());
            return HostPhase.exitStatusFor(host.result().problems());
        }

        Result<LampPhase.Prepared> lamp = new LampPhase(machine, context).prepare(lampPath, host.facts());
        if (lamp instanceof Result.Err<LampPhase.Prepared> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampPhase.Prepared prepared = ((Result.Ok<LampPhase.Prepared>) lamp).value();
        context.report(lamp.warnings());

        // A dry run shows the *whole* plan, the container included, and takes no lock: it changes
        // nothing, so it must not be able to block a session that is actually running. Stopping
        // here instead would hide the one part of the plan a user most wants to inspect — the
        // podman arguments that are the sandbox's guarantees (§15.3).
        if (context.options().dryRun()) {
            Result<SandboxPhase.Running> planned =
                    new SandboxPhase(machine, context).start(prepared, host.facts());
            if (planned instanceof Result.Err<SandboxPhase.Running> failure) {
                context.report(failure.problems());
                return ExitStatus.ERROR;
            }
            context.report(planned.warnings());
            context.info("lamp", "dry run — nothing above was actually done");
            return ExitStatus.SUCCESS;
        }

        // One session per lamp (FR-04). The OS releases this when the process dies, however it
        // dies, so a crashed supervisor never blocks the next run.
        Optional<LampLock> lock;
        try {
            lock = LampLock.tryAcquire(prepared.layout().lockFile());
        } catch (java.io.IOException e) {
            context.report(Tuple.of(Problem.class,
                    Problems.lampNotWritable(prepared.layout().root(), Problems.reason(e))));
            return ExitStatus.ERROR;
        }
        if (lock.isEmpty()) {
            context.report(Tuple.of(Problem.class, busyProblem(prepared)));
            return ExitStatus.LAMP_BUSY;
        }

        LampLock held = lock.get();
        try {
            context.ok("lamp", "ready — agent " + prepared.layout().agentId()
                    + ", desktop " + prepared.config().display().size()
                    + ", renderer " + prepared.gpu().renderer());

            Result<SandboxPhase.Running> sandbox =
                    new SandboxPhase(machine, context).start(prepared, host.facts());
            if (sandbox instanceof Result.Err<SandboxPhase.Running> failure) {
                context.report(failure.problems());
                return ExitStatus.ERROR;
            }
            context.report(sandbox.warnings());
            if (context.options().dryRun()) return ExitStatus.SUCCESS;

            SandboxPhase.Running running = ((Result.Ok<SandboxPhase.Running>) sandbox).value();
            context.ok("session", "sandbox running — container " + running.container());
            // Said out loud because it is the difference between a tick that was reported and one
            // that was tested: oillamp opened both of these sockets before printing this.
            context.ok("session", "desktop and shell both answering — "
                    + "oillamp connected to each socket before handing it over");

            // From here on `at` does not return until the session is over. It opens the two
            // windows, holds the relays they come back through, and takes everything down again
            // when the user closes the terminal — which is why this call is the last thing the
            // command does rather than one more step in a list (§26.5).
            return new Supervisor(machine, context, host.facts(), prepared, running).run();
        } finally {
            try {
                held.close();
            } catch (java.io.IOException e) {
                context.report(Tuple.of(Problem.class,
                        Problems.internal("lock release", Problems.reason(e))));
            }
        }
    }

    // ─── the commands that talk to a session already running (§26.6) ───────────────────────

    /** {@code oillamp view <dir> [--view-only]} — open another window onto the same desktop. */
    public ExitStatus view(Path lampPath, boolean viewOnly) {
        return askTheSession(lampPath, "view",
                Control.Request.of("view").with("view_only", String.valueOf(viewOnly)),
                reply -> context.ok("view", "another viewer window is opening"));
    }

    /**
     * {@code oillamp shell <dir>} — an extra shell, in <em>this</em> terminal.
     *
     * <p>The supervisor hands back the command rather than running it, because the shell belongs
     * to the terminal the user typed this into (§26.6). Closing it ends nothing: only the window
     * oillamp opened itself has that power (D-09).
     */
    public ExitStatus shell(Path lampPath) {
        Result<Control.Reply> reply = askTheSession(lampPath, "shell", Control.Request.of("shell"));
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Tuple<String> argv = ((Result.Ok<Control.Reply>) reply).value().argv();
        if (argv.isEmpty()) {
            context.report(Tuple.of(Problem.class,
                    Problems.internal("shell", "the session did not say how to reach it")));
            return ExitStatus.ERROR;
        }
        context.info("shell", "connecting — closing this shell does not end the session");
        int code = machine.launch(Machine.Command.of(argv).labelled("shell"),
                                  Machine.Window.Stdio.TERMINAL).waitFor();
        return code == 0 ? ExitStatus.SUCCESS : ExitStatus.ERROR;
    }

    /**
     * {@code oillamp stop <dir>} — ask the running session to end.
     *
     * <p>Asks rather than kills: the supervisor holds the lock, the relays and the recording, and
     * a container removed behind its back would leave it believing it still had a session. When
     * there is no supervisor to ask, this cleans up after one that died instead.
     */
    public ExitStatus stop(Path lampPath) {
        Result<Control.Reply> reply = askTheSession(lampPath, "stop", Control.Request.of("stop"));
        if (reply instanceof Result.Ok<Control.Reply>) {
            context.ok("stop", "the session is shutting down");
            return ExitStatus.SUCCESS;
        }
        return cleanUpAfterACrashedSession(lampPath, reply.problems());
    }

    /**
     * {@code oillamp remove <dir>} — delete a lamp and everything in it.
     *
     * <p>This exists because a lamp cannot be deleted with {@code rm -rf}. Part of one belongs to
     * the sandbox's second user — the infra sockets and the recordings, so that the agent cannot
     * tamper with the recording of its own screen (§9.2, NFR-06) — and those map onto subordinate
     * ids that the human who owns the directory has no permission over. They find out when
     * {@code rm -rf} stops halfway with "Permission denied" on a path they have never heard of.
     *
     * <p>It deletes what oillamp created and nothing else. The lamp directory is the user's —
     * they named it, and may keep files of their own beside {@code oillamp.toml} — so the root
     * itself survives unless removal leaves it empty.
     *
     * <p>Requires {@code --yes}, because there is no prompt to answer here and this deletes the
     * agent's home — repositories, installed toolchains, everything it did. Without the flag it
     * prints exactly what would go and stops, which is the closest thing to asking.
     */
    public ExitStatus remove(Path lampPath, boolean confirmed) {
        Path root = lampPath.toAbsolutePath().normalize();
        DirListing listing = Filesystem.list(root);
        Tuple<Path> agentDirs = Tuple.of(Path.class);
        for (String entry : listing.entries())
            if (entry.startsWith(LampLayout.AGENT_DIR_PREFIX)) agentDirs = agentDirs.add(root.resolve(entry));

        boolean anythingOfOurs = !agentDirs.isEmpty()
                || Filesystem.exists(LampLayout.stateDirOf(root))
                || Filesystem.exists(LampLayout.configOf(root));
        if (!anythingOfOurs) {
            context.report(Tuple.of(Problem.class, Problems.lampNotWritable(root,
                    "this is not an oillamp lamp — there is nothing of oillamp's in " + root)));
            return ExitStatus.USAGE;
        }

        // The identity is what names the container and the runtime directory, and a lamp that was
        // half-deleted by hand no longer has one. Everything else is still addressable, so a
        // missing identity costs only those two checks — not the command.
        Optional<LampLayout> layout = layoutOf(lampPath) instanceof Result.Ok<LampLayout> ok
                ? Optional.of(ok.value())
                : Optional.empty();

        Optional<String> inUse = whatIsStillRunning(root, layout);
        if (inUse.isPresent()) {
            context.report(Tuple.of(Problem.class, Problems.lampStillRunning(root, inUse.get())));
            return ExitStatus.LAMP_BUSY;
        }

        LampPlanner.Removal found = new LampPlanner.Removal(root, agentDirs,
                layout.map(LampLayout::runtimeDir));
        context.emit(new LampEvent.Answer(describeWhatWouldGo(found)));
        if (!confirmed && !context.options().dryRun()) {
            context.emit(new LampEvent.Answer("Nothing has been removed. To go ahead:\n\n"
                    + "  oillamp remove " + root + " --yes"));
            return ExitStatus.USAGE;
        }

        Result<Plan> done = new StepRunner(machine, context).run(LampPlanner.planRemoval(found));
        if (done instanceof Result.Err<Plan> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.report(done.warnings());
        if (context.options().dryRun()) return ExitStatus.SUCCESS;

        context.ok("remove", "the lamp is gone" + removeTheRootIfEmpty(root));
        return ExitStatus.SUCCESS;
    }

    /**
     * Whether anything would be pulled out from under a running session.
     *
     * <p>Two questions, because they fail differently. A supervisor that answers is a session the
     * user is probably still looking at. A container with no supervisor is the wreck of one that
     * died — which {@code oillamp stop} already knows how to clear up, so the answer in both
     * cases is the same and says so.
     */
    private Optional<String> whatIsStillRunning(Path root, Optional<LampLayout> layout) {
        Optional<String> container = runningSandboxFor(root);
        if (container.isPresent())
            return Optional.of("the sandbox container " + container.get()
                    + " is still running for this lamp");
        if (layout.isPresent() && Control.ask(layout.get().controlSocket(), root,
                Control.Request.of("status"), "remove") instanceof Result.Ok<Control.Reply>)
            return Optional.of("a session is running: its supervisor is answering on "
                    + layout.get().controlSocket());
        if (layout.isPresent() && machine.run(Machine.Command.of("podman", "container", "exists",
                layout.get().containerName().value()).labelled("podman container exists")).succeeded())
            return Optional.of("the sandbox container " + layout.get().containerName().value()
                    + " is still there, left behind by a session that did not finish");
        return Optional.empty();
    }

    /**
     * Any sandbox podman is running for this lamp, found by the label rather than by the name.
     *
     * <p>The name is derived from the lamp's identity, and the case that most needs this check is
     * the one where that identity is gone: a lamp somebody started deleting by hand. The
     * container still carries the lamp's path as a label, so podman can answer even when the
     * directory no longer can — which is what stops {@code remove} deleting a lamp out from under
     * a sandbox that is still up.
     */
    private Optional<String> runningSandboxFor(Path root) {
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "ps", "--filter", "label=oillamp.agent-id", "--format", "json")
                .withTimeout(java.time.Duration.ofSeconds(20)).labelled("podman ps"));
        if (!outcome.succeeded()) return Optional.empty();
        try {
            com.fasterxml.jackson.databind.JsonNode listing =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(outcome.output());
            if (!listing.isArray()) return Optional.empty();
            for (com.fasterxml.jackson.databind.JsonNode container : listing) {
                com.fasterxml.jackson.databind.JsonNode labels = container.get("Labels");
                if (labels == null || !text(labels, "oillamp.lamp").equals(root.toString())) continue;
                com.fasterxml.jackson.databind.JsonNode names = container.get("Names");
                return Optional.of(names != null && names.isArray() && !names.isEmpty()
                        ? names.get(0).asText()
                        : text(container, "Names"));
            }
        } catch (com.fasterxml.jackson.core.JacksonException unreadable) {
            // A podman that answers with something else is a reason to fall back to the checks
            // below, not a reason to decide the lamp is free.
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The list a user reads before typing {@code --yes}.
     *
     * <p>The agent's home is listed entry by entry rather than as a total in megabytes, because
     * the question being asked is not "how much" but "what": one of those names is usually a
     * repository with work in it, and a number would not show that.
     */
    private static String describeWhatWouldGo(LampPlanner.Removal found) {
        StringBuilder out = new StringBuilder("`oillamp remove` permanently deletes:\n\n");
        for (Path agentDir : found.agentDirs()) {
            out.append("  ").append(agentDir.getFileName()).append("/\n      the agent's home");
            DirListing home = Filesystem.list(agentDir);
            if (!home.readable())    out.append(" (cannot be read from here)");
            else if (home.isEmpty()) out.append(" (empty)");
            else                     out.append(" — ").append(String.join(", ", home.entries()));
            out.append('\n');
        }
        if (Filesystem.exists(LampLayout.stateDirOf(found.root())))
            out.append("  .oillamp/\n      this lamp's identity, keys, logs, recordings and sockets\n");
        if (Filesystem.exists(LampLayout.configOf(found.root())))
            out.append("  oillamp.toml\n      your configuration for this lamp\n");
        if (Filesystem.exists(LampLayout.readmeOf(found.root())))
            out.append("  README.txt\n");
        found.runtimeDir().ifPresent(runtime -> out.append("  ").append(runtime)
                .append("\n      the sockets this lamp uses while it runs\n"));
        out.append("\nThe directory itself, ").append(found.root())
           .append(", stays — unless this empties it.\n");
        return out.toString();
    }

    /**
     * Takes the lamp directory too, but only if oillamp was all that was in it.
     *
     * <p>A lamp created by {@code oillamp at <new dir>} should not leave an empty directory
     * behind; one the user has kept their own files in must not take those with it. Nothing
     * records which of the two this was — but by this point the directory itself answers.
     */
    private String removeTheRootIfEmpty(Path root) {
        DirListing left = Filesystem.list(root);
        if (!left.readable() || !left.isEmpty())
            return " — " + root + " itself is untouched";
        try {
            Filesystem.deleteIfPresent(root);
            return ", and " + root + " with it: oillamp was all that was in it";
        } catch (java.io.IOException e) {
            return " — " + root + " is empty now, but could not be removed: " + Problems.reason(e);
        }
    }

    /** {@code oillamp status <dir>} — what the running session is doing. */
    public ExitStatus status(Path lampPath) {
        Result<Control.Reply> reply = askTheSession(lampPath, "status", Control.Request.of("status"));
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Control.Reply answer = ((Result.Ok<Control.Reply>) reply).value();
        StringBuilder out = new StringBuilder();
        for (String key : java.util.List.of("state", "detail", "lamp", "session", "container",
                                            "desktop", "renderer", "uptime", "shells", "viewer"))
            answer.values().get(key).ifPresent(value ->
                    out.append(pad(key)).append(value).append('\n'));
        context.emit(new LampEvent.Answer(out.toString().stripTrailing()));
        return ExitStatus.SUCCESS;
    }

    /**
     * {@code oillamp list} — every sandbox running on this host.
     *
     * <p>Asks podman rather than keeping a list of its own. A second list beside the one the
     * container runtime already maintains is a list that can be wrong, and it would be wrong in
     * exactly the case it is needed: after a supervisor was killed.
     */
    public ExitStatus list() {
        // JSON rather than a --format template. Templates reach into podman's own internal
        // struct, and the field that holds a label is spelled differently between podman 4 and
        // 5 — a listing that works here and not on the user's machine is worse than no listing.
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "ps", "--filter", "label=oillamp.agent-id", "--format", "json")
                .withTimeout(java.time.Duration.ofSeconds(20)).labelled("podman ps"));
        if (!outcome.succeeded()) {
            context.report(Tuple.of(Problem.class, Problems.podmanFailed(
                    "podman ps", outcome.exitCode(), outcome.errorOutput().strip())));
            return ExitStatus.ERROR;
        }
        Tuple<String> rows = describeRunningSandboxes(outcome.output());
        context.emit(new LampEvent.Answer(rows.isEmpty()
                ? "no oillamp sandboxes are running on this host"
                : String.join("\n", rows)));
        return ExitStatus.SUCCESS;
    }

    /** Turns {@code podman ps --format json} into one line per sandbox, lamp path included. */
    private static Tuple<String> describeRunningSandboxes(String json) {
        Tuple<String> rows = Tuple.of(String.class);
        try {
            com.fasterxml.jackson.databind.JsonNode listing =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!listing.isArray()) return rows;
            for (com.fasterxml.jackson.databind.JsonNode container : listing) {
                com.fasterxml.jackson.databind.JsonNode labels = container.get("Labels");
                com.fasterxml.jackson.databind.JsonNode names = container.get("Names");
                String name = names != null && names.isArray() && !names.isEmpty()
                        ? names.get(0).asText()
                        : text(container, "Names");
                String lamp = labels == null ? "" : text(labels, "oillamp.lamp");
                rows = rows.add(column(name) + column(text(container, "State")) + lamp);
            }
        } catch (com.fasterxml.jackson.core.JacksonException unreadable) {
            // A podman that answers with something other than the JSON it was asked for is a
            // reason to show nothing, not a reason to fail the command.
            return Tuple.of(String.class);
        }
        return rows.isEmpty() ? rows
                : Tuple.of(String.class, column("CONTAINER") + column("STATE") + "LAMP").addAll(rows);
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        return value == null ? "" : value.asText();
    }

    // ─── reaching the supervisor ───────────────────────────────────────────────────────────

    private ExitStatus askTheSession(Path lampPath, String command, Control.Request request,
                                     java.util.function.Consumer<Control.Reply> onSuccess) {
        Result<Control.Reply> reply = askTheSession(lampPath, command, request);
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        onSuccess.accept(((Result.Ok<Control.Reply>) reply).value());
        return ExitStatus.SUCCESS;
    }

    private Result<Control.Reply> askTheSession(Path lampPath, String command, Control.Request request) {
        Result<LampLayout> layout = layoutOf(lampPath);
        if (layout instanceof Result.Err<LampLayout> failure) return Result.err(failure.problems());
        LampLayout found = ((Result.Ok<LampLayout>) layout).value();
        return Control.ask(found.controlSocket(), found.root(), request, command);
    }

    /**
     * Finds a lamp's paths without setting anything up.
     *
     * <p>{@code view}, {@code shell}, {@code stop} and {@code status} must not create, migrate or
     * repair anything: they are questions about a session that is already running, and a command
     * that fixed a lamp on its way to asking one would be the last thing a user wants from
     * {@code status}.
     */
    private Result<LampLayout> layoutOf(Path lampPath) {
        Path root = lampPath.toAbsolutePath().normalize();
        LampState state = LampClassifier.classify(root, Filesystem.list(root),
                Filesystem.readString(root.resolve(".oillamp").resolve("lamp.json")));
        if (!(state instanceof LampState.Existing existing))
            return Result.err(Problems.lampNotWritable(root,
                    "this is not an oillamp lamp — run `oillamp at " + lampPath + "` to make one"));
        return Result.ok(new LampLayout(root, existing.meta().agentId(), runtimeDirectory()));
    }

    private Path runtimeDirectory() {
        return machine.environmentVariable("XDG_RUNTIME_DIR")
                .map(Path::of)
                .orElseGet(() -> Path.of("/run/user/" + machine.run(Machine.Command.of("id", "-u"))
                        .output().strip()));
    }

    /**
     * There is no supervisor. Either nothing is running, or one was killed without tidying up —
     * and FR-08 is explicit that the second case must not need manual cleanup.
     */
    private ExitStatus cleanUpAfterACrashedSession(Path lampPath, Tuple<Problem> why) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        ContainerName container = layout.containerName();
        boolean wasThere = machine.run(Machine.Command
                .of("podman", "container", "exists", container.value())
                .labelled("podman container exists")).succeeded();
        if (!wasThere) {
            context.report(why);
            return exitStatusFor(why);
        }
        context.info("stop", "no supervisor is running, but its sandbox is — cleaning up after it");
        Machine.Outcome removed = machine.run(Machine.Command
                .of("podman", "rm", "-f", container.value())
                .withTimeout(java.time.Duration.ofSeconds(30)).labelled("podman rm"));
        if (!removed.succeeded()) {
            context.report(Tuple.of(Problem.class, Problems.podmanFailed(
                    "podman rm", removed.exitCode(), removed.errorOutput().strip())));
            return ExitStatus.ERROR;
        }
        try {
            Filesystem.deleteIfPresent(layout.sessionMeta());
        } catch (java.io.IOException e) {
            context.report(Tuple.of(Problem.class, Problems.internal("session.json", Problems.reason(e))));
        }
        context.ok("stop", "the sandbox left by the previous session has been removed");
        return ExitStatus.SUCCESS;
    }

    /** The label column of {@code status}. */
    private static String pad(String label) { return padTo(label, 12); }

    /** The wider columns of {@code list}, which hold container names and paths. */
    private static String column(String value) { return padTo(value, 22); }

    private static String padTo(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    /** {@code oillamp config <dir> check} — validate without touching anything. */
    public ExitStatus checkConfig(Path lampPath) {
        HostPhase.Outcome host = new HostPhase(machine, new Context(context::emit,
                context.options().withDryRun(true).withAutoInstall(false), context.version()))
                .prepare(lampPath, false, Installing.NEVER);
        context.report(host.result().warnings());
        return checkConfiguration(lampPath, true);
    }

    /** {@code oillamp config <dir> show-effective} — print the merged, validated configuration. */
    public ExitStatus showEffectiveConfig(Path lampPath) {
        Result<LampConfig> loaded = loadConfig(lampPath);
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.report(failure.problems());
            return ExitStatus.USAGE;
        }
        LampConfig config = ((Result.Ok<LampConfig>) loaded).value();
        context.emit(new LampEvent.Answer(describe(config)));
        return ExitStatus.SUCCESS;
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private ExitStatus checkConfiguration(Path lampPath, boolean quiet) {
        Result<LampConfig> loaded = loadConfig(lampPath);
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.report(failure.problems());
            return ExitStatus.USAGE;
        }
        context.report(loaded.warnings());
        if (!quiet) context.ok("lamp", "configuration is valid");
        else context.ok("config", "valid");
        return ExitStatus.SUCCESS;
    }

    private Result<LampConfig> loadConfig(Path lampPath) {
        Tuple<ConfigSource> sources = Tuple.of(ConfigSource.class);
        Path lampConfig = lampPath.resolve("oillamp.toml");
        Optional<String> text = Filesystem.readString(lampConfig);
        if (text.isEmpty())
            return Result.err(Problems.lampNotWritable(lampPath,
                    "there is no oillamp.toml here — run `oillamp at " + lampPath + "` to create one"));
        return ConfigLoader.load(sources.add(ConfigSource.lamp(lampConfig, text.get())));
    }

    private static String describe(LampConfig config) {
        return "display      " + config.display().size() + " scale " + config.display().scale()
                + " gpu " + config.display().gpu().configName() + "\n"
             + "viewer       clipboard " + config.viewer().clipboard().configName()
                + ", " + config.viewer().maxFps() + " fps"
                + (config.viewer().viewOnly() ? ", view only" : "") + "\n"
             + "recording    " + (config.recording().enabled()
                    ? config.recording().codec() + " crf " + config.recording().crf()
                      + ", keep " + config.recording().maxAgeDays() + " days / "
                      + config.recording().maxTotalGb() + " GB"
                    : "off") + "\n"
             + "limits       memory " + config.limits().memory()
                + ", cpus " + (config.limits().cpus() == 0 ? "all but one" : config.limits().cpus())
                + ", pids " + config.limits().pids() + "\n"
             + "network      default " + config.network().defaultDecision().configName()
                + ", " + config.network().rules().size() + " rule(s), "
                + config.forwards().size() + " forward(s)\n"
             + "image        " + config.image().base() + ", " + config.image().jdkPackage()
                + ", node " + config.image().nodeVersion();
    }

    private Problem busyProblem(LampPhase.Prepared prepared) {
        Optional<String> sessionMeta = Filesystem.readString(prepared.layout().sessionMeta());
        return Problems.lockBusy(prepared.layout().root(),
                extractLong(sessionMeta, "supervisorPid").orElse(0L),
                extract(sessionMeta, "startedAt").orElse("an earlier time"));
    }

    private static Optional<String> extract(Optional<String> json, String key) {
        return json.flatMap(text -> {
            int at = text.indexOf('"' + key + '"');
            if (at < 0) return Optional.empty();
            int colon = text.indexOf(':', at);
            int start = text.indexOf('"', colon + 1);
            int end = start < 0 ? -1 : text.indexOf('"', start + 1);
            return start < 0 || end < 0 ? Optional.empty() : Optional.of(text.substring(start + 1, end));
        });
    }

    private static Optional<Long> extractLong(Optional<String> json, String key) {
        return json.flatMap(text -> {
            int at = text.indexOf('"' + key + '"');
            if (at < 0) return Optional.empty();
            int colon = text.indexOf(':', at);
            StringBuilder digits = new StringBuilder();
            for (int i = colon + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isDigit(c)) digits.append(c);
                else if (digits.length() > 0) break;
            }
            return digits.isEmpty() ? Optional.empty() : Optional.of(Long.parseLong(digits.toString()));
        });
    }

    /** Maps a lamp-phase failure to the exit code the spec promises (§27.5). */
    public static ExitStatus exitStatusFor(Tuple<Problem> problems) {
        for (Problem problem : problems) {
            String code = problem.code().value();
            if (code.equals("OIL-LOCK-001")) return ExitStatus.LAMP_BUSY;
            if (code.startsWith("OIL-CONFIG-")) return ExitStatus.USAGE;
        }
        for (Problem problem : problems)
            if (problem.isError()) return ExitStatus.ERROR;
        return ExitStatus.SUCCESS;
    }

    /** Used by the entry point to announce itself before any phase runs. */
    public static Plan noPlan() { return Plan.nothingToDo(LampEvent.Phase.HOST); }
}
