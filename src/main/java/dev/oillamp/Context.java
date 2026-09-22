package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;


/**
 * What every command needs: somewhere to send events, and the switches the user set.
 *
 * <p>oillamp never prints directly. Everything it has to say becomes a {@link LampEvent}, which
 * the CLI renders, the log records and — later — the Swing front end displays (NFR-08). That is
 * also why a scenario can assert on what the user was told without parsing console text.
 */
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

    /**
     * The switches from the command line and configuration.
     *
     * @param dryRun      print the complete plan and change nothing (FR-12)
     * @param autoInstall may oillamp install host packages? ({@code --no-install} turns it off)
     * @param init        accept a non-empty directory as a new lamp (FR-02)
     */
    public record Options(boolean verbose, boolean debug, boolean dryRun,
                          boolean autoInstall, boolean init, boolean openViewer) {

        public static Options defaults() {
            return new Options(false, false, false, true, false, true);
        }

        public Options withDryRun(boolean dryRun) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer);
        }

        public Options withAutoInstall(boolean autoInstall) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer);
        }

        public Options withInit(boolean init) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer);
        }

        public Options withVerbose(boolean verbose) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer);
        }

        public Options withDebug(boolean debug) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer);
        }

        public Options withViewer(boolean openViewer) {
            return new Options(verbose, debug, dryRun, autoInstall, init, openViewer);
        }
    }

    public Options options() { return options; }

    public String version() { return version; }

    public void emit(LampEvent event) { sink.accept(event); }

    public void ok(String area, String text) { emit(new LampEvent.Ok(area, text)); }

    public void info(String area, String text) { emit(new LampEvent.Info(area, text)); }

    /** Reports problems, routing warnings and errors to the right event. */
    public void report(sprouts.Tuple<Problem> problems) {
        for (Problem problem : problems)
            emit(problem.isError() ? new LampEvent.Failure(problem) : new LampEvent.Warning(problem));
    }

    public Optional<Path> installLog() { return installLog; }

    public void installLog(Path path) { this.installLog = Optional.of(path); }
}
