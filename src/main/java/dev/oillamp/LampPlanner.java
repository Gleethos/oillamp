package dev.oillamp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Tuple;

/// Plans the lamp phase: turning a directory into a working lamp. Also plans `oillamp remove`
/// and `oillamp recordings --prune`.
///
/// The lamp is planned in two passes, because the second needs the result of the first: the SSH
/// keys must exist before `authorized_keys` and `known_hosts` can contain them.
/// [LampPhase] runs [#planSkeleton], reads the new public keys, then runs
/// [#planSession]. The alternative, a step that computes its content while running, would
/// mean a dry run could not show that content.
///
/// The ownership of the lamp's directories is decided here. The recordings directory and the
/// infra socket directory are given to container uid 1001, so the agent (uid 1000) can read its
/// recordings but cannot change them.
final class LampPlanner {

    private LampPlanner() {}

    /// The container's infra user, which owns everything the agent must not be able to change.
    public static final int INFRA_UID = 1001;
    public static final int INFRA_GID = 1001;

    /// Everything the lamp phase needs to know, gathered by [LampPhase].
    ///
    /// @param state              what is at the lamp path
    /// @param newAgentId         a freshly generated id, used only if the lamp does not exist yet
    /// @param initRequested      the user passed `--init`, accepting a non-empty directory
    /// @param leftoverContainer  a container left by a previous session that crashed
    /// @param sessionMetaExists  a stale `session.json` from a crashed session
    public record Inputs(
        LampState state,
        LampLayout layout,
        LampConfig config,
        SessionId session,
        AgentId newAgentId,
        Instant now,
        String oillampVersion,
        Tuple<RecordingFile> recordings,
        Optional<ContainerName> leftoverContainer,
        boolean sessionMetaExists,
        boolean initRequested
    ) {}

    /// Creates or repairs the lamp's directories, identity, keys and runtime directory.
    ///
    /// Every file the user or the agent may have edited is written with
    /// [Step.WritePolicy#IF_ABSENT], so running `oillamp at` again never overwrites an
    /// edited `oillamp.toml` or anything in the agent's home.
    public static Result<Plan> planSkeleton(Inputs inputs) {
        LampLayout layout = inputs.layout();

        Optional<Problem> refusal = switch (inputs.state()) {
            case LampState.Foreign foreign -> inputs.initRequested()
                    ? Optional.<Problem>empty()
                    : Optional.of(Problems.lampNotEmpty(foreign.root(), foreign.sampleEntries()));
            case LampState.Unreadable unreadable ->
                    Optional.of(Problems.lampNotWritable(unreadable.root(), unreadable.reason()));
            case LampState.Existing existing ->
                    existing.meta().schemaVersion() > LampMeta.CURRENT_SCHEMA_VERSION
                        ? Optional.of(Problems.lampNewerSchema(existing.root(),
                                existing.meta().schemaVersion(), LampMeta.CURRENT_SCHEMA_VERSION))
                        : Optional.<Problem>empty();
            case LampState.Missing ignored -> Optional.<Problem>empty();
            case LampState.Empty ignored   -> Optional.<Problem>empty();
        };
        if (refusal.isPresent()) return Result.err(refusal.get());

        Tuple<Problem> warnings = Tuple.of(Problem.class);
        Tuple<Step> steps = Tuple.of(Step.class);

        // ── clean up after a session that crashed without shutting down ────────────────────
        if (inputs.leftoverContainer().isPresent()) {
            steps = steps.add(new Step.RemoveContainer(inputs.leftoverContainer().get(),
                    "left over from a session that did not shut down"));
            warnings = warnings.add(Problems.lockRecovered(layout.root(),
                    "the container " + inputs.leftoverContainer().get() + " was still running"));
        }
        if (inputs.sessionMetaExists()) {
            steps = steps.add(new Step.RemovePath(layout.sessionMeta(), "stale session state"));
            if (inputs.leftoverContainer().isEmpty())
                warnings = warnings.add(Problems.lockRecovered(layout.root(),
                        "a session.json was left behind by a previous run"));
        }

        // ── the user's layer ───────────────────────────────────────────────────────────────
        steps = steps.add(new Step.CreateDirectory(layout.root(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.WriteFile(layout.config(), GeneratedFileTextUtil.defaultConfig(),
                PosixMode.PRIVATE_FILE, Step.WritePolicy.IF_ABSENT));
        steps = steps.add(new Step.WriteFile(layout.readme(), GeneratedFileTextUtil.readme(layout),
                PosixMode.PUBLIC_FILE, Step.WritePolicy.IF_ABSENT));

        // ── the state layer, mode 0700 so no other host user reaches the sockets inside ────
        steps = steps.add(new Step.CreateDirectory(layout.stateDir(), PosixMode.PRIVATE_DIR));
        LampMeta fresh = LampMeta.createdNow(inputs.newAgentId(), inputs.now(), inputs.oillampVersion());
        LampMeta meta = switch (inputs.state()) {
            case LampState.Existing existing  -> existing.meta().usedAt(inputs.now());
            case LampState.Missing ignored    -> fresh;
            case LampState.Empty ignored      -> fresh;
            case LampState.Foreign ignored    -> fresh;
            case LampState.Unreadable ignored -> fresh;
        };
        steps = steps.add(new Step.WriteLampMeta(layout.lampMeta(), meta));

        steps = steps.add(new Step.CreateDirectory(layout.keysDir(), PosixMode.PRIVATE_DIR));
        steps = steps.add(new Step.GenerateSshKey(layout.clientKey(),
                "oillamp-" + layout.agentId().value() + "-client"));
        steps = steps.add(new Step.GenerateSshKey(layout.hostKey(),
                "oillamp-" + layout.agentId().value() + "-host"));

        steps = steps.add(new Step.CreateDirectory(layout.sessionDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.imageDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.logsDir(), PosixMode.PUBLIC_DIR));

        // ── sockets: host/ and agent/ belong to the user, infra/ to the infra user ─────────
        steps = steps.add(new Step.CreateDirectory(layout.socketsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.hostSocketsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.agentSocketsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.infraSocketsDir(), PosixMode.PUBLIC_DIR));
        // wayvnc runs as the container's infra user and creates vnc.sock here.
        steps = steps.add(new Step.ChownForContainer(layout.infraSocketsDir(),
                INFRA_UID, INFRA_GID, PosixMode.PUBLIC_DIR));

        // ── recordings: writable only by the infra user, so the agent cannot change them ───
        steps = steps.add(new Step.CreateDirectory(layout.recordingsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.ChownForContainer(layout.recordingsDir(),
                INFRA_UID, INFRA_GID, PosixMode.PUBLIC_DIR));

        Tuple<RecordingFile> doomed = Retention.select(inputs.recordings(),
                inputs.config().recording(), inputs.now());
        if (!doomed.isEmpty()) {
            Tuple<Path> paths = Tuple.of(Path.class);
            for (RecordingFile file : doomed) paths = paths.add(file.path());
            steps = steps.add(new Step.DeleteContainerOwnedFiles(paths,
                    "beyond the configured retention of " + inputs.config().recording().maxAgeDays()
                  + " days / " + inputs.config().recording().maxTotalGb() + " GB"));
        }

        // ── the agent directory: created once, never overwritten afterwards ────────────────
        steps = steps.add(new Step.CreateDirectory(layout.agentDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.workspace(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.libs(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.screenshots(), PosixMode.PUBLIC_DIR));
        // Written once and then left to the agent. Without it, `ssh <lamp> 'some command'` runs in
        // a shell that has read no profile, so it has no proxy settings, display or `sdk`.
        steps = steps.add(new Step.WriteFile(layout.agentBashrc(), GeneratedFileTextUtil.agentBashrc(),
                PosixMode.PUBLIC_FILE, Step.WritePolicy.IF_ABSENT));

        // ── the short runtime directory, so socket paths stay under the 107-byte limit ─────
        steps = steps.add(new Step.CreateDirectory(layout.runtimeDir(), PosixMode.PRIVATE_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.runDir(), PosixMode.PRIVATE_DIR));
        steps = steps.add(new Step.CreateSymlink(layout.shortSockets(), layout.socketsDir()));

        return Result.ok(Plan.of(LampEvent.Phase.LAMP, steps), warnings);
    }

    /// What `oillamp remove` found in a lamp directory.
    ///
    /// This is what is actually on disk, not derived from the lamp's identity, because the most
    /// common reason to run `remove` is that `rm -rf` already deleted `lamp.json`
    /// before failing. `agentDirs` is usually one directory, sometimes none, and more than one
    /// if a lamp directory was copied.
    ///
    /// @param runtimeDir empty when the lamp's identity is gone, since the runtime directory is
    ///                   named after it
    record Removal(Path root, Tuple<Path> agentDirs, Optional<Path> runtimeDir) {}

    /// The plan for `oillamp recordings --prune`: delete the recordings [Retention]
    /// selected. It is the same decision the next session start would make, done now.
    public static Plan planPrune(Tuple<RecordingFile> doomed, LampConfig.Recording policy) {
        if (doomed.isEmpty()) return Plan.of(LampEvent.Phase.LAMP, Tuple.of(Step.class));
        Tuple<Path> paths = Tuple.of(Path.class);
        for (RecordingFile file : doomed) paths = paths.add(file.path());
        return Plan.of(LampEvent.Phase.LAMP, Tuple.of(Step.class,
                new Step.DeleteContainerOwnedFiles(paths,
                        "beyond the configured retention of " + policy.maxAgeDays()
                      + " days / " + policy.maxTotalGb() + " GB")));
    }

    /// Everything `oillamp remove` deletes, in the order it deletes it.
    ///
    /// Only what oillamp created is removed. The user may keep their own files beside
    /// `oillamp.toml`, so the lamp directory itself is only removed if it ends up empty
    /// (`Commands.remove` does that).
    ///
    /// The agent's home goes last. If the state directory cannot be fully deleted, for example
    /// because podman is missing, the run stops there, with the agent's work still intact.
    public static Plan planRemoval(Removal found) {
        Tuple<Step> steps = Tuple.of(Step.class);
        if (found.runtimeDir().isPresent())
            steps = steps.add(new Step.RemoveTree(found.runtimeDir().get(),
                    "the session's sockets and the symlink into the lamp"));
        steps = steps.add(new Step.RemoveTree(LampLayout.stateDirOf(found.root()),
                "oillamp's own state: identity, keys, logs, recordings and sockets"));
        steps = steps.add(new Step.RemovePath(LampLayout.configOf(found.root()),
                "the lamp's configuration"));
        steps = steps.add(new Step.RemovePath(LampLayout.readmeOf(found.root()),
                "the note describing this directory"));
        for (Path agentDir : found.agentDirs())
            steps = steps.add(new Step.RemoveTree(agentDir,
                    "the agent's home, and everything it did here"));
        return Plan.of(LampEvent.Phase.LAMP, steps);
    }

    /// Plans the per-session files under `.oillamp/session/`, which are mounted read-only
    /// into the container at `/oillamp/session`.
    ///
    /// @param clientPublicKey the generated client public key, so sshd will accept our connection
    /// @param hostPublicKey   the generated host public key, pinned so the user is never prompted
    /// @param runtimeEnv      the contents of `runtime.env`
    /// @param agentGuide      the guide the agent reads as `~/AGENTS.md`
    /// @param gitConfig       the agent's git identity, see [AgentGitConfigUtil]
    /// @param scheduleTools   the pi extension with the agent's scheduling tools, or empty when
    ///                        the schedule is off and the agent should have none
    public static Result<Plan> planSession(LampLayout layout,
                                           String clientPublicKey,
                                           String hostPublicKey,
                                           String runtimeEnv,
                                           String agentGuide,
                                           String gitConfig,
                                           Optional<String> scheduleTools) {
        Tuple<Step> steps = Tuple.of(Step.class);
        steps = steps.add(new Step.WriteFile(layout.runtimeEnvFile(), runtimeEnv,
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        steps = steps.add(new Step.WriteFile(layout.authorizedKeys(), clientPublicKey.trim() + "\n",
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        // sshd requires its host key to be private (0600). Container uid 1000 is this user, so it can read the copy.
        steps = steps.add(new Step.CopyFile(layout.hostKey(), layout.sshdHostKey(), PosixMode.PRIVATE_FILE));
        steps = steps.add(new Step.WriteFile(layout.agentGuide(), agentGuide,
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        // The same text as ~/AGENTS.md, where the login banner says to look. A real file rather
        // than a symlink to /oillamp/session, which would be a broken link on the host. Rewritten
        // every session because it describes this session's configuration.
        steps = steps.add(new Step.WriteFile(layout.agentsMd(), agentGuide,
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));

        steps = steps.add(new Step.WriteFile(layout.gitConfig(), gitConfig,
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        // How the session moves within a conversation, for a question asked after an earlier
        // entry. Always there, since any lamp can be asked.
        steps = steps.add(new Step.WriteFile(layout.conversationTools(), AgentGuide.conversationTools(),
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        // Rewritten every session, like the guide, so that it always matches this oillamp. The
        // agent could change it, but it only asks the host, which decides.
        steps = steps.add(scheduleTools.<Step>map(tools -> new Step.WriteFile(layout.scheduleTools(), tools,
                        PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS))
                .orElseGet(() -> new Step.RemovePath(layout.scheduleTools(),
                        "the schedule is off, so the agent has no scheduling tools")));

        steps = steps.add(new Step.WriteFile(layout.sshConfig(), Ssh.renderClientConfig(layout),
                PosixMode.PRIVATE_FILE, Step.WritePolicy.ALWAYS));
        steps = steps.add(new Step.WriteFile(layout.knownHosts(), Ssh.renderKnownHosts(layout, hostPublicKey),
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));

        return Result.ok(Plan.of(LampEvent.Phase.LAMP, steps));
    }
}
