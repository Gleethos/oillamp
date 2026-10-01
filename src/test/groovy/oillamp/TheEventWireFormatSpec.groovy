package oillamp

import dev.lamp.LampEvent
import dev.lamp.Problem
import spock.lang.Specification
import sprouts.Tuple

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

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
    /** A snapshot with every part present, a message with a line break in it among them. */
    static final LampEvent.Snapshot SNAPSHOT = new LampEvent.Snapshot(
            '3f2a9c1d00000000000000000000000000000000', Instant.parse('2026-09-29T18:14:03Z'),
            LampEvent.SaveKind.BEFORE_RESTORE, 'before the upgrade\nand the "migration"',
            Optional.of('20260929-181200'), Optional.of('run-12'), Optional.of('job-3'),
            Optional.of(LampEvent.RunOutcome.TIMED_OUT), Optional.of('0199a3f0-7c1e-7d2b-9a41-5c3e2f1d0b9a'))

    static final LampEvent.Job JOB = new LampEvent.Job('job-3', '0 9 * * 1-5',
            Optional.of(Instant.parse('2026-09-30T07:00:00Z')), 'Check the build\nand say "why"',
            LampEvent.JobAuthor.AGENT, Instant.parse('2026-09-29T18:14:03Z'),
            Optional.of(Instant.parse('2026-10-13T18:14:03Z')), true,
            Tuple.of(Instant, Instant.parse('2026-09-30T07:00:00Z'), Instant.parse('2026-10-01T07:00:00Z')))

    static final dev.lamp.Lamp.Conversation CONVERSATION = new dev.lamp.Lamp.Conversation(
            '01a0ec69-de1c-7234-914c-b970a862c13e', 'run-12 (job-3)', '.pi/agent/sessions/--home-agent--/x.jsonl',
            Instant.parse('2026-09-29T18:14:03Z'), Instant.parse('2026-09-29T18:15:00Z'),
            Tuple.of(dev.lamp.Lamp.Conversation.Entry,
                new dev.lamp.Lamp.Conversation.Entry('q1', Optional.empty(), Instant.parse('2026-09-29T18:14:03Z'),
                        dev.lamp.Lamp.Conversation.Kind.MESSAGE_TO_AGENT, 'What "now"?\nTell me.', '',
                        Tuple.of(dev.lamp.Lamp.Conversation.ToolCall), Optional.empty(), false),
                new dev.lamp.Lamp.Conversation.Entry('a1', Optional.of('q1'), Instant.parse('2026-09-29T18:15:00Z'),
                        dev.lamp.Lamp.Conversation.Kind.MESSAGE_FROM_AGENT, '', 'thinking…',
                        Tuple.of(dev.lamp.Lamp.Conversation.ToolCall, new dev.lamp.Lamp.Conversation.ToolCall('c1', 'bash', 'ls')),
                        Optional.of('bash'), true)))

    static final LampEvent.Run RUN = new LampEvent.Run('run-12', Optional.of('job-3'), 'Check the build', Optional.empty())

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
            new LampEvent.SessionOpened('20260928-120000', Tuple.of(String, 'ssh', '-T', 'lamp-k3v7x2ab'),
                                        Path.of('/run/user/1000/oillamp/k3v7x2ab/sockets/infra/vnc.sock'), 1920, 1080),
            new LampEvent.LookAtDesktop('the chart you asked for'),
            new LampEvent.WindowOpened('the desktop viewer', Tuple.of(String, 'vncviewer', '/run/x.sock')),
            new LampEvent.Summary('session 20260928-120000', Tuple.of(String)),
            new LampEvent.Saved(SNAPSHOT, 1234),
            new LampEvent.History(Tuple.of(LampEvent.Snapshot, SNAPSHOT,
                    new LampEvent.Snapshot('0123456789abcdef0123456789abcdef01234567', Instant.EPOCH,
                                           LampEvent.SaveKind.IDLE, '', Optional.empty(), Optional.empty(),
                                           Optional.empty(), Optional.empty(), Optional.empty()))),
            new LampEvent.Restored(SNAPSHOT, new LampEvent.Snapshot('fedcba9876543210fedcba9876543210fedcba98',
                    Instant.parse('2026-09-29T19:00:00Z'), LampEvent.SaveKind.RESTORE, 'back to 3f2a9c1d',
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())),
            new LampEvent.Schedule(true, false, 'Europe/Berlin', Tuple.of(LampEvent.Job, JOB,
                    new LampEvent.Job('job-4', 'once at 2026-10-01 09:00', Optional.empty(), 'x',
                                      LampEvent.JobAuthor.USER, Instant.EPOCH, Optional.empty(), false, Tuple.of(Instant)))),
            new LampEvent.JobAdded(JOB),
            new LampEvent.JobRemoved(JOB, 'it expired'),
            new LampEvent.ScheduleChanged('the schedule is paused'),
            new LampEvent.RunQueued(new LampEvent.Run('run-13', Optional.empty(), 'what now?', Optional.of('01a0ec69-de1c')), 0),
            new LampEvent.RunStarted(RUN),
            new LampEvent.RunFinished(RUN, LampEvent.RunOutcome.FINISHED, 'All green.\n\nNothing to do.',
                    Optional.of(SNAPSHOT), Duration.ofSeconds(95), Optional.of('01a0ec69-de1c')),
            new LampEvent.RunFinished(RUN, LampEvent.RunOutcome.TIMED_OUT, '', Optional.empty(), Duration.ofMinutes(30), Optional.empty()),
            new LampEvent.RunAccepted(RUN),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.Opened('01a0ec69-de1c')),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.Said('Hel')),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.Thought('hmm\n"quoted"')),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.Answered('Hello.', false)),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.ToolStarted('call-1', 'bash', 'ls -la')),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.ToolFinished('call-1', true, 'no such file')),
            new LampEvent.RunProgress('run-12', new LampEvent.Progress.Retrying(2, 3, '529 overloaded')),
            new LampEvent.AgentStatus(Optional.of(RUN), Tuple.of(LampEvent.Run, RUN)),
            new LampEvent.AgentStatus(Optional.empty(), Tuple.of(LampEvent.Run)),
            new LampEvent.Conversations(Tuple.of(dev.lamp.Lamp.Conversation, CONVERSATION)),
            new LampEvent.ConversationShown(CONVERSATION),
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
