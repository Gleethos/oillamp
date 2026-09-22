package dev.oillamp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import sprouts.Tuple;

/**
 * oillamp itself — the one thing a caller needs.
 *
 * <pre>{@code
 * OilLamp.on(Machine.real()).run("at", "/home/me/lamps/feature-x");
 * }</pre>
 *
 * <p>The public surface of this project is deliberately four ideas: this entry point, the
 * {@link Machine} it runs against, the {@link LampEvent}s it emits, and the {@link Problem}s it
 * reports. Everything else — the planners, the policy engine, the podman adapter, the session
 * machine — is implementation, and is free to change without breaking anyone.
 *
 * <p>The entry point takes an argument vector rather than typed methods on purpose: it is what
 * the user actually types, so a scenario exercises the same path a person does, argument parsing
 * and usage errors included.
 */
public final class OilLamp {

    /** Reported by {@code oillamp version}, and written into every lamp's identity file. */
    public static final String VERSION = "0.1.0";

    private final Machine machine;
    private final List<Consumer<LampEvent>> listeners;

    private OilLamp(Machine machine, List<Consumer<LampEvent>> listeners) {
        this.machine = machine;
        this.listeners = listeners;
    }

    public static OilLamp on(Machine machine) {
        return new OilLamp(machine, List.of());
    }

    /** The command-line entry point. */
    public static void main(String[] argv) {
        System.exit(on(Machine.real()).run(argv).status().code());
    }

    /**
     * Watches everything oillamp does, as it happens.
     *
     * <p>Not needed to read the outcome — {@link Outcome} already carries every event — but a
     * long-running session streams for minutes, so a live subscriber is what a front end uses.
     */
    public OilLamp observedBy(Consumer<LampEvent> listener) {
        List<Consumer<LampEvent>> extended = new ArrayList<>(listeners);
        extended.add(listener);
        return new OilLamp(machine, List.copyOf(extended));
    }

    /**
     * Runs one command, exactly as it would be typed.
     *
     * @param argv for example {@code "at", "/home/me/lamps/x", "--dry-run"}
     */
    public Outcome run(String... argv) {
        List<LampEvent> recorded = new ArrayList<>();
        ConsoleRenderer console = ConsoleRenderer.forMachine(machine);
        Consumer<LampEvent> sink = event -> {
            recorded.add(event);
            console.render(event);
            for (Consumer<LampEvent> listener : listeners) listener.accept(event);
        };
        ExitStatus status;
        try {
            status = Invocation.execute(machine, sink, console, VERSION, argv);
        } catch (RuntimeException | StackOverflowError failure) {
            // A bug in oillamp still has to reach the user as something they can report,
            // not as a stack trace on the console (NFR-03). The trace goes to --debug.
            sink.accept(new LampEvent.Failure(Problems.crash(failure)));
            status = ExitStatus.ERROR;
        }
        return new Outcome(status, Tuple.of(LampEvent.class, recorded), console.text());
    }

    /**
     * What happened: the exit code, everything oillamp said, and the console text it produced.
     *
     * @param console the rendered output, exactly as it appeared in the terminal (minus colour)
     */
    public record Outcome(ExitStatus status, Tuple<LampEvent> events, String console) {

        public boolean succeeded() { return status.isSuccess(); }

        /** Every problem reported, warnings included. */
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

        /** True when a problem with this code was reported — the usual thing to assert on. */
        public boolean reported(String problemCode) {
            return problems().any(problem -> problem.code().value().equals(problemCode));
        }

        /** The one-line descriptions of everything oillamp did or, in a dry run, would do. */
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

        /** The kinds of step, e.g. {@code "InstallPackages"} — stable across wording changes. */
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
