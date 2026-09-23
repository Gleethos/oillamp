package dev.oillamp;

import java.util.Optional;


/// Prints events as text for a person to read.
///
/// Progress is one short line per fact, prefixed with its area (`[host]`, `[lamp]`,
/// ...), so a successful run reads like a checklist. A warning or error is a block: what happened,
/// why it matters, the evidence, and what to try.
///
/// Everything printed is also kept, so tests can check exactly what the user saw.
///
/// Colour is used when output goes to a terminal and `NO_COLOR` is not set. The
/// `--no-color` option is accepted but not yet connected to this.
final class ConsoleRenderer {

    private static final String RESET  = "\u001B[0m";
    private static final String DIM    = "\u001B[2m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";

    private final StringBuilder captured = new StringBuilder();
    private final boolean colour;
    /// Mutable on purpose: the renderer exists before the command line is parsed, so that usage
    /// errors can be printed, and `--verbose` is only known afterwards. An earlier version
    /// returned a modified copy, which nobody used, so `--verbose` had no effect.
    private boolean verbose;
    private boolean echoToTerminal = true;

    // ─── the live activity line ─────────────────────────────────────────────────────────────
    //
    // While a step runs (building the image, starting the container, waiting for the desktop),
    // the last line of the terminal shows a spinner, what is happening, for how long, and the
    // latest line of the step's own output. It is redrawn in place every 120 ms by a daemon
    // thread and cleared before any ordinary line is printed, so it never ends up in the text
    // above it. It is never part of text(): it is only drawn on a real terminal.

    /// Steps that may run sudo, which asks for a password. A redrawn line would overwrite the prompt.
    private static final java.util.Set<String> STEPS_THAT_PROMPT = java.util.Set.of("InstallPackages", "AddSubIds");
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String CLEAR_LINE = "\r\u001B[2K";
    /// Turns the terminal's automatic line wrapping off while the activity line is drawn, and on
    /// again afterwards. If the line wrapped onto a second row, the carriage return would only go
    /// back to the start of that second row, and every redraw would leave a row of text behind.
    /// With wrapping off, a line that is too long is cut off at the right edge instead.
    private static final String WRAP_OFF = "\u001B[?7l", WRAP_ON = "\u001B[?7h";
    /// Colour codes and other terminal escape sequences, and control characters such as tabs.
    private static final java.util.regex.Pattern NOT_PRINTABLE =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B.|\\p{Cntrl}");

    /// What is happening right now, if anything.
    private record Activity(String text, long startedNanos, String latestOutput) {
        Activity withOutput(String line) { return new Activity(text, startedNanos, line); }
    }

    /// Whether the activity line may be drawn at all: only when standard output is a terminal.
    private final boolean live;
    /// The terminal's width, so that a long activity line ends with "…" instead of being cut off.
    private final int width;
    /// Everything written to the terminal goes through this lock.
    private final Object terminal = new Object();
    private volatile Optional<Activity> activity = Optional.empty();
    private boolean activityShown;
    private boolean tickerStarted;

    private ConsoleRenderer(boolean colour, boolean verbose, boolean live, int width) {
        this.colour = colour;
        this.verbose = verbose;
        this.live = live;
        this.width = width;
    }

    /// Colour when output goes to a terminal and `NO_COLOR` is unset. The activity line only
    /// when standard output really is a terminal, never when it is redirected or piped.
    public static ConsoleRenderer forMachine(Machine machine) {
        boolean colour = machine.isInteractive() && machine.environmentVariable("NO_COLOR").isEmpty();
        boolean live = machine.isInteractive()
                && Optional.ofNullable(System.console()).filter(java.io.Console::isTerminal).isPresent();
        int width = machine.environmentVariable("COLUMNS").flatMap(ConsoleRenderer::number)
                           .or(() -> live ? terminalWidth(machine) : Optional.empty())
                           .orElse(80);
        return new ConsoleRenderer(colour, false, live, width);
    }

    /// Asks the terminal how wide it is. Shells set `COLUMNS` for themselves but usually do not
    /// pass it on to programs, so it is rarely available. `stty size` prints "rows columns".
    private static Optional<Integer> terminalWidth(Machine machine) {
        Machine.Outcome outcome = machine.run(Machine.Command.of("sh", "-c", "stty size < /dev/tty")
                                                             .withTimeout(java.time.Duration.ofSeconds(2)));
        java.util.regex.Matcher size = java.util.regex.Pattern.compile("\\d+\\s+(\\d+)").matcher(outcome.output().trim());
        return outcome.succeeded() && size.matches() ? number(size.group(1)).filter(n -> n > 0) : Optional.empty();
    }

    private static Optional<Integer> number(String text) {
        try {
            return Optional.of(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /// Told once, as soon as the options are parsed. Returns this renderer, not a copy.
    public ConsoleRenderer verbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    /// Used by tests, which read [#text()] instead of watching a terminal.
    public ConsoleRenderer quiet() {
        echoToTerminal = false;
        return this;
    }

    public String text() { return captured.toString(); }

    /// Writes text with no banner, tag or colour. Used only for the completion script, which a
    /// shell evaluates, so any decoration would be evaluated too.
    public void plain(String text) {
        line(text.stripTrailing());
    }

    public void banner(String version, String lamp) {
        line("🪔 oillamp " + version + (lamp.isEmpty() ? "" : " — " + lamp));
    }

    public void render(LampEvent event) {
        switch (event) {
            case LampEvent.StepStarted started -> startActivity(started.step());
            case LampEvent.Output output -> activity = activity.map(a -> a.withOutput(output.line()));
            default -> stopActivity();
        }
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
                // ask for it, so --verbose keeps it, and a failure quotes it in full.
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

    /// A problem, in full. Errors and warnings share the layout so that a user learns to read it
    /// once; only the marker and colour differ.
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

    /// Two columns: a fixed-width label, then wrapped-by-the-author text.
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
        // A name longer than the column still gets a space after it; otherwise "[recordings]"
        // would run straight into the tick mark.
        if (out.charAt(out.length() - 1) != ' ') out.append(' ');
        return dim(out.toString());
    }

    private String dim(String text) { return colour(DIM, text); }

    private String colour(String code, String text) { return colour ? code + text + RESET : text; }

    private void line(String text) {
        synchronized (terminal) {
            captured.append(text).append('\n');
            if (!echoToTerminal) return;
            clearActivityLine();
            System.out.println(text);
        }
    }

    // ─── the live activity line ─────────────────────────────────────────────────────────────

    private void startActivity(LampEvent.StepInfo step) {
        if (!live || !echoToTerminal || STEPS_THAT_PROMPT.contains(step.kind())) {
            stopActivity();
            return;
        }
        activity = Optional.of(new Activity(activityText(step), System.nanoTime(), ""));
        synchronized (terminal) {
            if (tickerStarted) return;
            tickerStarted = true;
        }
        Thread.ofPlatform().daemon().name("oillamp-activity").start(this::tick);
    }

    /// What the user is told a step is doing. Plainer than the step's own description.
    private static String activityText(LampEvent.StepInfo step) {
        return switch (step.kind()) {
            case "BuildImage"          -> "building the sandbox image, the first build takes minutes";
            case "ExtractImageContext" -> "preparing the image build";
            case "RunContainer"        -> "starting the sandbox container";
            case "AwaitReady"          -> "waiting for the desktop and the shell to start";
            case "CheckEndpoints"      -> "checking that the desktop and the shell answer";
            default                    -> step.describe();
        };
    }

    private void stopActivity() {
        activity = Optional.empty();
        synchronized (terminal) { clearActivityLine(); }
    }

    /// Redraws the activity line until the process ends. Runs on a daemon thread.
    private void tick() {
        for (int frame = 0; ; frame++) {
            synchronized (terminal) {
                Optional<Activity> now = activity;
                if (now.isPresent()) drawActivityLine(now.get(), frame);
                else clearActivityLine();
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void drawActivityLine(Activity now, int frame) {
        long seconds = (System.nanoTime() - now.startedNanos()) / 1_000_000_000L;
        String elapsed = seconds < 60 ? seconds + "s" : seconds / 60 + "m " + String.format("%02ds", seconds % 60);
        String text = SPINNER[frame % SPINNER.length] + " " + elapsed + " · " + now.text()
                    + (now.latestOutput().isBlank() || verbose ? "" : " · " + printable(now.latestOutput()));
        int room = Math.max(20, width - 1);
        if (text.codePointCount(0, text.length()) > room)
            text = text.substring(0, text.offsetByCodePoints(0, room - 1)) + "…";
        System.out.print(WRAP_OFF + CLEAR_LINE + dim(text) + WRAP_ON);
        System.out.flush();
        activityShown = true;
    }

    /// A line of a program's output, without anything that would move the cursor or change colours.
    private static String printable(String line) {
        return NOT_PRINTABLE.matcher(line).replaceAll(" ").strip();
    }

    /// Removes the activity line, if one is on the screen. Call with the terminal lock held.
    private void clearActivityLine() {
        if (!activityShown) return;
        System.out.print(CLEAR_LINE);
        System.out.flush();
        activityShown = false;
    }
}
