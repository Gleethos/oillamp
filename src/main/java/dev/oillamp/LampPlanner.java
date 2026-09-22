package dev.oillamp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import sprouts.Tuple;

/**
 * Plans Phase B: turning a directory into a working lamp — spec §10.5.
 *
 * <p>Split into two passes, because the second depends on the result of the first: keys have to
 * exist before {@code authorized_keys} and {@code known_hosts} can contain them. Rather than let
 * a step compute its own content at execution time — which would make {@code --dry-run} a lie —
 * the shell runs {@link #planSkeleton}, reads the generated public keys, and then runs
 * {@link #planSession}. Both plans stay fully described up front.
 *
 * <p>The ownership rules of §9.2 are expressed here and nowhere else. They are what makes
 * NFR-06 true: the recordings directory and the infra socket directory belong to container uid
 * 1001, so the agent — which is uid 1000 — can read its own recordings but cannot alter them.
 */
final class LampPlanner {

    private LampPlanner() {}

    /** The container's infra user, which owns everything the agent must not be able to modify (D-14). */
    public static final int INFRA_UID = 1001;
    public static final int INFRA_GID = 1001;

    /**
     * Everything Phase B needs to know, gathered by the shell.
     *
     * @param state              what is at the lamp path
     * @param newAgentId         a freshly generated id, used only if the lamp does not exist yet
     * @param initRequested      the user passed {@code --init}, accepting a non-empty directory
     * @param leftoverContainer  a container from a previous, crashed session (§10.4)
     * @param sessionMetaExists  a stale {@code session.json} from a crashed session
     */
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

    /**
     * Creates or repairs the lamp's directories, identity, keys and runtime directory.
     *
     * <p>Every write that could destroy something the user or the agent owns uses
     * {@link Step.WritePolicy#IF_ABSENT}: re-running {@code oillamp at} on an existing lamp must
     * never overwrite an edited {@code oillamp.toml} or anything in the agent's home (FR-21).
     */
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

        // ── clean up after a session that never got to shut down (§10.4, FR-08) ────────────
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
        steps = steps.add(new Step.WriteFile(layout.config(), Templates.defaultConfig(),
                PosixMode.PRIVATE_FILE, Step.WritePolicy.IF_ABSENT));
        steps = steps.add(new Step.WriteFile(layout.readme(), Templates.readme(layout),
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

        // ── sockets: three directories, three owners (§9.2) ───────────────────────────────
        steps = steps.add(new Step.CreateDirectory(layout.socketsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.hostSocketsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.agentSocketsDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.infraSocketsDir(), PosixMode.PUBLIC_DIR));
        // wayvnc runs as the container's infra user and creates vnc.sock here.
        steps = steps.add(new Step.ChownForContainer(layout.infraSocketsDir(),
                INFRA_UID, INFRA_GID, PosixMode.PUBLIC_DIR));

        // ── recordings: writable only by the infra user, so the agent cannot tamper (NFR-06) ─
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

        // ── the agent's world: created once, never overwritten afterwards (§19.1) ──────────
        steps = steps.add(new Step.CreateDirectory(layout.agentDir(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.workspace(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.libs(), PosixMode.PUBLIC_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.screenshots(), PosixMode.PUBLIC_DIR));

        // ── the short runtime dir, so socket paths stay under the 107-byte limit (D-25) ────
        steps = steps.add(new Step.CreateDirectory(layout.runtimeDir(), PosixMode.PRIVATE_DIR));
        steps = steps.add(new Step.CreateDirectory(layout.runDir(), PosixMode.PRIVATE_DIR));
        steps = steps.add(new Step.CreateSymlink(layout.shortSockets(), layout.socketsDir()));

        return Result.ok(Plan.of(LampEvent.Phase.LAMP, steps), warnings);
    }

    /**
     * Plans the per-session files under {@code .oillamp/session/}, which are mounted read-only
     * into the container at {@code /oillamp/session}.
     *
     * @param clientPublicKey the generated client public key, so sshd will accept our connection
     * @param hostPublicKey   the generated host public key, pinned so the user is never prompted
     * @param runtimeEnv      the rendered environment (§20.3)
     * @param agentGuide      the generated guide the agent reads as {@code ~/AGENTS.md} (§19.3)
     */
    public static Result<Plan> planSession(LampLayout layout,
                                           String clientPublicKey,
                                           String hostPublicKey,
                                           String runtimeEnv,
                                           String agentGuide) {
        Tuple<Step> steps = Tuple.of(Step.class);
        steps = steps.add(new Step.WriteFile(layout.runtimeEnvFile(), runtimeEnv,
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        steps = steps.add(new Step.WriteFile(layout.authorizedKeys(), clientPublicKey.trim() + "\n",
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));
        // sshd insists on a private host key; the copy is readable by container uid 1000 = this user.
        steps = steps.add(new Step.CopyFile(layout.hostKey(), layout.sshdHostKey(), PosixMode.PRIVATE_FILE));
        steps = steps.add(new Step.WriteFile(layout.agentGuide(), agentGuide,
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));

        steps = steps.add(new Step.WriteFile(layout.sshConfig(), Ssh.renderClientConfig(layout),
                PosixMode.PRIVATE_FILE, Step.WritePolicy.ALWAYS));
        steps = steps.add(new Step.WriteFile(layout.knownHosts(), Ssh.renderKnownHosts(layout, hostPublicKey),
                PosixMode.PUBLIC_FILE, Step.WritePolicy.ALWAYS));

        return Result.ok(Plan.of(LampEvent.Phase.LAMP, steps));
    }
}
