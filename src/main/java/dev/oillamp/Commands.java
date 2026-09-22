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
     * {@code oillamp at <dir>} — prepare the host and the lamp, then start a session.
     *
     * <p>Phases A and B are complete; the image build and the session itself (phases C and D)
     * are the next milestones, so for now this stops after the lamp is ready and says so.
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

        if (context.options().dryRun()) {
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
            context.info("session", "starting the sandbox container is the next milestone; "
                    + "everything up to this point is done and persisted in " + prepared.layout().root());
            return ExitStatus.SUCCESS;
        } finally {
            try {
                held.close();
            } catch (java.io.IOException e) {
                context.report(Tuple.of(Problem.class,
                        Problems.internal("lock release", Problems.reason(e))));
            }
        }
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
