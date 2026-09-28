package oillamp

import dev.lamp.LampEvent
import dev.lamp.Problem
import spock.lang.Specification
import sprouts.Tuple

import java.nio.file.Path
import java.time.Duration

/**
 *  How events travel from an embedded oillamp to the application that started it: one line of
 *  JSON each.
 *
 *  <p>The engine writes them in one process and the application reads them in another. If the
 *  two ever disagreed, the application would show the player nonsense or nothing at all, so
 *  every kind of event is written and read back here.
 */
class TheEventWireFormatSpec extends Specification {

    static final Problem PROBLEM = new Problem(
            new Problem.Code('OIL-SANDBOX-002'), Problem.Severity.ERROR,
            'The sandbox stopped while starting up',
            'the container exited with code 70',
            'there is no sandbox to work in',
            Tuple.of(Problem.Evidence,
                    new Problem.Evidence.Command(Tuple.of(String, 'podman', 'run'), 70,
                                                 'entrypoint: missing setting', Duration.ofMillis(1234)),
                    new Problem.Evidence.File(Path.of('/tmp/lamp/oillamp.toml'), 'the configuration'),
                    new Problem.Evidence.Value('podman', '4.9.3'),
                    new Problem.Evidence.Excerpt('container log', 'line one\nline "two"\t\\'),
                    new Problem.Evidence.Config(Path.of('/tmp/lamp/oillamp.toml'),
                                                'network.rules[0].cidrs', '"10/8"', 'an address range')),
            Tuple.of(Problem.Fix, Problem.Fix.of('look at the log'),
                                  Problem.Fix.run('stop it', 'oillamp stop /tmp/lamp')),
            Optional.of(Path.of('/tmp/lamp/.oillamp/logs/x.log')))

    static final LampEvent.StepInfo STEP = new LampEvent.StepInfo('WriteFile', 'write a file', 'because')

    /** One of every kind of event, with every optional part both present and absent somewhere. */
    static final List<LampEvent> EXAMPLES = [
            new LampEvent.PhaseStarted(LampEvent.Phase.SESSION),
            new LampEvent.PhaseFinished(LampEvent.Phase.IMAGE, Duration.ofSeconds(3)),
            new LampEvent.Ok('host', 'podman 4.9.3, rootless, crun'),
            new LampEvent.Info('network', 'denied 10.0.0.1:5432 — "block private"'),
            new LampEvent.StepPlanned(STEP),
            new LampEvent.StepStarted(STEP),
            new LampEvent.StepSucceeded(STEP, Duration.ofMillis(15)),
            new LampEvent.StepSkipped(STEP, 'already there'),
            new LampEvent.Output('sway', '[sway] 00:00:01 ✓ ünïcödé'),
            new LampEvent.Answer('multi\nline\nanswer'),
            new LampEvent.SessionStateChanged(new LampEvent.SessionStatus('running', 'up', Duration.ofMinutes(2), 1)),
            new LampEvent.WindowOpened('the desktop viewer', Tuple.of(String, 'vncviewer', '/run/x.sock')),
            new LampEvent.Summary('session 20260928-120000', Tuple.of(String)),
            new LampEvent.Warning(PROBLEM.withFix(Problem.Fix.of('another'))),
            new LampEvent.Failure(new Problem(new Problem.Code('OIL-LOCK-001'), Problem.Severity.ERROR,
                    'busy', '', '', Tuple.of(Problem.Evidence), Tuple.of(Problem.Fix), Optional.empty())),
            new LampEvent.Failure(PROBLEM),
    ]

    def 'Every event reads back as the same value it was written from'() {
        reportInfo """
            Text with line breaks, quotes, tabs and backslashes, problems with every kind of
            evidence, and parts that are absent: all come back exactly as they went out.
        """
        expect:
            EXAMPLES.every { event -> LampEvent.fromJson(event.toJson()) == Optional.of(event) }
    }

    def 'Each event is one line, so a reader can split the stream on line breaks'() {
        reportInfo """
            The application reads the engine's output one line at a time and treats each line as
            one event. Some events carry text with line breaks in it, such as a container log in
            a problem's evidence. If such a break reached the output as it is, one event would
            arrive as two broken halves, and neither could be read. So a line break inside an
            event is always written as an escape, and only the end of an event ends a line.
        """
        expect:
            EXAMPLES.every { !it.toJson().contains('\n') }
    }

    def 'The examples above cover every kind of event there is'() {
        reportInfo """
            So that a new kind of event cannot be added without being checked here.
        """
        expect:
            LampEvent.permittedSubclasses
                     .findAll { !it.interface }
                     .every { kind -> EXAMPLES.any { kind.isInstance(it) } }
    }

    def 'A line that is not an event this oillamp knows is skipped, not a failure'() {
        reportInfo """
            An application may read from a newer oillamp than it was built with, or receive a line
            that was cut short. Either way it should carry on with the next line.
        """
        expect:
            LampEvent.fromJson(line).isEmpty()

        where:
            line << ['', 'not json', '{}', '{"type":"FromTheFuture","x":1}', '{"type":"Ok","area":"x"}',
                     '{"type":"Failure","problem":{"code":{"value":"nonsense"}}}']
    }
}
