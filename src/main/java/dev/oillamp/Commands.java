package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;

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
        HostPhase.Outcome host = new HostPhase(machine, context).prepare(lampPath, true, installing(lampPath));
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
            // oillamp connected to both sockets (CheckEndpoints) before printing this.
            context.ok("session", "desktop and shell both answering — "
                    + "oillamp connected to each socket before handing it over");

            // From here `at` does not return until the session is over.
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

    /// `oillamp remove <dir>`: delete a lamp.
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

        // A lamp half-deleted by hand may have lost lamp.json, and with it the agent id that names
        // the container and the runtime directory. Everything else can still be removed.
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
            // Unreadable output: fall back to the other checks in whatIsStillRunning.
            return Optional.empty();
        }
        return Optional.empty();
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
        for (String key : java.util.List.of("state", "detail", "lamp", "session", "container",
                                            "desktop", "renderer", "uptime", "shells", "viewer"))
            answer.values().get(key).ifPresent(value ->
                    out.append(pad(key)).append(value).append('\n'));
        context.emit(new LampEvent.Answer(out.toString().stripTrailing()));
        return ExitStatus.SUCCESS;
    }

    /// `oillamp list`: every oillamp container running on this host, found by its
    /// `oillamp.agent-id` label. oillamp keeps no list of its own that could go out of date.
    public ExitStatus list() {
        // JSON rather than a --format template: the template field names differ between podman
        // 4 and 5, and this must work with both.
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

    /// Turns `podman ps --format json` into one line per sandbox, lamp path included.
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
            // Output that is not the expected JSON: show nothing rather than fail.
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
        for (Path leftover : java.util.List.of(layout.controlSocket(), layout.primarySshSocket(),
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

    /// The label column of `status`.
    private static String pad(String label) { return padTo(label, 12); }

    /// The wider columns of `list`, which hold container names and paths.
    private static String column(String value) { return padTo(value, 22); }

    private static String padTo(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) out.append(' ');
        return out.toString();
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

    /// The exit code for a list of problems: 4 for a busy lamp, 2 for configuration errors, otherwise 1.
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
}
