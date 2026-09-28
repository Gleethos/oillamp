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
    ///                    windows open, and the session ends when standard input closes
    public record Options(boolean verbose, boolean debug, boolean dryRun,
                          boolean autoInstall, boolean init, boolean openViewer, boolean embedded) {

        public static Options defaults() {
            return new Options(false, false, false, true, false, true, false);
        }

        public Options withDryRun(boolean dryRun) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
        }

        public Options withAutoInstall(boolean autoInstall) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
        }

        public Options withInit(boolean init) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
        }

        public Options withVerbose(boolean verbose) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
        }

        public Options withDebug(boolean debug) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
        }

        public Options withViewer(boolean openViewer) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
        }

        public Options withEmbedded(boolean embedded) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer, embedded);
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
