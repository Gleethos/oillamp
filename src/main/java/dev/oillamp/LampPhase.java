package dev.oillamp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;

/**
 * Phase B: turn a directory into a lamp that is ready to run — spec §10.5.
 *
 * <p>Does the reading the pure planners cannot: resolves the path, looks at what is there, reads
 * the configuration files, checks that the filesystem can host Unix sockets, and takes the lock.
 * Every decision that follows from those facts is made by a pure function in the core.
 *
 * <p>Deliberately <b>package-private</b>: Phase B of §10.5, wired together. On the effects
 * allowlist.
 */
final class LampPhase {

    /** Filesystems that cannot host Unix domain sockets, which the whole design depends on (§11.2). */
    private static final Tuple<String> UNUSABLE_FILESYSTEMS =
            Tuple.of(String.class, "nfs", "nfs4", "cifs", "smb3", "vfat", "exfat", "fuse.sshfs", "msdos");

    private final Machine machine;
    private final Context context;

    public LampPhase(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /** Everything the later phases need from a prepared lamp. */
    public record Prepared(LampLayout layout, LampConfig config, SessionId session,
                           Gpu.Decision gpu, LampState state) {}

    public Result<Prepared> prepare(Path requestedPath, HostFacts host) {
        Path resolved = resolve(requestedPath);

        Result<Path> validated = LampPaths.validate(resolved, host.user());
        if (validated instanceof Result.Err<Path> failure) return Result.err(failure.problems());
        Path root = ((Result.Ok<Path>) validated).value();

        if (UNUSABLE_FILESYSTEMS.contains(host.fileSystemTypeOfLamp()))
            return Result.err(Problems.lampBadFilesystem(root, host.fileSystemTypeOfLamp()));

        LampState state = LampClassifier.classify(root, Filesystem.list(root),
                Filesystem.readString(root.resolve(".oillamp").resolve("lamp.json")));

        AgentId agentId = switch (state) {
            case LampState.Existing existing -> existing.meta().agentId();
            case LampState.Missing ignored    -> generateAgentId();
            case LampState.Empty ignored      -> generateAgentId();
            case LampState.Foreign ignored    -> generateAgentId();
            case LampState.Unreadable ignored -> generateAgentId();
        };

        Path runtimeDir = host.xdgRuntimeDir().orElse(Path.of("/run/user/" + host.user().uid()));
        LampLayout layout = new LampLayout(root, agentId, runtimeDir);

        Result<LampConfig> configuration = loadConfiguration(layout, host);
        if (configuration instanceof Result.Err<LampConfig> failure) return Result.err(failure.problems());
        LampConfig config = ((Result.Ok<LampConfig>) configuration).value();
        context.report(configuration.warnings());
        describeConfiguration(config);

        Gpu.Decision gpu = Gpu.decide(config.display().gpu(), host.gpu(), host.user(), host.podman());
        if (gpu instanceof Gpu.Decision.Refused refused) return Result.err(refused.problem());
        // The desktop size and renderer are the two things a user most wants confirmed at
        // startup, because they are what they will be looking at in the viewer window.
        context.ok("lamp", "desktop " + config.display().size()
                + (config.display().scale() == 1.0 ? "" : " at scale " + config.display().scale())
                + ", renderer " + gpu.renderer()
                + (gpu instanceof Gpu.Decision.Hardware ? " (hardware)" : " (software)"));
        Gpu.noteFor(gpu).ifPresent(note -> context.emit(new LampEvent.Info("lamp", note.whatHappened())));

        SessionId session = SessionId.at(machine.now());

        Result<Plan> skeleton = LampPlanner.planSkeleton(new LampPlanner.Inputs(
                state, layout, config, session, agentId, machine.now(), context.version(),
                existingRecordings(layout), Optional.empty(), Filesystem.exists(layout.sessionMeta()),
                context.options().init()));
        if (skeleton instanceof Result.Err<Plan> failure) return Result.err(failure.problems());
        context.report(skeleton.warnings());

        Result<Plan> executed = new StepRunner(machine, context).run(((Result.Ok<Plan>) skeleton).value());
        if (executed instanceof Result.Err<Plan> failure) return Result.err(failure.problems());

        Result<Plan> sessionFiles = planSessionFiles(layout, config, session, gpu);
        if (sessionFiles instanceof Result.Err<Plan> failure) return Result.err(failure.problems());

        Result<Plan> written = new StepRunner(machine, context).run(((Result.Ok<Plan>) sessionFiles).value());
        if (written instanceof Result.Err<Plan> failure) return Result.err(failure.problems());

        return Result.ok(new Prepared(layout, config, session, gpu, state),
                         executed.warnings().addAll(written.warnings()));
    }

    /**
     * Renders the per-session files. Separate from the skeleton because it needs the keys that
     * the skeleton generated — see {@link LampPlanner#planSession}.
     */
    private Result<Plan> planSessionFiles(LampLayout layout, LampConfig config,
                                          SessionId session, Gpu.Decision gpu) {
        if (context.options().dryRun())
            return LampPlanner.planSession(layout, "(generated client key)", "(generated host key)",
                    "(rendered per session)", "(rendered per session)");

        Optional<String> clientKey = Filesystem.readString(layout.clientKeyPub());
        Optional<String> hostKey = Filesystem.readString(layout.hostKeyPub());
        if (clientKey.isEmpty() || hostKey.isEmpty())
            return Result.err(Problems.sshKeygenFailed(new Problem.Evidence.Command(
                    Tuple.of(String.class, "ssh-keygen"), 0,
                    "the generated public key could not be read back from " + layout.keysDir(),
                    java.time.Duration.ZERO)));

        Result<String> environment = RuntimeEnv.render(
                RuntimeEnv.variables(config, layout, session, gpu.renderer()));
        if (environment instanceof Result.Err<String> failure) return Result.err(failure.problems());

        return LampPlanner.planSession(layout, clientKey.get(), hostKey.get(),
                ((Result.Ok<String>) environment).value(), AgentGuide.render(config, layout));
    }

    // ─── configuration ─────────────────────────────────────────────────────────────────────

    /**
     * Reads the configuration files that apply to this lamp, in precedence order (§20.1).
     *
     * <p>A lamp whose {@code oillamp.toml} does not exist yet is not an error: it is about to be
     * created from the shipped template, and the built-in defaults describe exactly what that
     * template says.
     */
    private Result<LampConfig> loadConfiguration(LampLayout layout, HostFacts host) {
        Tuple<ConfigSource> sources = Tuple.of(ConfigSource.class);
        Path global = host.user().home().resolve(".config").resolve("oillamp").resolve("config.toml");
        Optional<String> globalText = Filesystem.readString(global);
        if (globalText.isPresent())
            sources = sources.add(ConfigSource.userGlobal(global, globalText.get()));
        Optional<String> lampText = Filesystem.readString(layout.config());
        if (lampText.isPresent())
            sources = sources.add(ConfigSource.lamp(layout.config(), lampText.get()));
        return ConfigLoader.load(sources);
    }

    private void describeConfiguration(LampConfig config) {
        StringBuilder summary = new StringBuilder("config valid — network: default ")
                .append(config.network().defaultDecision() == Decision.ALLOW ? "allow" : "deny")
                .append(", ").append(config.network().rules().size())
                .append(config.network().rules().size() == 1 ? " rule" : " rules");
        if (config.forwards().isEmpty()) {
            summary.append(", no forwards");
        } else {
            summary.append(", ").append(config.forwards().size())
                   .append(config.forwards().size() == 1 ? " forward (" : " forwards (");
            boolean first = true;
            for (Forward forward : config.forwards()) {
                summary.append(first ? "" : ", ").append(forward.name()).append(" → ").append(forward.target());
                first = false;
            }
            summary.append(')');
        }
        context.ok("lamp", summary.toString());
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Makes the path absolute and resolves symlinks <em>before</em> validation, so a link
     * pointing into a system directory cannot slip past the refusal in {@link LampPaths} (FR-03).
     */
    private Path resolve(Path requested) {
        Path absolute = requested.isAbsolute()
                ? requested
                : machine.environmentVariable("PWD").map(Path::of).orElse(Path.of(""))
                        .resolve(requested);
        try {
            return Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                    ? absolute.toRealPath().normalize()
                    : absolute.normalize();
        } catch (IOException e) {
            return absolute.normalize();
        }
    }

    private AgentId generateAgentId() {
        return new AgentId(machine.randomToken(AgentId.LENGTH));
    }

    private Tuple<RecordingFile> existingRecordings(LampLayout layout) {
        Tuple<RecordingFile> recordings = Tuple.of(RecordingFile.class);
        if (!Filesystem.exists(layout.recordingsDir())) return recordings;
        try (var entries = Files.list(layout.recordingsDir())) {
            for (Path entry : entries.sorted().toList()) {
                if (!entry.toString().endsWith(".mkv")) continue;
                recordings = recordings.add(new RecordingFile(entry,
                        Files.getLastModifiedTime(entry).toInstant(), Files.size(entry)));
            }
        } catch (IOException e) {
            context.info("lamp", "could not list existing recordings: " + Problems.reason(e));
        }
        return recordings;
    }
}
