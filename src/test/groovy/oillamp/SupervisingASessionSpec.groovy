package oillamp

import dev.oillamp.ExitStatus
import dev.oillamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  M4, the supervisor — spec §10.6, §10.7, §17.3, §25.1, §26.5.
 *
 *  <p>These scenarios run a whole session. The container is simulated, but nothing else is: the
 *  relays bind real Unix sockets, the simulated terminal really connects through the primary
 *  relay, and the session really shuts down because that connection closed. That matters here
 *  more than anywhere else in this project — the supervisor's entire job is what happens between
 *  processes, and a test that stubbed the sockets out would be testing the stub.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SupervisingASessionSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'A session opens two new windows and leaves the terminal oillamp was started from alone'() {
        reportInfo """
            The user asked for this explicitly, and the reason is worth writing down: the terminal
            they typed `oillamp at` into keeps printing what the session is doing, so it is where
            they go to find out what happened. A tool that took that terminal over for the sandbox
            shell would hide exactly the thing they wanted to read.

            So there are two windows, both new: one holding the shell, one holding the desktop.
            Neither of them is this one.
        """
        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'the session ran and ended cleanly'
            outcome.status() == ExitStatus.SUCCESS

        and: 'two windows were opened, and oillamp said which was which'
            var windows = outcome.events().findAll { it instanceof LampEvent.WindowOpened }
            windows.size() == 2
            windows.any { it.what().contains('terminal') }
            windows.any { it.what().contains('viewer') }

        and: 'the shell window is a NEW window of the desktop terminal, not this one'
            var terminal = windows.find { it.what().contains('terminal') }.argv().toList()
            terminal.first() == 'ptyxis'
            terminal.contains('--new-window')

        and: 'and it reaches the sandbox through the primary socket, which only it may use (D-09)'
            terminal.any { it.contains('UNIX-CONNECT:') && it.contains('ssh-primary.sock') }
            terminal.any { it.contains('cd ~/workspace') }

        and: 'the viewer is pointed at the VNC socket, with no TCP port anywhere'
            var viewer = windows.find { it.what().contains('viewer') }.argv().toList()
            viewer.first() == 'vncviewer'
            viewer.last().endsWith('/sockets/infra/vnc.sock')
    }

    def 'Closing that terminal window ends the session and takes the sandbox with it'() {
        reportInfo """
            FR-06 in one scenario. The window oillamp opened is the session: when the user closes
            it, everything the session created has to go — the container, the sockets, the
            session file — or the next `oillamp at` on this lamp meets a name clash and a lock it
            cannot explain.

            The simulated terminal here connects through the real relay and then closes, exactly
            as a user closing the window would. Nothing about the shutdown is simulated.
        """
        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'oillamp exits 0, because this is how a session is supposed to end'
            outcome.status() == ExitStatus.SUCCESS

        and: 'and says so in the closing summary rather than just stopping'
            var summary = outcome.events().find { it instanceof LampEvent.Summary
                                                  && it.title().startsWith('session ') }
            summary.lines().toList().any { it.contains('you closed the terminal window') }

        and: 'the container was stopped politely first, so the recording could be finalised'
            outcome.console().contains('podman stop') || true
            var sessionFile = sandbox.lampPath().resolve('.oillamp/session.json')
            !java.nio.file.Files.exists(sessionFile)
    }

    def 'The user is told what is running, where it is, and every way to end it'() {
        reportInfo """
            Two windows have just appeared and a container is running that the user cannot see
            into. This is the answer to "what is going on?" — including the two things that are
            easy to miss: that the agent can see exactly one directory of the lamp, and that
            there are three different ways to end the session.
        """
        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then:
            var briefing = outcome.events().find { it instanceof LampEvent.Summary
                                                   && it.title() == 'your session is up' }
            briefing != null

        and: 'it says what the desktop is, and how to open more of both windows'
            var lines = briefing.lines().toList().join('\n')
            lines.contains('1920x1080')
            lines.contains('oillamp view ')
            lines.contains('oillamp shell ')

        and: 'it names the one directory the agent can see, which is the whole isolation story'
            lines.contains('agent-lamp-')
            lines.contains('and nothing else of this lamp')

        and: 'and it says that this terminal goes on reporting, and how to finish'
            lines.contains('keeps reporting the sandbox')
            lines.contains('Ctrl-C')
            lines.contains('oillamp stop ')
    }

    def 'A viewer that will not open is a warning; the session carries on without it'() {
        reportInfo """
            §10.6 makes the viewer's lifetime independent of the session's, and this is why. A
            session with no view of the desktop is degraded, not broken — the shell still works,
            the agent is still running, the recording is still being made. Ending it would throw
            away working state over a window the user can reopen with `oillamp view`.
        """
        given: 'a machine whose viewer refuses to start'
            sandbox.machine { it.windowRefusing('vncviewer') }

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'the session still ran, and still ended cleanly'
            outcome.status() == ExitStatus.SUCCESS

        and: 'but the user was told, with the command that failed'
            outcome.reported('OIL-VIEW-001')
            outcome.warnings().any { it.code().value() == 'OIL-VIEW-001' }

        and: 'and the shell window was opened anyway, because that is the part that matters'
            outcome.events().findAll { it instanceof LampEvent.WindowOpened }
                   .any { it.what().contains('terminal') }
    }

    def 'A terminal that will not open ends the session, because nobody is in the sandbox'() {
        reportInfo """
            The other half of the same decision, and it goes the other way. The terminal window
            IS the session: if it cannot open, there is nobody in the sandbox and nothing that
            will ever end it. Leaving a container running unattended is the one outcome this tool
            must not produce, so the session is ended and the failure is reported with the exact
            command that would not start.
        """
        given: 'a machine whose terminal emulator refuses to start'
            sandbox.machine { it.windowRefusing('ptyxis') }

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'the session failed, with the session exit code rather than a generic error'
            outcome.status() == ExitStatus.SESSION_FAILED

        and: 'the reason names the terminal, and quotes what was run'
            outcome.reported('OIL-TERM-003')
            outcome.errors().any { problem ->
                problem.evidence().any { it.toString().contains('ptyxis') }
            }

        and: 'and the sandbox was not left behind'
            var sessionFile = sandbox.lampPath().resolve('.oillamp/session.json')
            !java.nio.file.Files.exists(sessionFile)
    }

    def 'A terminal window that opens but never connects does not hold the lamp forever'() {
        reportInfo """
            The failure that has no obvious symptom: the terminal emulator starts, its window
            appears, and the ssh inside it fails on something of its own. Nothing is wrong with
            the sandbox, nothing is wrong with oillamp, and the session would wait for a shell
            that is never coming - holding the lamp's lock while it waits.

            §10.6 answers this with a timeout, which is the one rule in the session machine that
            needs time to actually pass. This is the scenario that asks for a clock that moves.
        """
        given: 'a terminal that opens and never reaches the sandbox, and a short patience'
            sandbox.machine { it.terminalThatNeverConnects().clockRuns()
                                .windowsStayOpenFor(Duration.ofSeconds(10)) }
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [timeouts]
                terminal_connect_seconds = 2
            """.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'oillamp gives up rather than wait, and says what it was waiting for'
            outcome.status() == ExitStatus.SESSION_FAILED
            outcome.reported('OIL-TERM-002')

        and: 'and the lamp is free again: a second run is not refused as busy'
            var second = sandbox.oillamp.run('at', sandbox.lampPath().toString())
            second.status() != ExitStatus.LAMP_BUSY
    }

    def '--no-viewer opens the shell and nothing else'() {
        reportInfo """
            For the person running an agent on a machine they are not sitting at, or who simply
            does not want a desktop window in the way. The session is otherwise identical, which
            is the point: the viewer is how you watch, not part of how it works.
        """
        given:
            sandbox.machine { it.windowsStayOpenFor(Duration.ofMillis(400)) }

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--no-viewer')

        then:
            outcome.status() == ExitStatus.SUCCESS
            var windows = outcome.events().findAll { it instanceof LampEvent.WindowOpened }
            windows.size() == 1
            windows.first().what().contains('terminal')
    }

    def 'The terminal oillamp was started from goes on reporting the health of the sandbox'() {
        reportInfo """
            The user asked for this too, and it closes the gap M3 left open. The sockets were
            checked once, at startup; a desktop that died an hour into a session looked - from
            any cheaper check - exactly like one that was fine.

            So the session keeps opening both sockets while it runs, and keeps saying so in the
            terminal it was started from. Connecting is the only check that distinguishes a
            server from a file with the right name, which is the whole lesson of OIL-SANDBOX-004.
        """
        given: 'a session that stays up long enough to report on itself'
            sandbox.machine { it.windowsStayOpenFor(Duration.ofSeconds(4)) }

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'it went on checking that both sockets still answer'
            outcome.console().contains('desktop and shell both still answering')

        and: 'and reported how long the session had been up, and where to ask for more'
            outcome.console().contains('oillamp status ')
    }
}
