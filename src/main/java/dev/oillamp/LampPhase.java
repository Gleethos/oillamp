package dev.oillamp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Association;
import sprouts.Tuple;

/// The lamp phase: turns a directory into a lamp that is ready to run.
///
/// It does the reading the pure planners cannot: resolves the path, looks at what is there,
/// reads the configuration files and checks the filesystem can hold Unix sockets. The decisions are
/// made by [LampPaths], [LampClassifier], [ConfigLoader], [Gpu] and
/// [LampPlanner]. The lock is taken afterwards, by `Commands.at`.
final class LampPhase {

    /// Filesystems that cannot hold Unix domain sockets, which every connection to the sandbox uses.
    private static final Tuple<String> UNUSABLE_FILESYSTEMS =
            Tuple.of(String.class, "nfs", "nfs4", "cifs", "smb3", "vfat", "exfat", "fuse.sshfs", "msdos");

    private final Machine machine;
    private final Context context;

    public LampPhase(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /// Everything the later phases need from a prepared lamp.
    public record Prepared(LampLayout layout, LampConfig config, SessionId session,
                           Gpu.Decision gpu, LampState state) {}

    public Result<Prepared> prepare(Path requestedPath, HostFacts host) {
        Path resolved = resolve(machine, requestedPath);

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

        LampLayout layout = new LampLayout(root, agentId, host.runtimeDirectory());

        Optional<Problem> replaced = aSocketDirectoryReplacedByALink(layout);
        if (replaced.isPresent()) return Result.err(replaced.get());

        Result<LampConfig> configuration = loadConfiguration(layout, host);
        if (configuration instanceof Result.Err<LampConfig> failure) return Result.err(failure.problems());
        // Model settings given on the command line replace the lamp's own, for this session, and
        // so does --enable-scheduling.
        LampConfig configured = ((Result.Ok<LampConfig>) configuration).value();
        LampConfig config = configured
                .withModel(context.options().model().applyTo(configured.model()))
                .withSchedule(context.options().enableScheduling() ? configured.schedule().switchedOn()
                                                                   : configured.schedule());
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
        Gpu.noteLine(gpu).ifPresent(note -> context.emit(new LampEvent.Info("lamp", note)));

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

    /// The host environment variables passed to the agent's tools: only the names in
    /// [RuntimeEnv#INHERITED_FROM_HOST]. A user who set `EDENAI_API_KEY` on the host
    /// does not have to set it again in the sandbox. The console says which were found, never their
    /// values.
    private Association<String, String> inheritedFromHost() {
        Association<String, String> found = Association.between(String.class, String.class);
        Tuple<String> names = Tuple.of(String.class);
        for (String name : RuntimeEnv.INHERITED_FROM_HOST) {
            Optional<String> value = machine.environmentVariable(name);
            if (value.isEmpty()) continue;
            found = found.put(name, value.get());
            names = names.add(name);
        }
        if (!names.isEmpty())
            context.info("lamp", "the agent's tools will see " + String.join(", ", names)
                    + " from your environment (the value is never logged)");
        return found;
    }

    /// Renders the per-session files. Separate from the skeleton because it needs the keys that
    /// the skeleton generated; see [LampPlanner#planSession].
    private Result<Plan> planSessionFiles(LampLayout layout, LampConfig config,
                                          SessionId session, Gpu.Decision gpu) {
        if (context.options().dryRun())
            return LampPlanner.planSession(layout, "(generated client key)", "(generated host key)",
                    "(rendered per session)", "(rendered per session)", "(rendered per session)",
                    config.schedule().enabled() ? Optional.of("(the scheduling tools)") : Optional.empty());

        Optional<String> clientKey = Filesystem.readString(layout.clientKeyPub());
        Optional<String> hostKey = Filesystem.readString(layout.hostKeyPub());
        if (clientKey.isEmpty() || hostKey.isEmpty())
            return Result.err(Problems.sshKeygenFailed(new Problem.Evidence.Command(
                    Tuple.of(String.class, "ssh-keygen"), 0,
                    "the generated public key could not be read back from " + layout.keysDir(),
                    Duration.ZERO)));

        Result<String> environment = RuntimeEnv.render(
                RuntimeEnv.variables(config, layout, session, gpu.renderer(), inheritedFromHost()));
        if (environment instanceof Result.Err<String> failure) return Result.err(failure.problems());

        return LampPlanner.planSession(layout, clientKey.get(), hostKey.get(),
                ((Result.Ok<String>) environment).value(), AgentGuide.render(config),
                GitConfig.render(gitAuthor(config.git(), layout)),
                config.schedule().enabled() ? Optional.of(AgentGuide.scheduleTools()) : Optional.empty());
    }

    /// The name and email the agent's commits carry, as `[git]` chooses, said on the console
    /// either way. Without one, git in the sandbox refuses to commit, so that is said too.
    private Optional<GitConfig.Author> gitAuthor(LampConfig.Git git, LampLayout layout) {
        Optional<GitConfig.Author> author = switch (git.identity()) {
            case GENIE  -> Optional.of(GitConfig.genie(layout.agentId()));
            case CUSTOM -> Optional.of(new GitConfig.Author(git.name(), git.email()));
            case NONE   -> Optional.empty();
            case HOST   -> hostGitValue("user.name").flatMap(name ->
                           hostGitValue("user.email").map(email -> new GitConfig.Author(name, email)));
        };
        if (author.isPresent())
            context.info("lamp", "the agent's commits will carry " + author.get().name()
                    + " <" + author.get().email() + ">"
                    + (git.identity() == GitIdentity.HOST ? ", from your git configuration" : ""));
        else if (git.identity() == GitIdentity.HOST)
            context.info("lamp", "your git configuration has no user.name and user.email, so the agent "
                    + "cannot commit; set them with `git config --global`, or set [git] in oillamp.toml");
        else
            context.info("lamp", "the agent has no git identity, as [git] in oillamp.toml says");
        return author;
    }

    /// One value from the user's own git configuration, the one `git commit` uses outside any
    /// repository. `--global` so that a repository oillamp happens to be started from cannot
    /// lend its identity, and `--includes` because many people keep theirs in an included file.
    private Optional<String> hostGitValue(String key) {
        Machine.Outcome outcome = machine.run(Machine.Command.of(
                "git", "config", "--global", "--includes", "--get", key));
        if (!outcome.succeeded()) return Optional.empty();
        String value = outcome.output().strip();
        return value.isEmpty() || value.contains("\n") ? Optional.empty() : Optional.of(value);
    }

    // ─── configuration ─────────────────────────────────────────────────────────────────────

    /// Reads the configuration files that apply to this lamp: the global file, then the lamp's.
    ///
    /// A lamp whose `oillamp.toml` does not exist yet is not an error: it is about to be
    /// created from the shipped template, and the built-in defaults describe exactly what that
    /// template says.
    private Result<LampConfig> loadConfiguration(LampLayout layout, HostFacts host) {
        return ConfigLoader.load(configurationFiles(host.user().home(), layout.config()));
    }

    /// The configuration files that apply to a lamp, in the order they are merged: the user's
    /// global `~/.config/oillamp/config.toml`, then the lamp's own `oillamp.toml`. Files that do
    /// not exist are left out. Every command that reads a lamp's configuration uses this, so
    /// that `config check` judges the same configuration `at` uses.
    static Tuple<ConfigSource> configurationFiles(Path home, Path lampConfig) {
        Tuple<ConfigSource> sources = Tuple.of(ConfigSource.class);
        Path global = home.resolve(".config").resolve("oillamp").resolve("config.toml");
        Optional<String> globalText = Filesystem.readString(global);
        if (globalText.isPresent())
            sources = sources.add(ConfigSource.userGlobal(global, globalText.get()));
        Optional<String> lampText = Filesystem.readString(lampConfig);
        if (lampText.isPresent())
            sources = sources.add(ConfigSource.lamp(lampConfig, lampText.get()));
        return sources;
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

    /// The socket directories are attached to the container and one is handed to the infra user.
    /// Each must be a real directory: a link would carry both to wherever it points.
    ///
    /// Older versions attached their shared parent, which the agent owns, so the agent could
    /// replace one with a link to any directory of the user's. Such a lamp is refused.
    private static Optional<Problem> aSocketDirectoryReplacedByALink(LampLayout layout) {
        for (Path directory : Tuple.of(Path.class, layout.socketsDir(), layout.hostSocketsDir(),
                                                   layout.agentSocketsDir(), layout.infraSocketsDir())) {
            if (!Files.isSymbolicLink(directory)) continue;
            String target;
            try {
                target = Files.readSymbolicLink(directory).toString();
            } catch (IOException e) {
                target = "somewhere that cannot be read (" + Problems.reason(e) + ")";
            }
            return Optional.of(Problems.lampSocketDirReplaced(directory, target));
        }
        return Optional.empty();
    }

    /// Makes the path absolute and resolves symlinks _before_ validation, so a link
    /// pointing into a system directory cannot get past the refusal in [LampPaths].
    ///
    /// Every command that names a lamp uses this, so a lamp reached through a link is the same
    /// lamp to all of them. `at` labels the container with this path, and `remove` looks for
    /// the container by that label.
    static Path resolve(Machine machine, Path requested) {
        Path absolute = (requested.isAbsolute()
                ? requested
                : machine.environmentVariable("PWD").map(Path::of).orElse(Path.of(""))
                        .resolve(requested)).toAbsolutePath();
        // A link whose target does not exist yet, as after `ln -s /mnt/big/x ~/lamps/x`: the user
        // means the lamp to be made where it points, so follow it by hand.
        for (int hops = 0; hops < 40 && Files.isSymbolicLink(absolute) && !Files.exists(absolute); hops++) {
            try {
                absolute = absolute.resolveSibling(Files.readSymbolicLink(absolute));
            } catch (IOException unreadable) {
                break;
            }
        }
        try {
            if (Files.exists(absolute)) return absolute.toRealPath().normalize();
            // Not there yet: resolve what does exist, so a link further up the path counts too.
            Path parent = absolute.normalize().getParent();
            Path name = absolute.normalize().getFileName();
            if (parent != null && name != null && Files.exists(parent))
                return parent.toRealPath().resolve(name);
            return absolute.normalize();
        } catch (IOException e) {
            return absolute.normalize();
        }
    }

    private AgentId generateAgentId() {
        return new AgentId(machine.randomToken(AgentId.LENGTH));
    }

    private Tuple<RecordingFile> existingRecordings(LampLayout layout) {
        return Filesystem.listRecordings(layout.recordingsDir());
    }
}
