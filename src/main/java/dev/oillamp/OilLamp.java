package dev.oillamp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Tuple;

/// The entry point of oillamp.
///
/// ```
/// OilLamp.on(Machine.real()).run("at", "/home/me/lamps/feature-x");
/// ```
///
/// [#run] takes the command line as the user would type it, rather than offering a method
/// per command, so tests go through the same argument parsing and usage errors a person does.
///
/// Public because it is how the tool is started, by `main`, by tests, and by any future
/// front end.
public final class OilLamp {

    /// Reported by `oillamp version`, and written into every lamp's identity file.
    public static final String VERSION = "0.1.0";

    private final Machine machine;
    private final List<Consumer<LampEvent>> listeners;
    /// Whether [Outcome] carries every event and the console text. Only `main` turns it off: it
    /// needs nothing but the exit code, and a session can run for days.
    private final boolean remember;

    private OilLamp(Machine machine, List<Consumer<LampEvent>> listeners, boolean remember) {
        this.machine = machine;
        this.listeners = listeners;
        this.remember = remember;
    }

    public static OilLamp on(Machine machine) {
        return new OilLamp(machine, List.of(), true);
    }

    /// The command-line entry point.
    public static void main(String[] argv) {
        System.exit(new OilLamp(Machine.real(), List.of(), false).run(argv).status().code());
    }

    /// Watches everything oillamp does, as it happens.
    ///
    /// Not needed to read the outcome ([Outcome] already carries every event), but a
    /// long-running session streams for minutes, so a live subscriber is what a front end uses.
    public OilLamp observedBy(Consumer<LampEvent> listener) {
        List<Consumer<LampEvent>> extended = new ArrayList<>(listeners);
        extended.add(listener);
        return new OilLamp(machine, List.copyOf(extended), remember);
    }

    /// Runs one command, exactly as it would be typed.
    ///
    /// @param argv for example `"at", "/home/me/lamps/x", "--dry-run"`
    public Outcome run(String... argv) {
        // A session reports from many threads at once: the event loop, the health check, every
        // proxied connection, every shell and the control socket. So the record is added to
        // under a lock, and each event is recorded, printed and passed on in one piece.
        List<LampEvent> recorded = new ArrayList<>();
        ConsoleRenderer console = ConsoleRenderer.forMachine(machine);
        if (!remember) console.forgetText();
        Consumer<LampEvent> sink = event -> {
            synchronized (recorded) {
                if (remember) recorded.add(event);
                console.render(event);
                for (Consumer<LampEvent> listener : listeners) listener.accept(event);
            }
        };
        ExitStatus status;
        try {
            status = Invocation.execute(machine, sink, console, VERSION, argv);
        } catch (RuntimeException | StackOverflowError failure) {
            // A bug in oillamp is reported as problem OIL-INTERNAL-001, with the start of the
            // stack trace as evidence, rather than as a raw stack trace on the console.
            sink.accept(new LampEvent.Failure(Problems.crash(failure)));
            status = ExitStatus.ERROR;
        }
        synchronized (recorded) {
            return new Outcome(status, Tuple.of(LampEvent.class, recorded), console.text());
        }
    }

    /// What happened: the exit code, everything oillamp said, and the console text it produced.
    ///
    /// @param console the rendered output, exactly as it appeared in the terminal (minus colour)
    public record Outcome(ExitStatus status, Tuple<LampEvent> events, String console) {

        public boolean succeeded() { return status.isSuccess(); }

        /// Every problem reported, warnings included.
        public Tuple<Problem> problems() {
            Tuple<Problem> out = Tuple.of(Problem.class);
            for (LampEvent event : events)
                switch (event) {
                    case LampEvent.Warning warning -> out = out.add(warning.problem());
                    case LampEvent.Failure failure -> out = out.add(failure.problem());
                    default -> { }
                }
            return out;
        }

        public Tuple<Problem> errors()   { return problems().retainIf(Problem::isError); }

        public Tuple<Problem> warnings() { return problems().retainIf(Problem::isWarning); }

        /// True when a problem with this code was reported — the usual thing to assert on.
        public boolean reported(String problemCode) {
            return problems().any(problem -> problem.code().value().equals(problemCode));
        }

        /// The one-line descriptions of everything oillamp did or, in a dry run, would do.
        public Tuple<String> steps() {
            Tuple<String> out = Tuple.of(String.class);
            for (LampEvent event : events)
                switch (event) {
                    case LampEvent.StepPlanned planned   -> out = out.add(planned.step().describe());
                    case LampEvent.StepStarted started   -> out = out.add(started.step().describe());
                    default -> { }
                }
            return out;
        }

        /// The fuller explanation attached to each step: what `--verbose` prints.
        ///
        /// The counterpart to [#steps()]. The detail includes the reason for each change,
        /// for example why each host package is installed, so tests can check it is there.
        public Tuple<String> stepDetails() {
            Tuple<String> out = Tuple.of(String.class);
            for (LampEvent event : events)
                switch (event) {
                    case LampEvent.StepPlanned planned -> out = out.add(planned.step().detail());
                    case LampEvent.StepStarted started -> out = out.add(started.step().detail());
                    default -> { }
                }
            return out;
        }

        /// The kinds of step, for example `"InstallPackages"`. Unaffected by wording changes.
        public Tuple<String> stepKinds() {
            Tuple<String> out = Tuple.of(String.class);
            for (LampEvent event : events)
                switch (event) {
                    case LampEvent.StepPlanned planned -> out = out.add(planned.step().kind());
                    case LampEvent.StepStarted started -> out = out.add(started.step().kind());
                    default -> { }
                }
            return out;
        }
    }
}
