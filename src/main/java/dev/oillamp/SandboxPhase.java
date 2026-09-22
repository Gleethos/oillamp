package dev.oillamp;

import sprouts.Association;
import sprouts.Tuple;

import java.nio.file.Path;
import java.time.Duration;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Phase C: build the image if needed, then start the sandbox and wait for it — spec §10.5, §15, §16.
 *
 * <p>Like the phases before it, this plans before it acts, so {@code --dry-run} shows the exact
 * {@code podman run} that would happen. That is worth more here than anywhere else: those flags
 * are the sandbox's guarantees, and someone who wants to check that {@code --network=none} is
 * really passed should be able to see it without reading the source or trusting a summary.
 *
 * <p>Deliberately <b>package-private</b>: Phase C of §10.5, wired together. On the effects
 * allowlist. Users meet it as the second half of {@code at}.
 */
final class SandboxPhase {

    private final Machine machine;
    private final Context context;

    public SandboxPhase(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /** What the sandbox turned out to be, once it was running. */
    public record Running(ContainerName container, ImageTag image, String readyJson) {}

    public Result<Running> start(LampPhase.Prepared prepared, HostFacts host) {
        LampLayout layout = prepared.layout();
        ContainerName container = ContainerName.of(layout.agentId());
        SortedMap<String, String> buildArguments = buildArgumentsFor(prepared.config());
        ImageTag image = ImageTag.ofHash(ImageResources.hashOf(buildArguments));

        Tuple<Step> steps = Tuple.of(Step.class);

        // A container from a previous session with the same name would make `podman run` fail with
        // a name clash, which says nothing about the real situation: the last session did not
        // clean up, probably because it was killed.
        if (containerExists(container))
            steps = steps.add(new Step.RemoveContainer(container, "left over from an earlier session"));

        // Images are content-addressed (§12.3), so an existing one with this tag was built from
        // exactly these inputs and there is nothing to gain by building it again.
        boolean imagePresent = imageExists(image);
        if (!imagePresent) {
            steps = steps.add(new Step.ExtractImageContext(layout.imageContext()));
            steps = steps.add(new Step.BuildImage(image, layout.imageContext(),
                                                  asAssociation(buildArguments), false));
        }

        StepRunner runner = new StepRunner(machine, context);

        // Two plans rather than one, because §10.5 makes them separate phases and they fail for
        // different reasons: "the image could not be built" and "the sandbox would not start" are
        // not the same problem and should not arrive under the same heading.
        if (!steps.isEmpty()) {
            Result<Plan> image_ = runner.run(Plan.of(LampEvent.Phase.IMAGE, steps));
            if (image_ instanceof Result.Err<Plan> failure) return Result.err(failure.problems());
        }
        if (imagePresent)
            context.ok("image", "sandbox image ready — " + image);

        Tuple<Step> session = Tuple.of(Step.class,
                new Step.DeleteContainerOwnedFiles(staleSessionFiles(layout),
                        "left behind by the previous session on this lamp"),
                new Step.RunContainer(container, image, containerArgv(container, image, prepared, host)),
                new Step.AwaitReady(container, layout.readyFile(), prepared.session(),
                                    readyTimeout(imagePresent)),
                new Step.CheckEndpoints(container, Tuple.of(Step.Endpoint.class,
                        new Step.Endpoint("the desktop (VNC)", layout.vncSocket()),
                        new Step.Endpoint("the shell (SSH)", layout.agentSshSocket()))));

        Result<Plan> started = runner.run(Plan.of(LampEvent.Phase.SESSION, session));
        if (started instanceof Result.Err<Plan> failure) return Result.err(failure.problems());
        if (context.options().dryRun())
            return Result.ok(new Running(container, image, "{}"), started.warnings());

        String ready = Filesystem.readString(layout.readyFile()).orElse("{}");
        return Result.ok(new Running(container, image, ready), started.warnings());
    }

    /**
     * The arguments that make the container a sandbox — spec §15.3.
     *
     * <p>Every one of these is load-bearing, and the spikes of §33 are what establish that they
     * behave as assumed:
     *
     * <ul>
     *   <li>{@code --network=none} (FR-40) — no route, no DNS. Everything outbound goes through
     *       the proxy socket instead.</li>
     *   <li>{@code --read-only} (S12) — the image cannot be modified, so what a session installs
     *       outside the agent's home is gone when it ends, by construction rather than by policy.</li>
     *   <li>{@code --userns=keep-id:uid=1000,gid=1000} (S13) — the agent's files belong to the
     *       host user, and the infra user stays a different user the agent cannot become.</li>
     *   <li>{@code --user 0:0} — the entrypoint needs to create each user's runtime directories
     *       and then drop privileges; this is root <em>inside the namespace</em>, which is an
     *       unprivileged subuid on the host.</li>
     * </ul>
     */
    private Tuple<String> containerArgv(ContainerName container, ImageTag image,
                                        LampPhase.Prepared prepared, HostFacts host) {
        LampLayout layout = prepared.layout();
        LampConfig config = prepared.config();
        Tuple<String> argv = Tuple.of(String.class,
                "--detach",
                "--name", container.value(),
                "--network=none",
                "--read-only",
                "--user", "0:0",
                "--userns=keep-id:uid=1000,gid=1000",
                "--tmpfs", "/run:rw,mode=755",
                "--tmpfs", "/tmp:rw,mode=1777",
                "--memory", config.limits().memory(),
                "--cpus", String.valueOf(config.limits().resolveCpus(host.cpuCount())),
                "--pids-limit", String.valueOf(config.limits().pids()),
                // Labels, so that `oillamp list` can find every sandbox on this host without a
                // registry of its own. podman already knows what is running; a second list kept
                // beside it would only be a list that can disagree.
                "--label", "oillamp.agent-id=" + layout.agentId(),
                "--label", "oillamp.lamp=" + layout.root(),
                "--label", "oillamp.session=" + prepared.session());

        argv = argv.addAll(Tuple.of(String.class,
                "--volume", layout.sessionDir() + ":/oillamp/session:ro",
                "--volume", layout.socketsDir() + ":/oillamp/sockets",
                "--volume", layout.recordingsDir() + ":/oillamp/recordings",
                "--volume", layout.agentDir() + ":/home/agent"));

        // Only when the GPU was actually granted. `keep-groups` is what carries the host's render
        // group across the user namespace, and it works under crun but not runc — which is why
        // crun is a required host package (§36.3).
        if (prepared.gpu() instanceof Gpu.Decision.Hardware hardware)
            argv = argv.addAll(Tuple.of(String.class,
                    "--device", hardware.node().path().toString(),
                    "--group-add", "keep-groups"));

        return argv.add(image.value());
    }

    /**
     * The files the last session left in the sockets directory — spec §9.2, §16.
     *
     * <p>That directory is a bind mount, so it outlives the container: {@code vnc.sock} from the
     * previous run is still there when wayvnc tries to bind, and wayvnc has no way to take a path
     * that is already taken. {@code ready.json} is worse, because the host starts watching for it
     * the instant the container starts and would otherwise read the previous session's answer.
     *
     * <p>They belong to a container uid, which is why this is a
     * {@link Step.DeleteContainerOwnedFiles} and not an ordinary delete: {@code rm} from the host
     * gets EPERM on a directory inside the subuid range, so it has to go through
     * {@code podman unshare}.
     */
    private static Tuple<Path> staleSessionFiles(LampLayout layout) {
        return Tuple.of(layout.readyFile(),
                        layout.infraSocketsDir().resolve("vnc.sock"),
                        layout.agentSocketsDir().resolve("ssh.sock"));
    }

    /**
     * How long to wait for readiness.
     *
     * <p>A freshly built image has nothing in the page cache and starts a compositor, a VNC server
     * and a recorder from cold, so the first run is legitimately slower than every run after it.
     * One timeout for both cases would either be too tight for the first or uselessly long for
     * the rest.
     */
    private static Duration readyTimeout(boolean imageWasAlreadyPresent) {
        return imageWasAlreadyPresent ? Duration.ofSeconds(60) : Duration.ofSeconds(120);
    }

    private SortedMap<String, String> buildArgumentsFor(LampConfig config) {
        SortedMap<String, String> arguments = new TreeMap<>();
        arguments.put("BASE_IMAGE", config.image().base());
        arguments.put("JDK_PACKAGE", config.image().jdkPackage());
        arguments.put("NODE_MAJOR", config.image().nodeVersion());
        arguments.put("EXTRA_APT_PACKAGES", String.join(" ", config.image().extraAptPackages()));
        // Until now `agent_tools.install` was read, validated and then never used: the image was
        // built with the Containerfile's own default whatever the lamp asked for. Passing it here
        // also makes it part of the content hash, so changing the list rebuilds the image — which
        // is the only way a new harness can appear in a sandbox that has no network of its own.
        arguments.put("AGENT_TOOLS", String.join(" ", config.agentTools().install()));
        return arguments;
    }

    private static Association<String, String> asAssociation(SortedMap<String, String> map) {
        Association<String, String> out = Association.betweenSorted(String.class, String.class);
        for (var entry : map.entrySet()) out = out.put(entry.getKey(), entry.getValue());
        return out;
    }

    private boolean imageExists(ImageTag tag) {
        return succeeded("podman image exists", "podman", "image", "exists", tag.value());
    }

    private boolean containerExists(ContainerName name) {
        return succeeded("podman container exists", "podman", "container", "exists", name.value());
    }

    private boolean succeeded(String label, String... argv) {
        Machine.Outcome outcome = machine.run(Machine.Command.of(argv)
                .withTimeout(Duration.ofSeconds(20)).labelled(label));
        return outcome instanceof Machine.Outcome.Finished finished && finished.exitCode() == 0;
    }
}
