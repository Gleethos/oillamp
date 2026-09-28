package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import dev.lamp.LampEvent;
import dev.lamp.Problem;


/// What every command needs: where to send events, and the options from the command line.
///
/// oillamp never prints directly. Everything it has to say becomes a [LampEvent], which the
/// console renders and tests can inspect.
final class Context {

    private final Consumer<LampEvent> sink;
    private final Options options;
    private final String version;
    private Optional<Path> installLog = Optional.empty();

    public Context(Consumer<LampEvent> sink, Options options, String version) {
        this.sink = sink;
        this.options = options;
        this.version = version;
    }

    /// The options from the command line.
    ///
    /// @param debug       `--debug`; currently has no effect beyond turning on `verbose`
    /// @param dryRun      `--dry-run`: print the complete plan and change nothing
    /// @param autoInstall whether oillamp may install host packages; `--no-install` turns it off
    /// @param init        `--init`: accept a non-empty directory as a new lamp
    /// @param openViewer  false with `--no-viewer`
    /// @param embedded    `--embedded`: an application started oillamp and owns the session. No
    ///                    windows open, events go to standard output as JSON lines, and the
    ///                    session ends when standard input closes
    /// @param model       `--model-service` and `--model-key-env`: where model requests go and
    ///                    which variable holds the key, in place of the lamp's `[model]` settings
    public record Options(boolean verbose, boolean debug, boolean dryRun,
                          boolean autoInstall, boolean init, boolean openViewer, boolean embedded,
                          ModelOverride model) {

        public static Options defaults() {
            return new Options(false, false, false, true, false, true, false, ModelOverride.NONE);
        }

        public Options withModel(ModelOverride model) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withDryRun(boolean dryRun) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withAutoInstall(boolean autoInstall) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withInit(boolean init) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withVerbose(boolean verbose) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withDebug(boolean debug) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withViewer(boolean openViewer) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }

        public Options withEmbedded(boolean embedded) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded, model);
        }
    }

    /// The model settings given on the command line. Each one that is present replaces the
    /// lamp's own, for this session only. The key itself is never on the command line, which
    /// other users of the machine can read: only the name of the variable that holds it.
    public record ModelOverride(Optional<java.net.URI> service, Optional<String> keyEnv) {

        static final ModelOverride NONE = new ModelOverride(Optional.empty(), Optional.empty());

        public LampConfig.Model applyTo(LampConfig.Model configured) {
            return new LampConfig.Model(service.orElse(configured.service()),
                                        keyEnv.orElse(configured.keyEnv()));
        }
    }

    public Options options() { return options; }

    public String version() { return version; }

    public void emit(LampEvent event) { sink.accept(event); }

    public void ok(String area, String text) { emit(new LampEvent.Ok(area, text)); }

    public void info(String area, String text) { emit(new LampEvent.Info(area, text)); }

    /// Reports problems, routing warnings and errors to the right event.
    public void report(sprouts.Tuple<Problem> problems) {
        for (Problem problem : problems)
            emit(problem.isError() ? new LampEvent.Failure(problem) : new LampEvent.Warning(problem));
    }

    public Optional<Path> installLog() { return installLog; }

    public void installLog(Path path) { this.installLog = Optional.of(path); }
}
