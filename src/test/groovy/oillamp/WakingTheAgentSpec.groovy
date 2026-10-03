package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampEvent.RunOutcome
import dev.lamp.LampEvent.SaveKind
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  {@code schedule} and {@code ask}: waking the agent while a session runs.
 *
 *  <p>A session holds one agent: pi, in the sandbox, which oillamp starts and talks to. Jobs on the
 *  lamp's schedule wake it at set times, and {@code oillamp ask} wakes it at once. Every run begins
 *  a new conversation, so what the agent should remember is put into its prompt: its own notes,
 *  what recent runs changed, and what the last one said. The lamp is saved just before and just
 *  after each run, so the history shows what each run did, and any run can be undone.
 *
 *  <p>The agent here is a stand-in that answers the way pi does, given what each scenario wants it
 *  to do. Everything else is real: the session, the sockets, the schedule file and the history.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WakingTheAgentSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    /** A Tuesday afternoon: 16:15 in Berlin. */
    static final Instant NOW = Instant.parse('2026-09-22T14:15:03Z')

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(30_000)
        }
    }

    // ─── the schedule, from the command line ───────────────────────────────────────────────

    def 'A job the user adds is listed with when it runs next, on this machine\'s clock'() {
        reportInfo """
            "0 9 * * 1-5" means nine in the morning on weekdays, and the person who wrote it meant
            nine on their own clock. So a schedule is read in the host's time zone, and listed in
            it. The schedule is kept beside the history, where the agent cannot edit it.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            var added = schedule(lamp, 'add', '--cron', '0 9 * * 1-5', 'Check the nightly build and fix what broke')
            var listed = schedule(lamp)

        then: 'it runs next on Wednesday at nine, Berlin time'
            added.succeeded()
            var job = added.events().find { it instanceof LampEvent.JobAdded }.job()
            job.id() == 'job-1'
            job.author() == LampEvent.JobAuthor.USER
            job.next() == Optional.of(Instant.parse('2026-09-23T07:00:00Z'))
            job.expires().isEmpty()

        and: 'the listing shows it on the local clock'
            listed.console().contains('job-1')
            listed.console().contains('2026-09-23 09:00')
            listed.console().contains('Europe/Berlin')

        and: 'the schedule lives in the lamp\'s state, out of the agent\'s home'
            Files.exists(lamp.resolve('.oillamp/schedule.json'))
            !Files.exists(home(lamp).resolve('schedule.json'))
    }

    def 'The schedule lists when each job runs over the coming week, so it can be drawn as a timeline'() {
        reportInfo """
            An application that shows the schedule on a timeline needs every time a job runs, not
            just the next one, and cannot work them out itself without reading cron. So each job
            comes with its times over the next seven days, up to when it expires. A job switched
            off has none.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            schedule(lamp, 'add', '--cron', '0 9 * * 1-5', 'Weekday check')
            schedule(lamp, 'add', '--cron', '0 9 * * *', '--expires', 'in 3d', 'Daily, for three days')
            schedule(lamp, 'add', '--at', 'in 2h', 'Once')
            schedule(lamp, 'add', '--cron', '@hourly', 'Switched off')
            schedule(lamp, 'disable', 'job-4')
            var listed = jobs(lamp)

        then: 'nine on each weekday of the week ahead, which ends next Tuesday at 16:15'
            listed[0].upcoming().collect() == ['2026-09-23', '2026-09-24', '2026-09-25', '2026-09-28', '2026-09-29']
                    .collect { Instant.parse(it + 'T07:00:00Z') }

        and: 'the daily job only until it expires on Friday afternoon'
            listed[1].upcoming().size() == 3
            listed[1].upcoming().last() == Instant.parse('2026-09-25T07:00:00Z')

        and: 'the job that runs once, once; the one switched off, never'
            listed[2].upcoming().collect() == [NOW.plus(Duration.ofHours(2))]
            listed[3].upcoming().isEmpty()
    }

    def 'A time oillamp cannot read, or one that has passed, is refused, and says why'() {
        reportInfo """
            A schedule that silently never runs is worse than an error: the person finds out days
            later. So anything oillamp cannot turn into a time is refused at once, with what was
            wrong, and the schedule stays as it was.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            var badHour = schedule(lamp, 'add', '--cron', '0 25 * * *', 'x')
            var passed = schedule(lamp, 'add', '--at', '2026-01-01 09:00', 'x')
            var both = schedule(lamp, 'add', '--cron', '@daily', '--at', 'in 2h', 'x')

        then:
            [badHour, passed, both].every { !it.succeeded() && it.reported('OIL-SCHEDULE-001') }
            badHour.problems().first().whatHappened().contains('the hour 25 is not between 0 and 23')
            passed.problems().first().whatHappened().contains('has already passed')

        and: 'nothing was added'
            jobs(lamp).isEmpty()
    }

    def 'A job can be switched off and on, and removed, and the whole schedule paused'() {
        reportInfo """
            A person who wants the agent to leave a job alone for a week switches it off rather
            than deleting it and writing it again later. A person going on holiday pauses the whole
            schedule. Both only change what runs; the jobs stay as they were written.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            schedule(lamp, 'add', '--cron', '@hourly', 'Look at the queue')
            schedule(lamp, 'add', '--at', 'in 2h', 'Send the summary')

        when:
            var off = schedule(lamp, 'disable', 'job-1')
            var switchedOff = jobs(lamp)
            schedule(lamp, 'enable', 'job-1')
            var paused = schedule(lamp, 'pause')
            var pausedListing = schedule(lamp).console()
            schedule(lamp, 'resume')
            var removed = schedule(lamp, 'remove', 'job-2')
            var missing = schedule(lamp, 'remove', 'job-9')

        then:
            off.succeeded()
            !switchedOff.find { it.id() == 'job-1' }.enabled()
            switchedOff.find { it.id() == 'job-1' }.next().isEmpty()
            paused.succeeded()
            pausedListing.contains('paused')
            removed.events().any { it instanceof LampEvent.JobRemoved && it.job().id() == 'job-2' }
            missing.reported('OIL-SCHEDULE-003')

        and:
            jobs(lamp)*.id() == ['job-1']
            jobs(lamp).first().enabled()
    }

    def 'A job can be written with --cron=, --at= and --expires=, and a prompt that starts with a dash goes after --'() {
        reportInfo """
            `--cron=@daily` means the same as `--cron @daily`, so a job comes out the same
            whichever way it was typed. A prompt is often a list of things to do, and a list
            starts with a dash, which oillamp would take for an option it does not know. After
            `--`, every argument is taken as it is written.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            schedule(lamp, 'add', '--cron=0 9 * * 1-5', '--expires=in 3d', 'Weekday check')
            schedule(lamp, 'add', '--at=in 2h', 'Once')
            schedule(lamp, 'add', '--cron', '@daily', '--', '- read the inbox\n- answer what is urgent')
            var listed = jobs(lamp)

        then: 'the first runs at nine on weekdays, and only until Friday afternoon'
            listed[0].when().contains('0 9 * * 1-5')
            listed[0].next() == Optional.of(Instant.parse('2026-09-23T07:00:00Z'))
            listed[0].expires() == Optional.of(NOW.plus(Duration.ofDays(3)))

        and: 'the second runs once, two hours from now'
            listed[1].next() == Optional.of(NOW.plus(Duration.ofHours(2)))

        and: 'the third has its prompt exactly as written'
            listed[2].prompt() == '- read the inbox\n- answer what is urgent'
    }

    def 'With the schedule switched off, a job is kept, but the user is told it will not run'() {
        reportInfo """
            Waking the agent costs model tokens, so a lamp's schedule is off until its owner turns
            it on. Adding a job to a lamp whose schedule is off is allowed, since the person may be
            setting things up first, but they are told plainly that nothing will run yet.
        """
        given:
            var lamp = aLampThatHasRun('')

        when:
            var added = schedule(lamp, 'add', '--cron', '@daily', 'Tidy up')

        then:
            added.succeeded()
            added.reported('OIL-SCHEDULE-004')
            jobs(lamp).size() == 1
    }

    def 'A session started with --enable-scheduling runs the jobs, and leaves oillamp.toml as it is'() {
        reportInfo """
            Turning the schedule on for one session should not mean editing a file. With
            --enable-scheduling, the session runs the jobs as if `enabled = true` were set, and
            `oillamp schedule` says so while it runs. The lamp's own setting is untouched, so the
            next session without the flag runs no jobs again.
        """
        given: 'a lamp whose schedule is off, with a job for five minutes from now'
            var lamp = aLampThatHasRun('')
            var toml = Files.readString(lamp.resolve('oillamp.toml'))
            schedule(lamp, 'add', '--at', 'in 5m', 'Tidy up')
            anAgent { prompt -> 'Tidied up.' }

        when: 'a session starts ten minutes later with the flag'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp, '--enable-scheduling')
            var finished = waitFor(LampEvent.RunFinished)
            var during = schedule(lamp)
            stop(lamp)
            var after = schedule(lamp)

        then: 'the job ran'
            finished.run().job() == Optional.of('job-1')
            finished.outcome() == RunOutcome.FINISHED

        and: 'the schedule was on while the session ran, and is off again after it'
            during.events().find { it instanceof LampEvent.Schedule }.enabled()
            !after.events().find { it instanceof LampEvent.Schedule }.enabled()

        and: 'the lamp\'s configuration was not changed'
            Files.readString(lamp.resolve('oillamp.toml')) == toml
    }

    // ─── runs ──────────────────────────────────────────────────────────────────────────────

    def 'A job whose time has come wakes the agent, and the run is saved as a snapshot of its own'() {
        reportInfo """
            The whole point: the agent is woken with the job's prompt, works, and says what it did.
            The lamp is saved as the run ends, whatever happened, with the run's name and the
            agent's last words in the snapshot's message, so the history reads as a log of what
            the agent did and when. A job meant to run once is then off the schedule.

            A job that is due as the session starts runs at once, but only after the session has
            said it is open, so an application that waits for that hears of the run after it.
        """
        given: 'a job for five minutes from now'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            schedule(lamp, 'add', '--at', 'in 5m', 'Write the weekly report into ~/workspace/report.md')

        and: 'an agent that writes the report'
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/report.md'), '# Week 39\n')
                'I wrote the weekly report to ~/workspace/report.md.'
            }

        when: 'a session runs ten minutes later'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            var finished = waitFor(LampEvent.RunFinished)
            stop(lamp)

        then: 'the session said it was open before anything of the run'
            reported.any { it instanceof LampEvent.SessionOpened }
            reported.findIndexOf { it instanceof LampEvent.SessionOpened } <
                    reported.findIndexOf { it instanceof LampEvent.RunQueued || it instanceof LampEvent.RunStarted }

        and: 'the agent was woken with the job\'s prompt, as the job\'s first run'
            reported.find { it instanceof LampEvent.RunStarted }.run() == new LampEvent.Run('run-1', Optional.of('job-1'),
                    'Write the weekly report into ~/workspace/report.md', Optional.empty())
            prompts.size() == 1
            prompts[0].contains('You were woken by job-1, a job the user added')
            prompts[0].contains('## The task\n\nWrite the weekly report')
            prompts[0].contains('You have no notes yet')
            prompts[0].contains('This is the first run.')

        and: 'it finished, and said what it did'
            finished.outcome() == RunOutcome.FINISHED
            finished.answer() == 'I wrote the weekly report to ~/workspace/report.md.'

        and: 'the run is a snapshot, named after it and holding the agent\'s words'
            var snapshot = finished.snapshot().orElseThrow()
            snapshot.kind() == SaveKind.RUN
            snapshot.run() == Optional.of('run-1')
            snapshot.message().contains('I wrote the weekly report')
            history(lamp).first().id() == snapshot.id()

        and: 'the history says which job it was, how it ended, and where its conversation is'
            with (history(lamp).first()) {
                job() == Optional.of('job-1')
                outcome() == Optional.of(RunOutcome.FINISHED)
                conversation().isPresent()
                conversation() == finished.conversation()
            }

        and: 'the job ran once, as it was meant to, and is gone'
            reported.any { it instanceof LampEvent.JobRemoved && it.job().id() == 'job-1' }
            jobs(lamp).isEmpty()
    }

    def 'The next run is told what the last one did: its notes, the files it changed and its last words'() {
        reportInfo """
            A job wakes the agent in a new conversation, so on its own it would remember nothing.
            Its prompt therefore carries what it needs: the notes it left itself in
            ~/workspace/NOTES.md, which files the recent runs changed, and what the last run said
            at its end. The notes are marked as the agent's own, not as the user's instructions.
        """
        given: 'two jobs, one after the other'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            schedule(lamp, 'add', '--at', 'in 1m', 'Do the first task')
            schedule(lamp, 'add', '--at', 'in 2m', 'Do the second task')
            anAgent { prompt ->
                if (prompt.contains('Do the first task')) {
                    Files.writeString(home(lamp).resolve('workspace/NOTES.md'), 'Next: add the chart.\n')
                    Files.writeString(home(lamp).resolve('workspace/data.csv'), 'a,b\n')
                    return 'Collected the data.'
                }
                'Added the chart.'
            }

        when: 'a session runs once both are due'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished) { it.run().id() == 'run-2' }
            stop(lamp)

        then: 'the second run was given the notes, the changes and the last words of the first'
            prompts.size() == 2
            prompts[1].contains('You were woken by job-2')
            prompts[1].contains('<notes>\nNext: add the chart.\n</notes>')
            prompts[1].contains('run-1')
            prompts[1].contains('~/workspace/data.csv (added)')
            prompts[1].contains('~/workspace/NOTES.md (added)')
            prompts[1].contains('<last-message>\nCollected the data.\n</last-message>')
    }

    def 'What a person asks goes to the agent exactly as they wrote it'() {
        reportInfo """
            A question someone asks is what a chat shows as their message, so it is never wrapped
            in notes or recent runs. The agent's guide already tells it to read its notes.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt -> 'Fine.' }
            startASession(lamp)

        when:
            host.oillamp.run('ask', lamp.toString(), 'How are you?')

        then:
            prompts == ['How are you?']
    }

    def 'Asking a busy agent waits its turn'() {
        reportInfo """
            The agent works on one thing at a time. A second question, or a job that comes due,
            while it is busy is not refused and not run alongside: it waits, and the session says
            so, and it runs as soon as the agent is free.
        """
        given:
            var lamp = aLampThatHasRun('')
            var release = new CountDownLatch(1)
            anAgent { prompt ->
                if (prompt == 'slow') release.await()
                'answered: ' + prompt
            }
            startASession(lamp)

        when:
            var slow = Thread.start { assert host.oillamp.run('ask', lamp.toString(), 'slow').succeeded() }
            waitFor(LampEvent.RunStarted)
            var quick = null
            var second = Thread.start { quick = host.oillamp.run('ask', lamp.toString(), 'quick') }
            var queued = waitFor(LampEvent.RunQueued)
            release.countDown()
            slow.join(20_000)
            second.join(20_000)

        then:
            queued.run().prompt() == 'quick'
            quick.succeeded()
            quick.console().contains('answered: quick')
            prompts == ['slow', 'quick']
    }

    def 'A run that takes too long is stopped, and what it did so far is saved'() {
        reportInfo """
            An agent can loop, or wait for something that never comes, and every minute it does
            costs money. A run is therefore stopped after schedule.max_run_minutes. What it had
            done by then is saved like any run, marked as stopped, so it can be looked at or undone.
        """
        given: 'runs of at most one minute, on a clock that runs ten minutes a second'
            var lamp = aLampThatHasRun('''
                [schedule]
                max_run_minutes = 1

                [timeouts]
                container_ready_seconds  = 3600
                terminal_connect_seconds = 3600
            '''.stripIndent())
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/half-done.txt'), 'started\n')
                Thread.sleep(60_000)
                'never reached'
            }
            host.machine { it.clockRunsFaster(600) }
            startASession(lamp)

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Work forever')

        then:
            !asked.succeeded()
            var finished = asked.events().find { it instanceof LampEvent.RunFinished }
            finished.outcome() == RunOutcome.TIMED_OUT
            asked.console().contains('ran out of time')

        and: 'the half-done work is in the run\'s snapshot'
            var snapshot = history(lamp).first()
            snapshot.kind() == SaveKind.RUN
            snapshot.message().contains('timed out')
    }

    def 'Ending the session in the middle of a run stops it, and saves it first'() {
        reportInfo """
            Ctrl-C, closing the terminal or oillamp stop must not lose a run's work, and must not
            leave the agent working in a sandbox that is about to disappear. The session tells the
            agent to stop, saves the run, and only then stops the sandbox.
        """
        given:
            var lamp = aLampThatHasRun('')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/partial.txt'), 'some of it\n')
                Thread.sleep(60_000)
                'never reached'
            }
            startASession(lamp)

        when:
            Thread.start { host.oillamp.run('ask', lamp.toString(), 'A long task') }
            waitFor(LampEvent.RunStarted)
            Thread.sleep(300)
            stop(lamp)

        then:
            var finished = reported.find { it instanceof LampEvent.RunFinished }
            finished.outcome() == RunOutcome.INTERRUPTED
            var run = history(lamp).find { it.kind() == SaveKind.RUN }
            run.message().contains('interrupted')
            !reported.any { it instanceof LampEvent.Failure }
    }

    def 'Without pi in the sandbox, a run fails and says why, and the session carries on'() {
        reportInfo """
            A lamp whose image has no pi, or a pi that crashes at start, cannot do a run. That is
            reported where the user looks, with what pi said, and the session stays up: the
            sandbox itself is fine.
        """
        given: 'a sandbox without an agent'
            var lamp = aLampThatHasRun('')
            startASession(lamp)

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Hello?')

        then:
            !asked.succeeded()
            asked.events().find { it instanceof LampEvent.RunFinished }.outcome() == RunOutcome.FAILED
            reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SCHEDULE-005'
                           && it.problem().whatHappened().contains('pi: command not found') }
            session.alive
    }

    def 'What the agent says cannot take over the user\'s terminal'() {
        reportInfo """
            The agent's answer is printed in the terminal the user started oillamp from, on the
            host. A terminal obeys escape sequences in what it prints: they can clear the screen,
            rewrite lines, or change the window's title. So everything the agent wrote is printed
            with such sequences made harmless, as the output of any program in the sandbox is.
        """
        given:
            var lamp = aLampThatHasRun('')
            anAgent { prompt -> 'Done.\u001B[2J\u001B]0;owned\u0007 All fine.' }
            startASession(lamp)

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Anything')

        then:
            asked.succeeded()
            asked.console().contains('Done.')
            !asked.console().contains('\u001B[2J')
            !asked.console().contains('\u001B]0;')
    }

    // ─── the agent's side ──────────────────────────────────────────────────────────────────

    def 'The agent adds and removes its own jobs through the session, within the limits the user set'() {
        reportInfo """
            The agent's tools do not write the schedule. They ask the session over a socket, and
            the session applies the rules: the agent may have only so many jobs, which may not run
            too often, and which expire. It can read the user's jobs, but not remove them. A
            refusal says what the rule is, so the agent can adjust.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_jobs = 1\n')
            schedule(lamp, 'add', '--cron', '@daily', 'The user\'s own job')
            startASession(lamp)

        when:
            var tooOften = agentAsks(op: 'add', cron: '*/5 * * * *', prompt: 'Check the build')
            var hourly = agentAsks(op: 'add', cron: '0 * * * *', prompt: 'Check the build')
            var oneTooMany = agentAsks(op: 'add', at: 'in 2h', prompt: 'Another one')
            var usersJob = agentAsks(op: 'remove', id: 'job-1')
            var listed = agentAsks(op: 'list')

        then:
            !tooOften.ok
            tooOften.error.contains('may run at most every 15 minutes')
            hourly.ok
            hourly.text.startsWith('Added job-2')
            !oneTooMany.ok
            oneTooMany.error.contains('may have 1 jobs')
            !usersJob.ok
            usersJob.error.contains('only the user can remove it')
            listed.text.contains('job-1 (the user\'s; you cannot change it)')
            listed.text.contains('job-2 (yours)')

        and: 'the agent\'s job ends after fourteen days, although it asked for no end'
            var job = jobs(lamp).find { it.id() == 'job-2' }
            job.author() == LampEvent.JobAuthor.AGENT
            job.expires() == Optional.of(NOW.plus(Duration.ofDays(14)))

        and: 'the session reported the new job'
            reported.any { it instanceof LampEvent.JobAdded && it.job().id() == 'job-2' }
    }

    def 'The agent looks back at an earlier run, as the host\'s history holds it'() {
        reportInfo """
            The prompt describes only the last few runs. To look further back, the agent asks for a
            run by name, and gets what it changed and what it said at its end, from the history on
            the host, which the agent cannot see or change.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/answer.txt'), '42\n')
                'The answer is in answer.txt.'
            }
            startASession(lamp)
            host.oillamp.run('ask', lamp.toString(), 'Find the answer')

        when:
            var all = agentAsks(op: 'history')
            var one = agentAsks(op: 'history', run: 'run-1')
            var none = agentAsks(op: 'history', run: 'run-7')

        then:
            all.text.contains('run-1')
            one.text.contains('~/workspace/answer.txt (added)')
            one.text.contains('The answer is in answer.txt.')
            none.text.contains('There is no run called \'run-7\'')
    }

    def 'With the schedule on, the agent is told how it works, and pi is given the tools'() {
        reportInfo """
            AGENTS.md is what the agent reads about the machine it is on. With the schedule on, it
            also says what the scheduling tools do, what limits apply, and that the agent's notes
            are its only memory between runs. The tools themselves are a pi extension that oillamp
            puts in pi's directory each session, and takes away again when the schedule is off.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_jobs = 3\n')
            var guide = Files.readString(home(lamp).resolve('AGENTS.md'))
            var tools = home(lamp).resolve('.pi/agent/extensions/oillamp-schedule.js')

        expect:
            guide.contains('## Working on a schedule')
            guide.contains('at most 3 jobs at once')
            guide.contains('~/workspace/NOTES.md')
            Files.readString(tools).contains('/oillamp/sockets/host/schedule.sock')

        when: 'the schedule is switched off again'
            Files.writeString(lamp.resolve('oillamp.toml'),
                    Files.readString(lamp.resolve('oillamp.toml')).replace('enabled = true', 'enabled = false'))
            host.oillamp.run('at', lamp.toString())

        then:
            !Files.readString(home(lamp).resolve('AGENTS.md')).contains('## Working on a schedule')
            !Files.exists(tools)
    }

    def 'An application schedules jobs and asks the agent through the Lamp API'() {
        reportInfo """
            An application that holds a lamp offers the same with its own controls: it reads and
            changes the schedule, and asks the agent something and gets the answer back as a
            value, with the snapshot that recorded the run.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt -> 'Hello from the sandbox.' }
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)

        when:
            var job = lamps.repeat('0 8 * * *', 'Morning check')
            var once = lamps.once('in 1h', '- starts with a dash')
            var listed = lamps.schedule()
            lamps.unschedule(job.id())
            var ending = lamps.repeat('0 8 * * *', 'For two days', NOW.plus(Duration.ofDays(2)))
            var atAnInstant = lamps.once(NOW.plus(Duration.ofMinutes(90)), 'At an instant')
            lamps.disable(ending.id())
            var switchedOff = lamps.schedule().jobs().find { it.id() == ending.id() }
            lamps.enable(ending.id())
            lamps.pause()
            var paused = lamps.schedule().paused()
            lamps.resume()
            lamps.unschedule(ending.id())
            lamps.unschedule(atAnInstant.id())
            var running = lamps.onEvent { reported << it }.start()
            assert running.awaitRunning(Duration.ofSeconds(30))
            var answer = running.ask('Say hello')
            running.close()

        then:
            job.when() == '0 8 * * *'
            once.prompt() == '- starts with a dash'
            listed.jobs()*.id() == ['job-1', 'job-2']
            lamps.schedule().jobs()*.id() == ['job-2']
            ending.expires() == Optional.of(NOW.plus(Duration.ofDays(2)))
            atAnInstant.next() == Optional.of(NOW.plus(Duration.ofMinutes(90)))
            !switchedOff.enabled()
            paused
            !lamps.schedule().paused()
            answer.outcome() == RunOutcome.FINISHED
            answer.answer() == 'Hello from the sandbox.'
            answer.snapshot().isPresent()

        and: 'the run happened in the session the application holds'
            reported.any { it instanceof LampEvent.RunStarted && it.run().prompt() == 'Say hello' }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private Path aLampThatHasRun(String toml) {
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n' + toml.stripIndent())
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        lamp
    }

    private void anAgent(Closure<String> work) {
        host.machine { it.agent { String prompt -> prompts << prompt; work(prompt) } }
    }

    private static Path home(Path lamp) { Lamp.agentHome(lamp).orElseThrow() }

    private def schedule(Path lamp, String... arguments) {
        host.oillamp.run(*(['schedule', lamp.toString()] + arguments.toList()))
    }

    private List<LampEvent.Job> jobs(Path lamp) {
        var listed = schedule(lamp)
        assert listed.succeeded()
        listed.events().find { it instanceof LampEvent.Schedule }.jobs().collect()
    }

    private List<LampEvent.Snapshot> history(Path lamp) {
        var listed = host.oillamp.run('history', lamp.toString())
        assert listed.succeeded()
        listed.events().find { it instanceof LampEvent.History }.snapshots().collect()
    }

    private void startASession(Path lamp, String... options) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(90)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run(*(['at', lamp.toString()] + options.toList())) }
        waitFor(LampEvent.Summary) { it.title() == 'your session is up' }
    }

    private void stop(Path lamp) {
        assert host.oillamp.run('stop', lamp.toString()).succeeded()
        session.join(30_000)
        assert !session.alive
    }

    private <T extends LampEvent> T waitFor(Class<T> kind, Closure<Boolean> which = { true }) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = reported.find { kind.isInstance(it) && which(it) }
            if (found != null) return (T) found
            assert System.currentTimeMillis() < deadline : "no ${kind.simpleName} came"
            Thread.sleep(50)
        }
    }

    /** What the agent's tools do: one JSON line to the session's schedule socket, one line back. */
    private Map agentAsks(Map request) {
        var socket = host.runtime.resolve('oillamp/k3v7x2ab/sockets/host/schedule.sock')
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).withCloseable { channel ->
            channel.write(ByteBuffer.wrap((JsonOutput.toJson(request) + '\n').getBytes(StandardCharsets.UTF_8)))
            channel.shutdownOutput()
            var answer = new ByteArrayOutputStream()
            var buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                answer.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            (Map) new JsonSlurper().parseText(answer.toString(StandardCharsets.UTF_8).readLines().first())
        }
    }
}
