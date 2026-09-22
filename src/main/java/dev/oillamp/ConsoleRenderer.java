package dev.oillamp;


/**
 * Turns events into the text a person reads — spec §27.4.
 *
 * <p>Two shapes only. Progress is one short line per fact, prefixed by the area it belongs to,
 * so a successful run reads like a checklist. A failure is a block: what happened, why that
 * matters, the evidence, and what to try — because NFR-03 asks for errors a user can act on
 * without guessing, and a stack trace is not that.
 *
 * <p>Everything is also captured verbatim, so a scenario can assert on exactly what the user saw.
 *
 * <p>Deliberately <b>package-private</b>: how oillamp looks in a terminal must stay free to change
 * without that being a breaking change for anyone.
 */
final class ConsoleRenderer {

    private static final String RESET  = "\u001B[0m";
    private static final String DIM    = "\u001B[2m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";

    private final StringBuilder captured = new StringBuilder();
    private final boolean colour;
    /**
     * Mutable, and deliberately so: the renderer has to exist before the command line is parsed
     * (a usage error must still print in colour), but {@code --verbose} is only known afterwards.
     * Returning a copy instead left the sink holding the original, which is how this flag came to
     * parse correctly and then do nothing at all.
     */
    private boolean verbose;
    private boolean echoToTerminal = true;

    private ConsoleRenderer(boolean colour, boolean verbose) {
        this.colour = colour;
        this.verbose = verbose;
    }

    /** Colour when a human is watching and {@code NO_COLOR} is unset — the usual convention. */
    public static ConsoleRenderer forMachine(Machine machine) {
        boolean colour = machine.isInteractive() && machine.environmentVariable("NO_COLOR").isEmpty();
        return new ConsoleRenderer(colour, false);
    }

    /** Told once, as soon as the options are parsed. Returns this renderer, not a copy. */
    public ConsoleRenderer verbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    /** Used by tests, which read {@link #text()} instead of watching a terminal. */
    public ConsoleRenderer quiet() {
        echoToTerminal = false;
        return this;
    }

    public String text() { return captured.toString(); }

    public void banner(String version, String lamp) {
        line("🪔 oillamp " + version + (lamp.isEmpty() ? "" : " — " + lamp));
    }

    public void render(LampEvent event) {
        switch (event) {
            case LampEvent.PhaseStarted started ->
                    { if (verbose) line(area(started.phase().name().toLowerCase(java.util.Locale.ROOT))
                                        + dim("…")); }
            case LampEvent.PhaseFinished ignored -> { }
            case LampEvent.Ok ok ->
                    line(area(ok.area()) + colour(GREEN, "✓ ") + ok.text());
            case LampEvent.Info info ->
                    line(area(info.area()) + dim("· " + info.text()));
            case LampEvent.StepPlanned planned -> {
                line(area("plan") + dim("→ ") + planned.step().describe());
                // The detail is normally for the session log, but a dry run writes no log, and
                // the detail is exactly what someone dry-running wants to read: the podman
                // arguments, the packages and why each one is being installed.
                if (verbose && !planned.step().detail().equals(planned.step().describe()))
                    for (String detailLine : planned.step().detail().lines().toList())
                        line(dim(" ".repeat(10) + "  " + detailLine));
            }
            case LampEvent.StepStarted started ->
                    { if (verbose) line(area("step") + dim("→ " + started.step().describe())); }
            case LampEvent.StepSucceeded succeeded ->
                    { if (verbose) line(area("step") + colour(GREEN, "✓ ") + succeeded.step().describe()); }
            case LampEvent.StepSkipped skipped ->
                    { if (verbose) line(area("step") + dim("· " + skipped.step().describe()
                                                           + " — " + skipped.why())); }
            case LampEvent.Output output ->
                    { if (verbose) line(area(output.sourceTag()) + dim(output.line())); }
            case LampEvent.Answer answer -> line(answer.text());
            // The state machine already says the things worth saying out loud, as Ok and Info;
            // the state changes themselves are the log's account of how it got there.
            case LampEvent.SessionStateChanged changed ->
                    { if (verbose) line(area("session") + dim("· " + changed.status().state()
                                                              + " — " + changed.status().detail())); }
            case LampEvent.WindowOpened opened -> {
                line(area("session") + colour(GREEN, "✓ ") + "opened " + opened.what());
                // The command matters when the window misbehaves, and by then it is too late to
                // ask for it — so --verbose keeps it, and a failure quotes it in full.
                if (verbose) line(dim(" ".repeat(10) + "  $ " + String.join(" ", opened.argv())));
            }
            case LampEvent.Summary summary -> {
                line("");
                line(dim("— ") + summary.title() + dim(" ———"));
                for (String detail : summary.lines()) line("  " + dim(detail));
                line("");
            }
            case LampEvent.Warning warning -> problem(warning.problem());
            case LampEvent.Failure failure -> problem(failure.problem());
        }
    }

    /**
     * A problem, in full. Errors and warnings share the layout so that a user learns to read it
     * once; only the marker and colour differ.
     */
    private void problem(Problem problem) {
        String marker = switch (problem.severity()) {
            case ERROR   -> colour(RED, "✗");
            case WARNING -> colour(YELLOW, "!");
            case INFO    -> dim("·");
        };
        if (problem.severity() == Problem.Severity.INFO) {
            line(area("note") + dim("· " + problem.whatHappened()));
            return;
        }
        line("");
        line(marker + "  " + problem.code() + "  " + problem.title());
        field("What happened", problem.whatHappened());
        field("Why it matters", problem.whyItMatters());

        boolean firstEvidence = true;
        for (Problem.Evidence evidence : problem.evidence()) {
            field(firstEvidence ? "Evidence" : "", describe(evidence));
            firstEvidence = false;
        }
        int index = 1;
        for (Problem.Fix fix : problem.fixes()) {
            String text = index + ". " + fix.description()
                        + fix.command().map(command -> "\n     " + command).orElse("");
            field(index == 1 ? "Try" : "", text);
            index++;
        }
        problem.logFile().ifPresent(log -> field("Full log", log.toString()));
        line("");
    }

    private static String describe(Problem.Evidence evidence) {
        return switch (evidence) {
            case Problem.Evidence.Command command ->
                    "$ " + String.join(" ", command.argv()) + "  (exit " + command.exitCode() + ")"
                    + (command.stderrTail().isBlank() ? "" : "\n" + command.stderrTail());
            case Problem.Evidence.File file    -> file.path() + " — " + file.note();
            case Problem.Evidence.Value value  -> value.name() + ": " + value.value();
            case Problem.Evidence.Excerpt text -> text.title() + ":\n" + text.text();
            case Problem.Evidence.Config config ->
                    config.file() + "\n" + config.keyPath()
                    + (config.value().isEmpty() ? "" : " = " + config.value())
                    + "\n" + config.expected();
        };
    }

    /** Two columns: a fixed-width label, then wrapped-by-the-author text. */
    private void field(String label, String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String prefix = i == 0 ? pad(label) : pad("");
            line("  " + dim(prefix) + lines[i]);
        }
    }

    private static String pad(String label) {
        StringBuilder out = new StringBuilder(label);
        while (out.length() < 15) out.append(' ');
        return out.toString();
    }

    private String area(String name) {
        StringBuilder out = new StringBuilder("[").append(name).append(']');
        while (out.length() < 10) out.append(' ');
        return dim(out.toString());
    }

    private String dim(String text) { return colour(DIM, text); }

    private String colour(String code, String text) { return colour ? code + text + RESET : text; }

    private void line(String text) {
        captured.append(text).append('\n');
        if (echoToTerminal) System.out.println(text);
    }
}
