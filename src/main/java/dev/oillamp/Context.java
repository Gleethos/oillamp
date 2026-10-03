package dev.oillamp;

import java.net.URI;
import java.util.Optional;
import java.util.function.Consumer;

import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Tuple;

/// What every command needs: where to send events, and the options from the command line.
///
/// oillamp never prints directly. Everything it has to say becomes a [LampEvent], which the
/// console renders and tests can inspect.
///
/// @param sink    where every event goes: the console, the record tests read, and whoever follows
///                the session
/// @param version oillamp's version, which `lamp.json` records
record Context(Consumer<LampEvent> sink, Options options, String version) {

    /// The options from the command line.
    ///
    /// @param debug       `--debug`; currently has no effect beyond turning on `verbose`
    /// @param dryRun      `--dry-run`: print the complete plan and change nothing
    /// @param autoInstall whether oillamp may install host packages; `--no-install` turns it off
    /// @param init        `--init`: accept a non-empty directory as a new lamp
    /// @param openViewer  false with `--no-viewer`
    /// @param openWindows false with `--no-windows`: neither the shell window nor the viewer
    ///                    opens, so no display is needed; the user attaches with `oillamp shell`
    ///                    and `oillamp view`, or forwards the desktop's socket over ssh
    /// @param embedded    `--embedded`: an application started oillamp and owns the session. No
    ///                    windows open, events go to standard output as JSON lines, and the
    ///                    session ends when standard input closes
    /// @param model       `--model-service` and `--model-key-env`: where model requests go and
    ///                    which variable holds the key, in place of the lamp's `[model]` settings
    /// @param enableScheduling `--enable-scheduling`: `schedule.enabled` is true for this session,
    ///                    whatever oillamp.toml says
    public record Options(boolean verbose, boolean debug, boolean dryRun,
                          boolean autoInstall, boolean init, boolean openViewer, boolean openWindows,
                          boolean embedded,
                          ModelOverride model, boolean enableScheduling) {

        public static Options defaults() {
            return new Options(false, false, false, true, false, true, true, false, ModelOverride.NONE, false);
        }

        public Options withModel(ModelOverride model) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withDryRun(boolean dryRun) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withAutoInstall(boolean autoInstall) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withInit(boolean init) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withVerbose(boolean verbose) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withDebug(boolean debug) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withViewer(boolean openViewer) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withWindows(boolean openWindows) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withEmbedded(boolean embedded) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }

        public Options withScheduling(boolean enableScheduling) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, openWindows, embedded, model, enableScheduling);
        }
    }

    /// The model settings given on the command line. Each one that is present replaces the
    /// lamp's own, for this session only. The key itself is never on the command line, which
    /// other users of the machine can read: only the name of the variable that holds it.
    public record ModelOverride(Optional<URI> service, Optional<String> keyEnv) {

        static final ModelOverride NONE = new ModelOverride(Optional.empty(), Optional.empty());

        public LampConfig.Model applyTo(LampConfig.Model configured) {
            return new LampConfig.Model(service.orElse(configured.service()),
                                        keyEnv.orElse(configured.keyEnv()));
        }
    }

    /// The same, with every event also passed to `more`.
    public Context alsoTelling(Consumer<LampEvent> more) {
        return new Context(sink.andThen(more), options, version);
    }

    /// Each problem as a [LampEvent.Failure] if it is an error, otherwise as a [LampEvent.Warning].
    public void sinkAcceptProblems(Tuple<Problem> problems) {
        for (Problem problem : problems)
            sink.accept(problem.isError() ? new LampEvent.Failure(problem) : new LampEvent.Warning(problem));
    }
}
