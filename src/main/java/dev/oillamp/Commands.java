package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;
import dev.lamp.LampEvent.SaveKind;
import dev.lamp.Problem;

import sprouts.Tuple;

/// One method per oillamp command. [Invocation] parses the command line and calls these.
///
/// These methods run the phases in order, turn their results into exit codes, and report
/// through [Context]. The decisions are made by the pure planners they call.
final class Commands {

    private final Machine machine;
    private final Context context;

    public Commands(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /// `oillamp doctor [<dir>]`: check the host (and the lamp's configuration), change nothing.
    ///
    /// Does not require a graphical session, because finding out that you are on a plain SSH
    /// login is one of the reasons to run it.
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

    /// `oillamp at <dir>`: prepare the host and the lamp, then run a session.
    ///
    /// Runs the phases in order: host, lamp, image and sandbox, then the [Supervisor]. It
    /// does not return when the sandbox is up, but when the session is over, so that one Ctrl-C,
    /// one closed window or one `oillamp stop` takes down everything it created.
    public ExitStatus at(Path lampPath) {
        // Only a session that opens windows needs the host's display.
        boolean opensWindows = !context.options().embedded() && context.options().openWindows();
        HostPhase.Outcome host = new HostPhase(machine, context).prepare(lampPath, opensWindows, installing(lampPath));
        if (!host.succeeded()) {
            context.report(host.result().problems());
            return HostPhase.exitStatusFor(host.result().problems());
        }
        context.report(host.result().warnings());

        Result<LampPhase.Prepared> lamp = new LampPhase(machine, context).prepare(lampPath, host.facts());
        if (lamp instanceof Result.Err<LampPhase.Prepared> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampPhase.Prepared prepared = ((Result.Ok<LampPhase.Prepared>) lamp).value();
        context.report(lamp.warnings());

        // A dry run also plans the image and container steps, so the full podman command is
        // shown. It takes no lock, so it cannot block a session that is really running.
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

        // One session per lamp. The OS releases the lock when the process dies, however it dies,
        // so a crashed supervisor never blocks the next run.
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
        // Before the sandbox runs, so the snapshot holds the lamp exactly as the last session
        // left it, and this session can always be undone. A save that fails is a warning: the
        // session is still worth having.
        for (Problem problem : saveLamp(context, prepared.layout(), SaveKind.STARTUP, "",
                                        Optional.of(prepared.session()), machine.now()))
            context.emit(new LampEvent.Warning(problem));
        // Until the supervisor takes over, nothing else would remove a container this run started:
        // not a failed start, and not a Ctrl-C while the image builds or the desktop comes up.
        ContainerName container = prepared.layout().containerName();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread abandon = new Thread(() -> {
            interrupted.set(true);
            context.info("session", "interrupted while the sandbox was starting — removing it");
            removeUnfinishedSandbox(container, "interrupted while the sandbox was starting");
        }, "oillamp-abandon-start");
        Runtime.getRuntime().addShutdownHook(abandon);
        try {
            context.ok("lamp", "ready — agent " + prepared.layout().agentId()
                    + ", desktop " + prepared.config().display().size()
                    + ", renderer " + prepared.gpu().renderer());

            Result<SandboxPhase.Running> sandbox =
                    new SandboxPhase(machine, context).start(prepared, host.facts());
            if (sandbox instanceof Result.Err<SandboxPhase.Running> failure) {
                // After a Ctrl-C the start fails because the hook removed the container. Saying
                // "the sandbox stopped while starting up" would blame the sandbox for the user's
                // own interrupt.
                if (interrupted.get()) return ExitStatus.INTERRUPTED;
                context.report(failure.problems());
                removeUnfinishedSandbox(container, "it did not start");
                return ExitStatus.ERROR;
            }
            context.report(sandbox.warnings());

            SandboxPhase.Running running = ((Result.Ok<SandboxPhase.Running>) sandbox).value();
            context.ok("session", "sandbox running — container " + running.container());
            // oillamp connected to both sockets (CheckEndpoints) before printing this.
            context.ok("session", "desktop and shell both answering — "
                    + "oillamp connected to each socket before handing it over");

            // From here `at` does not return until the session is over, and the supervisor
            // removes the container however the session ends.
            stopWatching(abandon);
            return new Supervisor(machine, context, host.facts(), prepared, running).run();
        } finally {
            stopWatching(abandon);
            try {
                held.close();
            } catch (java.io.IOException e) {
                context.report(Tuple.of(Problem.class,
                        Problems.internal("lock release", Problems.reason(e))));
            }
        }
    }

    /// Removes the container a start left behind, if there is one. Called when the start failed,
    /// and from a shutdown hook when the user pressed Ctrl-C before the supervisor took over.
    private void removeUnfinishedSandbox(ContainerName container, String why) {
        boolean exists = machine.run(Machine.Command.of("podman", "container", "exists", container.value())
                .withTimeout(java.time.Duration.ofSeconds(20)).labelled("podman container exists")
                .shieldedFromSignals()).succeeded();
        if (!exists) return;
        Result<Plan> removed = new StepRunner(machine, context).run(Plan.of(LampEvent.Phase.SESSION,
                Tuple.of(Step.class, new Step.RemoveContainer(container, why))));
        if (removed instanceof Result.Err<Plan> failure) context.report(failure.problems());
        else context.info("session", "removed the sandbox that did not start, so nothing is left running");
    }

    private static void stopWatching(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The JVM is already running the hook; it removes the container itself.
        }
    }

    /// Whether `at` may install host packages: not with `--no-install`, and not when the
    /// configuration says `host.auto_install = false`.
    ///
    /// The host is prepared before the lamp, so the configuration is read here once already. If it
    /// cannot be read, installing stays allowed; the lamp phase reports what is wrong with it.
    private Installing installing(Path lampPath) {
        if (!context.options().autoInstall()) return Installing.DECLINED;
        Result<LampConfig> config = ConfigLoader.load(LampPhase.configurationFiles(home(),
                lampPath.toAbsolutePath().resolve("oillamp.toml")));
        return config instanceof Result.Ok<LampConfig> ok && !ok.value().host().autoInstall()
                ? Installing.DECLINED_IN_CONFIG
                : Installing.ALLOWED;
    }

    private Path home() {
        return machine.environmentVariable("HOME").map(Path::of).orElse(Path.of("/nonexistent"));
    }

    // ─── commands that talk to a running session through its control socket ─────────────

    /// `oillamp view <dir> [--view-only]`: open another viewer onto the same desktop.
    public ExitStatus view(Path lampPath, boolean viewOnly) {
        return askTheSession(lampPath, "view",
                Control.Request.of("view").with("view_only", String.valueOf(viewOnly)),
                reply -> context.ok("view", "another viewer window is opening"));
    }

    /// `oillamp shell <dir>`: an extra shell in this terminal.
    ///
    /// The supervisor returns the ssh command and this process runs it, because the shell belongs
    /// in the terminal the user typed this into. Closing it does not end the session.
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

    /// `oillamp stop <dir>`: ask the running session to end.
    ///
    /// It asks the supervisor rather than removing the container, because the supervisor holds
    /// the lock, the relays and the recording and must run its own shutdown. If no supervisor
    /// answers, it removes a container left behind by one that died.
    public ExitStatus stop(Path lampPath) {
        Result<Control.Reply> reply = askTheSession(lampPath, "stop", Control.Request.of("stop"));
        if (reply instanceof Result.Ok<Control.Reply>) {
            context.ok("stop", "the session is shutting down");
            return ExitStatus.SUCCESS;
        }
        return cleanUpAfterACrashedSession(lampPath, reply.problems());
    }

    /// `oillamp remove <dir>...`: delete one lamp or several.
    ///
    /// A lamp cannot be deleted with `rm -rf`: the infra sockets and the recordings belong
    /// to the infra user, which is a subordinate id on the host that the lamp's owner cannot delete
    /// files of. This command deletes them through `podman unshare`.
    ///
    /// It deletes only what oillamp created. The lamp directory itself is removed only if nothing
    /// else is left in it, because the user may keep their own files there.
    ///
    /// It requires `--yes`, because it deletes the agent's home and all its work, and there
    /// is no interactive prompt. Without `--yes` it lists what would be deleted and exits 2.
    ///
    /// Several lamps are usually named by a shell pattern such as `test*`, which may match more
    /// than the user meant. So every one is checked before anything is deleted: if any of them is
    /// not a lamp, or is still running, nothing is removed and each problem is reported.
    public ExitStatus remove(Tuple<Path> lampPaths, boolean confirmed) {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        for (Path lampPath : lampPaths) roots.add(LampPhase.resolve(machine, lampPath));

        Tuple<LampPlanner.Removal> removals = Tuple.of(LampPlanner.Removal.class);
        Tuple<Problem> refusals = Tuple.of(Problem.class);
        boolean anyInUse = false;
        for (Path root : roots) {
            Result<LampPlanner.Removal> examined = examineForRemoval(root);
            if (examined instanceof Result.Ok<LampPlanner.Removal> ok) removals = removals.add(ok.value());
            else refusals = refusals.addAll(examined.problems());
            anyInUse |= examined.problems().stream().anyMatch(p -> p.code().equals(Problems.LAMP_STILL_RUNNING));
        }
        if (!refusals.isEmpty()) {
            context.report(refusals);
            if (roots.size() > 1)
                context.emit(new LampEvent.Answer("Nothing has been removed: " + refusals.size() + " of the "
                        + roots.size() + " directories cannot be. Name only the lamps to remove."));
            // A lamp in use is worth its own exit code: stopping it is all it takes to retry.
            return anyInUse ? ExitStatus.LAMP_BUSY : ExitStatus.USAGE;
        }

        boolean several = removals.size() > 1;
        for (LampPlanner.Removal found : removals)
            context.emit(new LampEvent.Answer((several ? "── " + found.root() + "\n\n" : "")
                    + describeWhatWouldGo(found)));
        if (!confirmed && !context.options().dryRun()) {
            StringBuilder command = new StringBuilder("oillamp remove");
            for (LampPlanner.Removal found : removals) command.append(' ').append(found.root());
            context.emit(new LampEvent.Answer("Nothing has been removed. To go ahead"
                    + (several ? " and delete all " + removals.size() + " lamps" : "") + ":\n\n  "
                    + command + " --yes"));
            return ExitStatus.USAGE;
        }

        // Every lamp was checked above and the user confirmed them all, so one that fails to
        // delete does not stop the others.
        Tuple<Problem> failures = Tuple.of(Problem.class);
        int removed = 0;
        for (LampPlanner.Removal found : removals) {
            Result<Plan> done = new StepRunner(machine, context).run(LampPlanner.planRemoval(found));
            if (done instanceof Result.Err<Plan> failure) {
                context.report(failure.problems());
                failures = failures.addAll(failure.problems());
                continue;
            }
            context.report(done.warnings());
            if (context.options().dryRun()) continue;
            removed++;
            // The rest of the sentence names the directory, so it needs no prefix for several.
            context.ok("remove", "the lamp is gone" + removeTheRootIfEmpty(found.root()));
        }
        if (several && !context.options().dryRun())
            context.emit(new LampEvent.Answer(removed == removals.size()
                    ? "All " + removed + " lamps were removed."
                    : removed + " of " + removals.size() + " lamps were removed; the others are "
                      + "reported above."));
        return failures.isEmpty() ? ExitStatus.SUCCESS : exitStatusFor(failures);
    }

    /// What `remove` would delete for one lamp, or why it must not: the directory holds nothing
    /// of oillamp's, or the lamp is still running.
    private Result<LampPlanner.Removal> examineForRemoval(Path root) {
        DirListing listing = Filesystem.list(root);
        Tuple<Path> agentDirs = Tuple.of(Path.class);
        for (String entry : listing.entries())
            if (entry.startsWith(LampLayout.AGENT_DIR_PREFIX)) agentDirs = agentDirs.add(root.resolve(entry));

        boolean anythingOfOurs = !agentDirs.isEmpty()
                || Filesystem.exists(LampLayout.stateDirOf(root))
                || Filesystem.exists(LampLayout.configOf(root));
        if (!anythingOfOurs)
            return Result.err(Problems.lampNotWritable(root,
                    "this is not an oillamp lamp — there is nothing of oillamp's in " + root));

        // A lamp half-deleted by hand may have lost lamp.json, and with it the agent id that names
        // the container and the runtime directory. Everything else can still be removed.
        Optional<LampLayout> layout = layoutOf(root) instanceof Result.Ok<LampLayout> ok
                ? Optional.of(ok.value())
                : Optional.empty();

        Optional<String> inUse = whatIsStillRunning(root, layout);
        if (inUse.isPresent())
            return Result.err(Problems.lampStillRunning(root, inUse.get()));

        return Result.ok(new LampPlanner.Removal(root, agentDirs, layout.map(LampLayout::runtimeDir)));
    }

    /// What is still running for this lamp, if anything: a container, a supervisor that answers, or
    /// a container left behind by a supervisor that died. In each case the user is told to run
    /// `oillamp stop` first.
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

    /// A container podman is running for this lamp, found by its `oillamp.lamp` label.
    ///
    /// Found by label rather than by name, because the name comes from `lamp.json`, which
    /// may already have been deleted by hand. The label holds the lamp's path.
    private Optional<String> runningSandboxFor(Path root) {
        Machine.Outcome outcome = listRunningSandboxes();
        if (!outcome.succeeded()) return Optional.empty();
        return runningSandboxesIn(outcome.output()).stream()
                .filter(sandbox -> sandbox.lamp().equals(root.toString()))
                .map(RunningSandbox::name).findFirst();
    }

    /// One oillamp container, as `podman ps` describes it.
    private record RunningSandbox(String name, String state, String lamp) {}

    /// Every container with an `oillamp.agent-id` label that podman is running.
    ///
    /// JSON rather than a --format template: the template field names differ between podman
    /// 4 and 5, and this must work with both.
    private Machine.Outcome listRunningSandboxes() {
        return machine.run(Machine.Command
                .of("podman", "ps", "--filter", "label=oillamp.agent-id", "--format", "json")
                .withTimeout(java.time.Duration.ofSeconds(20)).labelled("podman ps"));
    }

    /// Reads the output of [#listRunningSandboxes]. Output that is not the expected JSON counts
    /// as no containers at all, rather than as a failure.
    private static Tuple<RunningSandbox> runningSandboxesIn(String json) {
        Tuple<RunningSandbox> found = Tuple.of(RunningSandbox.class);
        com.fasterxml.jackson.databind.JsonNode listing =
                Json.parse(json).orElse(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        if (!listing.isArray()) return found;
        for (com.fasterxml.jackson.databind.JsonNode container : listing) {
            // podman 4 and 5 give the names as a list; older versions as one string.
            com.fasterxml.jackson.databind.JsonNode names = container.path("Names");
            String name = names.isArray() && !names.isEmpty() ? names.get(0).asText() : names.asText("");
            found = found.add(new RunningSandbox(name, Json.text(container, "State"),
                    container.path("Labels").path("oillamp.lamp").asText("")));
        }
        return found;
    }

    /// The list of what `remove` would delete, shown before the user adds `--yes`. The
    /// agent's home is listed entry by entry, so the user can see which repositories would go.
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

    /// Removes the lamp directory itself, but only if nothing else is left in it.
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

    /// `oillamp status <dir>`: what the running session is doing.
    public ExitStatus status(Path lampPath) {
        Result<Control.Reply> reply = askTheSession(lampPath, "status", Control.Request.of("status"));
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Control.Reply answer = ((Result.Ok<Control.Reply>) reply).value();
        StringBuilder out = new StringBuilder();
        for (String key : Tuple.of(String.class, "state", "detail", "lamp", "session", "container",
                                            "desktop", "renderer", "uptime", "shells", "agent", "viewer"))
            answer.values().get(key).ifPresent(value ->
                    out.append(pad(key, 12)).append(value).append('\n'));
        context.emit(new LampEvent.Answer(out.toString().stripTrailing()));
        return ExitStatus.SUCCESS;
    }

    /// `oillamp list`: every oillamp container running on this host, found by its
    /// `oillamp.agent-id` label. oillamp keeps no list of its own that could go out of date.
    public ExitStatus list() {
        Machine.Outcome outcome = listRunningSandboxes();
        if (!outcome.succeeded()) {
            context.report(Tuple.of(Problem.class, Problems.podmanFailed(
                    "podman ps", outcome.exitCode(), outcome.errorOutput().strip())));
            return ExitStatus.ERROR;
        }
        Tuple<RunningSandbox> running = runningSandboxesIn(outcome.output());
        StringBuilder rows = new StringBuilder(pad("CONTAINER", 22) + pad("STATE", 22) + "LAMP");
        for (RunningSandbox sandbox : running)
            rows.append('\n').append(pad(sandbox.name(), 22)).append(pad(sandbox.state(), 22))
                .append(sandbox.lamp());
        context.emit(new LampEvent.Answer(running.isEmpty()
                ? "no oillamp sandboxes are running on this host"
                : rows.toString()));
        return ExitStatus.SUCCESS;
    }


    // ─── the lamp's history ────────────────────────────────────────────────────────────────

    /// `oillamp save <dir> [--message <text>]`: take a snapshot of the lamp now.
    ///
    /// Works while a session is running, so a person can save just before letting the agent try
    /// something risky. The snapshot then says it was taken while the session ran, because a
    /// program writing at that moment may have left a file half written.
    public ExitStatus save(Path lampPath, String message) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        // Holding the lamp's lock while saving also keeps a session from starting halfway through.
        Optional<LampLock> idle;
        try {
            idle = LampLock.tryAcquire(layout.lockFile());
        } catch (java.io.IOException e) {
            context.report(Tuple.of(Problem.class, Problems.lampNotWritable(layout.root(), Problems.reason(e))));
            return ExitStatus.ERROR;
        }
        try {
            Optional<SessionId> session = idle.isPresent() ? Optional.empty()
                    : Filesystem.readString(layout.sessionMeta()).flatMap(Json::parse)
                                .flatMap(json -> SessionId.parse(Json.text(json, "session")));
            if (idle.isEmpty())
                context.info("history", "a session is running, so programs in the sandbox may be "
                        + "writing while this saves; the snapshot is marked as a running save");
            Tuple<Problem> problems = saveLamp(context, layout,
                    idle.isPresent() ? SaveKind.IDLE : SaveKind.RUNNING, message, session, machine.now());
            context.report(problems);
            return exitStatusFor(problems);
        } finally {
            idle.ifPresent(Commands::release);
        }
    }

    /// `oillamp history <dir>`: every snapshot of the lamp, newest first.
    public ExitStatus history(Path lampPath) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Result<Tuple<LampEvent.Snapshot>> snapshots = new History(((Result.Ok<LampLayout>) found).value()).snapshots();
        if (snapshots instanceof Result.Err<Tuple<LampEvent.Snapshot>> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.emit(new LampEvent.History(((Result.Ok<Tuple<LampEvent.Snapshot>>) snapshots).value()));
        return ExitStatus.SUCCESS;
    }

    /// `oillamp restore <dir> <snapshot>`: bring the lamp back to an earlier snapshot.
    ///
    /// Refused while a session runs. The lamp is saved first, so every restore can be undone by
    /// restoring that save.
    public ExitStatus restore(Path lampPath, String snapshot) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Optional<LampLock> lock;
        try {
            lock = LampLock.tryAcquire(layout.lockFile());
        } catch (java.io.IOException e) {
            context.report(Tuple.of(Problem.class, Problems.lampNotWritable(layout.root(), Problems.reason(e))));
            return ExitStatus.ERROR;
        }
        if (lock.isEmpty()) {
            Tuple<Problem> refused = Tuple.of(Problem.class, Problems.restoreWhileRunning(layout.root()));
            context.report(refused);
            return exitStatusFor(refused);
        }
        try {
            Result<History.Restoring> restored = new History(layout).restore(snapshot, machine.now());
            context.report(restored.warnings());
            if (restored instanceof Result.Err<History.Restoring> failure) {
                context.report(failure.problems());
                return exitStatusFor(failure.problems());
            }
            History.Restoring done = ((Result.Ok<History.Restoring>) restored).value();
            done.safety().ifPresent(safety -> context.emit(new LampEvent.Saved(safety, 0)));
            context.emit(new LampEvent.Restored(done.target(), done.result().orElse(done.target())));
            if (done.result().isEmpty()) return ExitStatus.SUCCESS;
            done.safety().ifPresent(safety -> context.info("history", "to undo this, run: oillamp restore "
                    + layout.root() + " " + safety.shortId()));
            return ExitStatus.SUCCESS;
        } finally {
            release(lock.get());
        }
    }

    /// Saves a lamp, reports the snapshot or that nothing changed, and returns the problems to
    /// report. Also used by `at` as a session starts and by the supervisor as it ends.
    static Tuple<Problem> saveLamp(Context context, LampLayout layout, SaveKind kind, String message,
                                   Optional<SessionId> session, java.time.Instant now) {
        Result<History.Saving> saved = new History(layout).save(kind, message, session, now);
        if (saved instanceof Result.Err<History.Saving> failure) return failure.problems();
        History.Saving saving = ((Result.Ok<History.Saving>) saved).value();
        if (saving.made().isPresent())
            context.emit(new LampEvent.Saved(saving.made().get(), saving.files()));
        else
            context.info("history", saving.latest()
                    .map(latest -> "nothing changed since " + latest.shortId() + " (" + latest.kind().label()
                                 + "), so there was nothing to save")
                    .orElse("nothing to save yet"));
        return saving.skipped().isEmpty() ? Tuple.of(Problem.class)
                : Tuple.of(Problem.class, Problems.filesNotSaved(layout.root(), saving.skipped()));
    }

    // ─── the schedule, and asking the agent ────────────────────────────────────────────────

    /// What `oillamp schedule` was asked to do.
    ///
    /// @param action   `list`, `add`, `remove`, `enable`, `disable`, `pause` or `resume`
    /// @param argument the job for `remove`, `enable` and `disable`; the prompt for `add`
    record ScheduleAction(String action, Optional<String> argument, Optional<String> cron,
                          Optional<String> at, Optional<String> expires) {}

    /// `oillamp schedule <dir> [<action>]`: list the lamp's jobs, or change them.
    ///
    /// Works whether or not a session runs. A running session reads the schedule again when it
    /// changes, so a job added now can run straight away.
    public ExitStatus schedule(Path lampPath, ScheduleAction request) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Result<LampConfig> config = loadConfig(layout.root());
        if (config instanceof Result.Err<LampConfig> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.Schedule limits = ((Result.Ok<LampConfig>) config).value().schedule();
        ScheduleBook book = new ScheduleBook(layout);
        java.time.ZoneId zone = machine.zone();
        java.time.Instant now = machine.now();
        String action = request.action();
        Result<LampEvent> done = switch (action) {
            case "list" -> book.read().map(schedule -> describe(schedule, limits, zone));
            case "add" -> book.update(schedule -> schedule.add(new Schedule.Request(request.cron(), request.at(),
                            request.argument().orElse(""), request.expires()),
                            LampEvent.JobAuthor.USER, now, zone, limits), Schedule.Changed::schedule)
                    .map(added -> new LampEvent.JobAdded(added.job().describe(zone)));
            case "remove" -> book.update(schedule -> schedule.remove(request.argument().orElse(""),
                            LampEvent.JobAuthor.USER, layout.root()), Schedule.Changed::schedule)
                    .map(removed -> new LampEvent.JobRemoved(removed.job().describe(zone), "removed by you"));
            case "enable", "disable" -> book.update(schedule -> schedule.enable(request.argument().orElse(""),
                            action.equals("enable"), now, layout.root()), Schedule.Changed::schedule)
                    .map(changed -> new LampEvent.ScheduleChanged(changed.job().id() + " is switched "
                            + (changed.job().enabled() ? "on" : "off")));
            case "pause", "resume" -> book.update(schedule -> Result.ok(schedule.paused(action.equals("pause"))),
                            java.util.function.Function.<Schedule>identity())
                    .map(changed -> new LampEvent.ScheduleChanged(changed.paused()
                            ? "the schedule is paused: no job runs until `oillamp schedule " + layout.root() + " resume`"
                            : "the schedule runs again"));
            default -> Result.err(Problems.usage("'" + action + "' is not something `oillamp schedule` does",
                    Invocation.usageOf("schedule")));
        };
        if (done instanceof Result.Err<LampEvent> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.emit(((Result.Ok<LampEvent>) done).value());
        if (!action.equals("list")) {
            if (!limits.enabled()) context.report(Tuple.of(Problem.class, Problems.scheduleOff(layout.config())));
            // A running session looks at the schedule every half minute; this makes it look now.
            // Without a session there is nothing to tell, and that is fine.
            if (Filesystem.exists(layout.controlSocket()))
                Control.ask(layout.controlSocket(), layout.root(), Control.Request.of("schedule-changed"), "schedule");
        }
        return ExitStatus.SUCCESS;
    }

    private static LampEvent describe(Schedule schedule, LampConfig.Schedule limits, java.time.ZoneId zone) {
        Tuple<LampEvent.Job> jobs = Tuple.of(LampEvent.Job.class);
        for (ScheduledJob job : schedule.jobs()) jobs = jobs.add(job.describe(zone));
        return new LampEvent.Schedule(limits.enabled(), schedule.paused(), zone.getId(), jobs);
    }

    /// `oillamp ask <dir> <prompt>`: wake the agent with a prompt, and wait for its answer.
    ///
    /// The session does the work, because it is the one that holds the agent: this sends the
    /// prompt over the control socket and waits. When the agent is busy, the prompt waits its turn.
    public ExitStatus ask(Path lampPath, String prompt) {
        if (prompt.isBlank()) {
            Tuple<Problem> refused = Tuple.of(Problem.class, Problems.usage(
                    "`oillamp ask` needs something to ask the agent", Invocation.usageOf("ask")));
            context.report(refused);
            return ExitStatus.USAGE;
        }
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        context.info("run", "asking the agent; its answer comes once it is done, which can take a while");
        Result<Control.Reply> reply = Control.ask(layout.controlSocket(), layout.root(),
                Control.Request.of("ask").with("prompt", prompt), "ask", ASK_PATIENCE);
        if (reply instanceof Result.Err<Control.Reply> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        Control.Reply answer = ((Result.Ok<Control.Reply>) reply).value();
        LampEvent.RunOutcome outcome = LampEvent.RunOutcome.valueOf(answer.values().get("outcome").orElse("FAILED"));
        Optional<LampEvent.Snapshot> snapshot = Optional.empty();
        Optional<String> saved = answer.values().get("snapshot");
        if (saved.isPresent() && new History(layout).snapshots() instanceof Result.Ok<Tuple<LampEvent.Snapshot>>(
                Tuple<LampEvent.Snapshot> all, var _))
            snapshot = all.stream().filter(s -> s.id().equals(saved.get())).findFirst();
        context.emit(new LampEvent.RunFinished(
                new LampEvent.Run(answer.values().get("run").orElse("run"), Optional.empty(), prompt),
                outcome, answer.values().get("answer").orElse(""), snapshot,
                java.time.Duration.ofSeconds(Long.parseLong(answer.values().get("seconds").orElse("0")))));
        return outcome == LampEvent.RunOutcome.FINISHED ? ExitStatus.SUCCESS : ExitStatus.ERROR;
    }

    /// How long `oillamp ask` waits for its answer. The agent may be busy with other runs first,
    /// each of which may take up to `schedule.max_run_minutes`. If the session ends meanwhile,
    /// the connection closes, and the wait ends with it.
    private static final java.time.Duration ASK_PATIENCE = java.time.Duration.ofDays(1);

    private static void release(LampLock lock) {
        try {
            lock.close();
        } catch (java.io.IOException ignored) {
            // The kernel releases it when this process ends, moments from now.
        }
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

    /// `oillamp recordings <dir> [--open <session>] [--prune]`: list, play or prune the lamp's
    /// recordings.
    ///
    /// The recordings belong to the infra user, so this user can read them but not delete them
    /// directly; `--prune` deletes through `podman unshare`.
    ///
    /// Works while a session is running. The file being recorded is listed with its current size
    /// and duration.
    public ExitStatus recordings(Path lampPath, Optional<String> open, boolean prune) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        Tuple<RecordingFile> existing = Filesystem.listRecordings(layout.recordingsDir());

        if (open.isPresent()) return openRecording(existing, open.get(), layout);

        context.emit(new LampEvent.Answer(describeRecordings(existing, layout)));
        if (!prune) return ExitStatus.SUCCESS;

        Result<LampConfig> loaded = loadConfig(lampPath);
        if (loaded instanceof Result.Err<LampConfig> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampConfig.Recording policy = ((Result.Ok<LampConfig>) loaded).value().recording();
        Tuple<RecordingFile> doomed = Retention.select(existing, policy, machine.now());
        if (doomed.isEmpty()) {
            context.ok("recordings", "nothing is beyond the configured retention of "
                    + policy.maxAgeDays() + " days / " + policy.maxTotalGb() + " GB");
            return ExitStatus.SUCCESS;
        }
        Result<Plan> done = new StepRunner(machine, context)
                .run(LampPlanner.planPrune(doomed, policy));
        if (done instanceof Result.Err<Plan> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        context.report(done.warnings());
        if (context.options().dryRun()) return ExitStatus.SUCCESS;
        context.ok("recordings", doomed.size() + " recording(s) deleted");
        return ExitStatus.SUCCESS;
    }

    private ExitStatus openRecording(Tuple<RecordingFile> existing, String session, LampLayout layout) {
        for (RecordingFile file : existing)
            if (file.session().map(id -> id.value().equals(session)).orElse(false)) {
                // Not waited for: with some players xdg-open only returns when the video is closed.
                Machine.Window window = machine.launch(
                        Machine.Command.of("xdg-open", file.path().toString()).labelled("xdg-open"),
                        Machine.Window.Stdio.DETACHED);
                if (window.failure().isPresent()) {
                    context.report(Tuple.of(Problem.class,
                            Problems.recordingNotOpened(file.path(), window.failure().get())));
                    return ExitStatus.ERROR;
                }
                context.ok("recordings", "opened " + file.path());
                return ExitStatus.SUCCESS;
            }
        context.report(Tuple.of(Problem.class,
                Problems.noSuchRecording(session, layout.root(), layout.recordingsDir(), existing)));
        return ExitStatus.USAGE;
    }

    private static String describeRecordings(Tuple<RecordingFile> existing, LampLayout layout) {
        if (existing.isEmpty())
            return "no recordings in " + layout.recordingsDir()
                 + "\nrecording is off unless `recording.enabled = true` is set in oillamp.toml";
        StringBuilder out = new StringBuilder(layout.recordingsDir() + "\n\n");
        long total = 0;
        for (RecordingFile file : existing) {
            total += file.sizeBytes();
            Path name = file.path().getFileName();
            out.append("  ").append(name == null ? file.path().toString() : name.toString())
               .append("  ").append(pad(describeDuration(file), 9))
               .append("  ").append(pad(describeSize(file.sizeBytes()), 8))
               .append("  ").append(file.recordedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
               .append('\n');
        }
        return out.append("\n  ").append(existing.size()).append(" recording(s), ")
                  .append(describeSize(total)).append(" in total").toString();
    }

    /// `text` followed by spaces up to `width`, for the columns of `status`, `list` and
    /// `recordings`.
    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /// "-" for a file whose duration is unknown.
    private static String describeDuration(RecordingFile file) {
        return file.duration()
                .map(gap -> gap.toHours() > 0
                        ? "%dh%02dm".formatted(gap.toHours(), gap.toMinutesPart())
                        : "%dm%02ds".formatted(gap.toMinutes(), gap.toSecondsPart()))
                .orElse("-");
    }

    private static String describeSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return "%.1f GB".formatted(bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)        return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        return "%d kB".formatted(bytes / 1024);
    }

    private Result<Control.Reply> askTheSession(Path lampPath, String command, Control.Request request) {
        Result<LampLayout> layout = layoutOf(lampPath);
        if (layout instanceof Result.Err<LampLayout> failure) return Result.err(failure.problems());
        LampLayout found = ((Result.Ok<LampLayout>) layout).value();
        return Control.ask(found.controlSocket(), found.root(), request, command);
    }

    /// Finds a lamp's paths without creating or changing anything. Used by `view`,
    /// `shell`, `stop`, `status` and `recordings`.
    private Result<LampLayout> layoutOf(Path lampPath) {
        Path root = LampPhase.resolve(machine, lampPath);
        LampState state = LampClassifier.classify(root, Filesystem.list(root),
                Filesystem.readString(root.resolve(".oillamp").resolve("lamp.json")));
        if (!(state instanceof LampState.Existing existing))
            return Result.err(Problems.lampNotWritable(root,
                    "this is not an oillamp lamp — run `oillamp at " + lampPath + "` to make one"));
        return Result.ok(new LampLayout(root, existing.meta().agentId(), HostProbe.runtimeDirectory(machine)));
    }


    /// No supervisor answered. Either nothing is running, or a supervisor was killed without
    /// cleaning up. In that case its container, its control socket and `session.json` are left
    /// behind; this removes them. Without removing the socket, every later `status` would say
    /// "the session did not answer, run `oillamp stop`", and `stop` would say the same.
    ///
    /// A supervisor that answered at all, even to say no, is alive, and nothing is removed.
    private ExitStatus cleanUpAfterACrashedSession(Path lampPath, Tuple<Problem> why) {
        Result<LampLayout> found = layoutOf(lampPath);
        if (found instanceof Result.Err<LampLayout> failure) {
            context.report(failure.problems());
            return exitStatusFor(failure.problems());
        }
        LampLayout layout = ((Result.Ok<LampLayout>) found).value();
        if (Relay.answers(layout.controlSocket())) {
            context.report(why);
            return exitStatusFor(why);
        }
        ContainerName container = layout.containerName();
        boolean containerLeft = machine.run(Machine.Command
                .of("podman", "container", "exists", container.value())
                .labelled("podman container exists")).succeeded();
        boolean filesLeft = Filesystem.exists(layout.controlSocket())
                || Filesystem.exists(layout.sessionMeta());
        if (!containerLeft && !filesLeft) {
            context.report(why);
            return exitStatusFor(why);
        }
        context.info("stop", "no supervisor is running, but its session left things behind — cleaning up");
        if (containerLeft) {
            Machine.Outcome removed = machine.run(Machine.Command
                    .of("podman", "rm", "-f", container.value())
                    .withTimeout(java.time.Duration.ofSeconds(30)).labelled("podman rm"));
            if (!removed.succeeded()) {
                context.report(Tuple.of(Problem.class, Problems.podmanFailed(
                        "podman rm", removed.exitCode(), removed.errorOutput().strip())));
                return ExitStatus.ERROR;
            }
        }
        for (Path leftover : Tuple.of(Path.class, layout.controlSocket(), layout.primarySshSocket(),
                                                layout.extraSshSocket(), layout.sessionMeta())) {
            try {
                Filesystem.deleteIfPresent(leftover);
            } catch (java.io.IOException e) {
                context.report(Tuple.of(Problem.class, Problems.internal("cleanup", Problems.reason(e))));
            }
        }
        context.ok("stop", containerLeft
                ? "the sandbox left by the previous session has been removed"
                : "removed what the previous session left behind; its sandbox was already gone");
        return ExitStatus.SUCCESS;
    }

    /// `oillamp config <dir> check`: validate the lamp's configuration without changing anything.
    public ExitStatus checkConfig(Path lampPath) {
        HostPhase.Outcome host = new HostPhase(machine, new Context(context::emit,
                context.options().withDryRun(true).withAutoInstall(false), context.version()))
                .prepare(lampPath, false, Installing.NEVER);
        context.report(host.result().warnings());
        return checkConfiguration(lampPath, true);
    }

    /// `oillamp config <dir> show-effective`: print a summary of the validated configuration,
    /// the global file and the lamp's merged, as `at` would use it.
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

    /// The lamp's configuration merged over the global one, exactly as `at` reads it.
    private Result<LampConfig> loadConfig(Path lampPath) {
        Path lampConfig = lampPath.resolve("oillamp.toml");
        if (!Filesystem.exists(lampConfig))
            return Result.err(Problems.lampNotWritable(lampPath,
                    "there is no oillamp.toml here — run `oillamp at " + lampPath + "` to create one"));
        return ConfigLoader.load(LampPhase.configurationFiles(home(), lampConfig));
    }

    private static String describe(LampConfig config) {
        return "display      " + config.display().size() + " scale " + config.display().scale()
                + " gpu " + config.display().gpu().configName()
                + ", " + config.display().windows().configName() + " windows\n"
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

    /// Says who holds the lamp, from the `session.json` the running session wrote.
    private Problem busyProblem(LampPhase.Prepared prepared) {
        Optional<com.fasterxml.jackson.databind.JsonNode> session =
                Filesystem.readString(prepared.layout().sessionMeta()).flatMap(Json::parse);
        return Problems.lockBusy(prepared.layout().root(),
                session.map(json -> json.path("supervisorPid").asLong(0)).orElse(0L),
                session.map(json -> Json.text(json, "startedAt")).filter(when -> !when.isEmpty())
                       .orElse("an earlier time"));
    }

    /// The exit code for a list of problems: 4 for a busy lamp, 2 for configuration errors, otherwise 1.
    public static ExitStatus exitStatusFor(Tuple<Problem> problems) {
        for (Problem problem : problems) {
            String code = problem.code().value();
            if (code.equals("OIL-LOCK-001") || code.equals("OIL-HISTORY-005")) return ExitStatus.LAMP_BUSY;
            if (code.equals("OIL-HISTORY-001")) return ExitStatus.USAGE;
            if (code.startsWith("OIL-CONFIG-")) return ExitStatus.USAGE;
        }
        for (Problem problem : problems)
            if (problem.isError()) return ExitStatus.ERROR;
        return ExitStatus.SUCCESS;
    }
}
