package oillamp

import dev.lamp.ExitStatus
import dev.lamp.LampEvent
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  A whole session, run by the supervisor.
 *
 *  <p>These scenarios run a whole session. The container is simulated, but nothing else is: the
 *  relays bind real Unix sockets, the simulated terminal really connects through the primary
 *  relay and then closes, and the simulated user ends the session through its real control
 *  socket, the way `oillamp stop` does. That matters here
 *  more than anywhere else in this project: the supervisor's entire job is what happens between
 *  processes, and a test that stubbed the sockets out would be testing the stub.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SupervisingASessionSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
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
            var outcome = host.oillamp.run('at', host.lampPath().toString())

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

        and: 'and it reaches the sandbox through the socket kept for that one window'
            terminal.any { it.contains('UNIX-CONNECT:') && it.contains('ssh-primary.sock') }
            terminal.any { it.contains('cd ~/workspace') }

        and: 'the viewer is pointed at the VNC socket, with no TCP port anywhere'
            var viewer = windows.find { it.what().contains('viewer') }.argv().toList()
            viewer.first() == 'vncviewer'
            viewer.last().endsWith('/sockets/infra/vnc.sock')
    }

    def 'Closing the shell window leaves the session running; it ends when the user says so'() {
        reportInfo """
            The shell window is one way into the session, not the session itself. A user who closes
            it may want a fresh shell, or none for a while, and should not lose the sandbox for it.
            The session ends on Ctrl-C in the terminal oillamp was started from, on closing that
            terminal, or on `oillamp stop`. When it does, everything the session created has to go
            (the container, the sockets, the session file), or the next `oillamp at` on this lamp
            meets a name clash and a lock it cannot explain.

            The simulated shell window connects through the real relay and closes; the simulated
            user then runs `oillamp stop`. Nothing about the shutdown is simulated.
        """
        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'oillamp exits 0, because this is how a session is supposed to end'
            outcome.status() == ExitStatus.SUCCESS

        and: 'the window closing was reported, with how to open another shell, and ended nothing'
            var events = outcome.events().toList()
            var closed = events.findIndexOf { it instanceof LampEvent.Info
                                              && it.text().contains('the shell window closed') }
            closed >= 0
            events[closed].text().contains('the session keeps running')
            events[closed].text().contains('oillamp shell ')

        and: 'the session ended only when it was asked to, and says so in the closing summary'
            var summaryAt = events.findIndexOf { it instanceof LampEvent.Summary
                                                 && it.title().startsWith('session ') }
            summaryAt > closed
            events[summaryAt].lines().toList().any { it.contains('asked to stop by') }

        and: 'the container was asked to stop, with time to finalise the recording, and is gone'
            outcome.console().contains('stopping the sandbox — up to 15s')
            outcome.console().contains('(removed)')
            var sessionFile = host.lampPath().resolve('.oillamp/session.json')
            !java.nio.file.Files.exists(sessionFile)
    }

    def 'The user is told what is running, where it is, and every way to end it'() {
        reportInfo """
            Two windows have just appeared and a container is running that the user cannot see
            into. This is the answer to "what is going on?", including the two things that are
            easy to miss: that the agent can see exactly one directory of the lamp, and that
            there are three different ways to end the session.
        """
        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

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
            The viewer's lifetime is deliberately independent of the session's, and this is why. A
            session with no view of the desktop is degraded, not broken: the shell still works,
            the agent is still running, the recording is still being made. Ending it would throw
            away working state over a window the user can reopen with `oillamp view`.
        """
        given: 'a machine whose viewer refuses to start'
            host.machine { it.windowRefusing('vncviewer') }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session still ran, and still ended cleanly'
            outcome.status() == ExitStatus.SUCCESS

        and: 'but the user was told, with the command that failed'
            outcome.reported('OIL-VIEW-001')
            outcome.warnings().any { it.code().value() == 'OIL-VIEW-001' }

        and: 'and the shell window was opened anyway, because that is the part that matters'
            outcome.events().findAll { it instanceof LampEvent.WindowOpened }
                   .any { it.what().contains('terminal') }
    }

    def 'A terminal that will not open ends the session, because it did not start as asked'() {
        reportInfo """
            The other half of the same decision, and it goes the other way. A terminal that cannot
            open almost always means the terminal setting is wrong, and it would be wrong in every
            session. So the session is ended and the failure is reported with the exact command
            that would not start, and the user fixes the setting now, rather than working around
            a missing window every time.
        """
        given: 'a machine whose terminal emulator refuses to start'
            host.machine { it.windowRefusing('ptyxis') }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session failed, with the session exit code rather than a generic error'
            outcome.status() == ExitStatus.SESSION_FAILED

        and: 'the reason names the terminal, and quotes what was run'
            outcome.reported('OIL-TERM-003')
            outcome.errors().any { problem ->
                problem.evidence().any { it.toString().contains('ptyxis') }
            }

        and: 'and the sandbox was not left behind'
            var sessionFile = host.lampPath().resolve('.oillamp/session.json')
            !java.nio.file.Files.exists(sessionFile)
    }

    def 'A terminal window that opens but never connects does not hold the lamp forever'() {
        reportInfo """
            The failure that has no obvious symptom: the terminal emulator starts, its window
            appears, and the ssh inside it fails on something of its own. Nothing is wrong with
            the sandbox, nothing is wrong with oillamp, and the session would wait for a shell
            that is never coming - holding the lamp's lock while it waits.

            The answer is a timeout, which is the one rule in the session machine that
            needs time to actually pass. This is the scenario that asks for a clock that moves.
        """
        given: 'a terminal that opens and never reaches the sandbox, and a short patience'
            host.machine { it.terminalThatNeverConnects().clockRuns()
                                .windowsStayOpenFor(Duration.ofSeconds(10)) }
            host.givenConfig(host.lampPath(), """
                schema_version = 1
                [timeouts]
                terminal_connect_seconds = 2
            """.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'oillamp gives up rather than wait, and says what it was waiting for'
            outcome.status() == ExitStatus.SESSION_FAILED
            outcome.reported('OIL-TERM-002')

        and: 'and the lamp is free again: a second run is not refused as busy'
            var second = host.oillamp.run('at', host.lampPath().toString())
            second.status() != ExitStatus.LAMP_BUSY
    }

    def '--no-viewer opens the shell and nothing else'() {
        reportInfo """
            For the person running an agent on a machine they are not sitting at, or who simply
            does not want a desktop window in the way. The session is otherwise identical, which
            is the point: the viewer is how you watch, not part of how it works.
        """
        given:
            host.machine { it.windowsStayOpenFor(Duration.ofMillis(400)) }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--no-viewer')

        then:
            outcome.status() == ExitStatus.SUCCESS
            var windows = outcome.events().findAll { it instanceof LampEvent.WindowOpened }
            windows.size() == 1
            windows.first().what().contains('terminal')
    }

    def 'The terminal oillamp was started from goes on reporting the health of the sandbox'() {
        reportInfo """
            The user asked for this too. Checking the sockets only at startup is not enough: a
            desktop that died an hour into a session would look fine to any cheaper check.

            So the session keeps opening all three sockets while it runs (the desktop, the shell
            and the egress proxy) and keeps saying so in the terminal it was started
            from. Connecting is the only check that distinguishes a server from a file with the
            right name, which is the whole lesson of OIL-SANDBOX-004.
        """
        given: 'a session that stays up long enough to report on itself'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(4)) }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'it went on checking that all three sockets still answer'
            outcome.console().contains('desktop, shell and network all still answering')

        and: 'and reported how long the session had been up, and where to ask for more'
            outcome.console().contains('oillamp status ')
    }

    def 'A sandbox that dies during a session ends it, says so, and leaves nothing behind'() {
        reportInfo """
            The container's first process stops the sandbox when a part the human depends on
            dies: the compositor, the desktop server, the recorder or a network bridge. The user
            would otherwise go on typing into a shell attached to a desktop nobody can see. The
            supervisor notices within seconds, ends the session with its own exit code, names the
            sandbox's exit code, and still cleans up everything the session made.
        """
        given: 'a sandbox that exits a second after it started, while the shell is still open'
            host.machine {
                it.windowsStayOpenFor(Duration.ofSeconds(60))
                  .sandboxDiesAfter(Duration.ofSeconds(1))
            }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session ends as failed, not as a normal stop'
            outcome.status() == ExitStatus.SESSION_FAILED

        and: 'the summary says why, with the sandbox\'s own exit code'
            outcome.console().contains('the sandbox container exited (code 70)')

        and: 'and the container and the session\'s files were still cleaned up'
            outcome.console().contains('(removed)')
            !java.nio.file.Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'A bug that breaks the session\'s event loop still ends the session and removes the sandbox'() {
        reportInfo """
            One thread of oillamp's, the event loop, decides when a session ends. If a bug made it
            fail, nothing would stop the sandbox, and oillamp would then wait many minutes on its
            way out for a loop that was gone. So a failing event loop is reported as a bug, and
            the session is shut down at once, as it is for any other ending.

            Here the bug is in an application listening to the session, which fails the moment
            the session is up: the listener is called on the event loop's thread.
        """
        given: 'a listener that fails once, when the session is up'
            var failed = new java.util.concurrent.atomic.AtomicBoolean()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.SessionStateChanged && event.status().state() == 'running'
                        && failed.compareAndSet(false, true))
                    throw new IllegalStateException('the listener broke')
            }

        when:
            var outcome = oillamp.run('at', host.lampPath().toString())

        then: 'the bug is reported as one'
            outcome.status() == ExitStatus.ERROR
            outcome.reported('OIL-INTERNAL-001')
            outcome.console().contains('the listener broke')

        and: 'the session ended the way every session does, and says why'
            outcome.console().contains('oillamp itself failed')
            outcome.console().contains('(removed)')
            outcome.events().any { it instanceof LampEvent.SessionStateChanged && it.status().state() == 'stopped' }
            !java.nio.file.Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'A second connection to the shell window\'s socket is refused, and the session goes on'() {
        reportInfo """
            The socket the terminal window connects through accepts exactly one connection per
            session, because that connection is what tells oillamp the user's shell is up. The
            socket is outside anything the sandbox can see, so a second connection is a mistake
            on the host, such as a copied ssh command. It is refused with a warning that points to
            `oillamp shell`, and the session carries on.
        """
        given: 'a session whose shell window stays open'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
            var reported = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
            var oillamp = host.oillamp.observedBy { reported.add(it) }
            var session = Thread.start { oillamp.run('at', host.lampPath().toString()) }
            waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }

        when: 'something else connects to the shell window\'s socket'
            var primary = java.nio.file.Files.list(host.runtime.resolve('oillamp')).toList().first()
                                                 .resolve('run/ssh-primary.sock')
            java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(primary)).close()

        then: 'it is refused with a warning naming the command to use instead'
            waitUntil { reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SSH-002' } }
            reported.find { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SSH-002' }
                    .problem().toString().contains('oillamp shell')

        and: 'the session is still running'
            session.alive

        cleanup:
            host.oillamp.run('stop', host.lampPath().toString())
            session?.join(20_000)
    }

    private static void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("the session never got there: ${condition}" as Object)
    }

    def 'A second Ctrl-C during shutdown does not turn a clean session into a bug report'() {
        reportInfo """
            Found on real hardware. Shutting down takes a moment - the container has to stop and,
            if recording is on, the recorder has to finalise the file - and the natural thing to
            do when a terminal seems to hang is to press Ctrl-C again.

            The catch is that `podman stop` is a child of oillamp and so shares the launching
            terminal's process group, so that second Ctrl-C went straight to it. It died with no
            output at all, and oillamp reported OIL-INTERNAL-001: "this is a bug in oillamp,
            please report" - on a session that had in fact cleaned up perfectly, because
            `podman rm -f` then removed the container anyway.

            Two things had to change. The cleanup commands now run shielded from the terminal's
            signals, so the second Ctrl-C cannot reach them. And what gets reported is the end
            state - is the container gone? - rather than each command's opinion of itself.
        """
        given: 'a `podman stop` that dies the way a signalled process does: non-zero, and silent'
            host.machine {
                it.commandFailing('podman stop', 130, '')
                  .windowsStayOpenFor(Duration.ofMillis(400))
            }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session still ends successfully, because the container did go away'
            outcome.status() == ExitStatus.SUCCESS

        and: 'nothing is reported as an internal error'
            !outcome.console().contains('OIL-INTERNAL-001')
            outcome.events().findAll { it instanceof LampEvent.Failure }.isEmpty()

        and: 'it says plainly what happened instead, without a colon and then nothing'
            outcome.console().contains('the container had to be forced')
            !outcome.console().contains('did not stop cleanly: \n')
    }

    def 'A container that truly cannot be removed is reported, and not as an internal error'() {
        reportInfo """
            The other half. If neither stopping nor removing works the container really is still
            there, and that is worth telling the user about: it holds its memory and CPU
            reservations, and the next session on this lamp will find the name taken.

            But it is still not a bug to report - it is podman or the machine - so it gets its own
            code and the command that fixes it, rather than "please file a bug".
        """
        given:
            host.machine {
                it.commandFailing('podman stop', 125, 'Error: no such container')
                  .commandFailing('podman rm', 125, 'Error: container is in use')
                  .windowsStayOpenFor(Duration.ofMillis(400))
            }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the user is told, with the code for a container left behind'
            outcome.console().contains('OIL-SANDBOX-005')
            !outcome.console().contains('OIL-INTERNAL-001')

        and: 'and given the one command that clears it'
            outcome.console().contains('podman rm -f ')
    }

    def 'A podman that is too busy to answer does not end a working session'() {
        reportInfo """
            Every two seconds the session asks podman whether the sandbox is still running. podman
            can be slow or fail to answer, for example while another lamp's image is being built
            and podman's database is locked. The session used to treat any failed answer as "the
            sandbox has died" and shut down, taking the user's shell with it.

            Only a clear answer ends the session now: podman saying the container stopped, or that
            it no longer exists. When podman does not answer, the user is warned once, and the
            session carries on.
        """
        given: 'a podman that fails every question about the container while the session runs'
            host.machine {
                it.commandFailing('podman container inspect', 125,
                                  'Error: timed out waiting for the database lock')
                  .windowsStayOpenFor(Duration.ofSeconds(6))
            }

        when: 'the session runs until the user closes the terminal'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'it ended because the terminal closed, not because the sandbox was thought dead'
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().contains('ended because')
            !outcome.console().contains('the sandbox stopped')

        and: 'the user was told once that podman was not answering, with its own words'
            outcome.problems().count { it.code().value() == 'OIL-SANDBOX-006' } == 1
            outcome.console().contains('timed out waiting for the database lock')
    }
}
