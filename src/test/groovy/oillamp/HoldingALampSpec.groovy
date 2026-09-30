package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 *  An application, such as a game that hosts an agent, holding a lamp through {@link Lamp}.
 *
 *  <p>The lamp starts oillamp's engine as a separate process, reads its events, and ends the
 *  session by closing the engine's standard input. Here the engine runs in this JVM against the
 *  simulated machine ({@link EngineInThisProcess}), connected by real pipes, so the lamp is
 *  tested exactly as an application uses it.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class HoldingALampSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox
    final List<LampEvent> received = new CopyOnWriteArrayList<>()

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'An application starts a lamp, sees it running, and ends it by closing it'() {
        reportInfo """
            This is the whole life of a lamp in an application: start it, hear what it does, know
            when it is usable, and end it. Closing the lamp is all it takes to end the session,
            and it returns only once the sandbox is gone, so the application can exit knowing
            nothing is left running.
        """
        given:
            var lamp = Lamp.at(sandbox.lampPath()).onEvent { received << it }
                           .launchedBy(sandbox.launcher).start()

        expect: 'the session comes up'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.close()

        then: 'the engine ended cleanly, because it was asked to'
            lamp.exitStatus() == Optional.of(ExitStatus.SUCCESS)
            received.any { it instanceof LampEvent.Summary
                           && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'nothing of the session is left'
            !Files.exists(sandbox.lampPath().resolve('.oillamp/session.json'))
    }

    def 'An application can leave its lamp running when it closes, until oillamp stop ends it'() {
        reportInfo """
            An agent with a schedule should keep working after the application that started it
            is closed. The application says so with leaveRunning(), which tells the engine on its
            standard input before closing it. The end of the input, which otherwise ends the
            session, then ends nothing: the sandbox runs on its own, and `oillamp status` says so.
            It ends as any session does, with `oillamp stop`.

            Only an application that says so leaves its lamp running. One that closes, or
            crashes, without a word still takes its sandbox with it.
        """
        given:
            var lamp = Lamp.at(sandbox.lampPath()).onEvent { received << it }
                           .launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application leaves it running, and closes it'
            lamp.leaveRunning()
            lamp.close()
            Thread.sleep(1500)

        then: 'the session goes on, on its own'
            lamp.exitStatus().isEmpty()
            Files.exists(sandbox.lampPath().resolve('.oillamp/session.json'))
            sandbox.oillamp.run('status', sandbox.lampPath().toString()).console()
                   .contains('running on its own')

        when: 'someone stops it'
            var stopped = sandbox.oillamp.run('stop', sandbox.lampPath().toString())
            sandbox.engines.first().waitFor()

        then: 'it ends cleanly, and leaves nothing behind'
            stopped.succeeded()
            lamp.exitStatus() == Optional.of(ExitStatus.SUCCESS)
            !Files.exists(sandbox.lampPath().resolve('.oillamp/session.json'))
    }

    def 'An application that left its lamp running joins it again, and can stop it'() {
        reportInfo """
            Closed and opened again, an application finds the session it left running with
            isRunning(), and joins it. The lamp it gets is used as one it started: it hears what
            the session reports, runs commands in the sandbox, and asks the agent. Closing it
            only stops following. stop() ends the session, and returns once it has.
        """
        given: 'a lamp left running by an application that has closed'
            var first = Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start()
            first.awaitRunning(Duration.ofSeconds(30))
            first.leaveRunning()
            first.close()

        when: 'the application opens again, and joins it'
            var starting = Lamp.at(sandbox.lampPath()).onEvent { received << it }.launchedBy(sandbox.launcher)
            var running = starting.isRunning()
            var joined = starting.join()

        then: 'it is running, and usable as before'
            running
            joined.awaitRunning(Duration.ofSeconds(10))
            !joined.holds()
            joined.commandLine('true').first() == 'ssh'
            sandbox.engines.last().arguments == ['follow', sandbox.lampPath().toString(), '--embedded']

        when: 'it stops the session'
            joined.stop()

        then: 'the session has ended, and nothing of it is left'
            joined.exitStatus() == Optional.of(ExitStatus.SUCCESS)
            !starting.isRunning()
            received.any { it instanceof LampEvent.Summary }
    }

    def 'Joining a lamp that is not running says so'() {
        reportInfo """
            An application may try to join every lamp it knows. One that is not running is not
            an error to crash on: the lamp never comes up, and the events say why.
        """
        given: 'a lamp whose session has ended'
            assert sandbox.oillamp.run('at', sandbox.lampPath().toString()).succeeded()

        when:
            var starting = Lamp.at(sandbox.lampPath()).onEvent { received << it }.launchedBy(sandbox.launcher)
            var joined = starting.join()

        then:
            !starting.isRunning()
            !joined.awaitRunning(Duration.ofSeconds(10))
            received.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-SESSION-001' }
    }

    def 'A command for the sandbox goes over ssh, with each argument arriving exactly as given'() {
        reportInfo """
            ssh joins its arguments with spaces and hands them to a shell in the sandbox, which
            would split "hello world" in two and expand a dollar sign. The lamp quotes every
            argument, so the application passes what it means and the sandbox receives that.
        """
        given:
            var lamp = Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            var line = lamp.commandLine('echo', 'hello world', 'it\'s $HOME')

        then: 'the engine\'s ssh command, without a terminal'
            line.first() == 'ssh'
            line.contains('-T')

        and: 'then each argument, quoted for the sandbox\'s shell'
            line.takeRight(3) == ["'echo'", "'hello world'", "'it'\\''s \$HOME'"]

        cleanup:
            lamp?.close()
    }

    def 'An application can show the sandbox\'s desktop, through the socket the lamp names'() {
        reportInfo """
            An application that wants the player to watch the agent work draws the sandbox's
            desktop in a window of its own. The desktop is served as VNC on a Unix socket that
            only this user can open, so no password is needed and nothing listens on the network.
            The lamp names the socket once the session is running; the engine is the one that
            knows where it is.
        """
        given:
            var lamp = Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        expect: 'the desktop socket of this session'
            lamp.desktop().fileName.toString() == 'vnc.sock'
            lamp.desktop().startsWith(sandbox.runtime)

        cleanup:
            lamp?.close()
    }

    def 'A lamp that is not running has no desktop to show'() {
        reportInfo """
            Asking for the desktop of a lamp that never started, or has ended, is a mistake in
            the application, and it says so at once instead of handing out a socket path that
            nothing listens on.
        """
        given:
            var lamp = Lamp.at(sandbox.home).launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.desktop()

        then:
            thrown(IllegalStateException)

        cleanup:
            lamp?.close()
    }

    def 'An application deletes a lamp it no longer needs through the engine'() {
        reportInfo """
            An application that lets its user make lamps, one per conversation say, must also
            let them delete one. Part of a lamp belongs to the sandbox's own users, so the
            application cannot delete the files itself; the engine can, and the lamp asks it
            to, reporting what it did as events, like everything else.
        """
        given: 'a lamp that ran once, and is closed'
            Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start().withCloseable {
                assert it.awaitRunning(Duration.ofSeconds(30))
            }

        when:
            var status = Lamp.at(sandbox.lampPath()).onEvent { received << it }.launchedBy(sandbox.launcher).remove()

        then: 'it is gone'
            status == ExitStatus.SUCCESS
            !Files.exists(sandbox.lampPath().resolve('.oillamp'))
            !Files.exists(sandbox.lampPath().resolve('oillamp.toml'))

        and: 'the engine was asked as the command line asks it, confirmed and answering in JSON'
            sandbox.engines.last().arguments == ['remove', sandbox.lampPath().toString(), '--yes', '--embedded']
            !received.isEmpty()
    }

    def 'An application finds the agent\'s home in a lamp, whether the lamp runs or not'() {
        reportInfo """
            What the agent keeps, such as its harness's saved conversations, is in its home,
            which is a directory in the lamp named after the agent's id. An application that
            shows those conversations, even while the sandbox is off, asks the lamp where that
            home is instead of knowing how oillamp names it. Before the lamp first ran, there is
            no home yet.
        """
        expect: 'no home before the first start'
            Lamp.agentHome(sandbox.lampPath()).isEmpty()

        when: 'the lamp ran once, and is closed'
            Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start().withCloseable {
                assert it.awaitRunning(Duration.ofSeconds(30))
            }

        then: 'its home is the agent directory, the one holding AGENTS.md'
            var home = Lamp.agentHome(sandbox.lampPath())
            home.isPresent()
            home.get().fileName.toString().startsWith('agent-lamp-')
            Files.exists(home.get().resolve('AGENTS.md'))
    }

    def 'Deleting a directory that is not a lamp says why, and deletes nothing'() {
        reportInfo """
            An application with a wrong path must not delete the wrong thing. The engine refuses
            a directory that is not a lamp, and says so in an event the application can show.
        """
        given:
            Files.createDirectories(sandbox.lampPath())
            Files.writeString(sandbox.lampPath().resolve('notes.txt'), 'mine')

        when:
            var status = Lamp.at(sandbox.lampPath()).onEvent { received << it }.launchedBy(sandbox.launcher).remove()

        then:
            status != ExitStatus.SUCCESS
            Files.exists(sandbox.lampPath().resolve('notes.txt'))
            received.any { it instanceof LampEvent.Failure && it.problem().whatHappened().contains('not an oillamp lamp') }
    }

    def 'A lamp that is not running refuses commands, rather than sending them nowhere'() {
        reportInfo """
            Here the lamp never started, because oillamp refused its directory. An application
            that asked it to run a command anyway would otherwise get an ssh process that fails
            with an error about sockets, which says nothing about the real cause. Refusing at
            once, with a message saying the lamp is not running, points at what actually went
            wrong, and the application can show the problem the events already carried.
        """
        given:
            var lamp = Lamp.at(sandbox.home).launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.commandLine('true')

        then:
            thrown(IllegalStateException)

        cleanup:
            lamp?.close()
    }

    def 'The engine is started with the lamp directory, in embedded mode'() {
        reportInfo """
            The lamp runs the same engine a person runs on a terminal, as `oillamp at <dir>`.
            `--embedded` is what makes it suitable for an application: no windows on the player's
            desktop, events as JSON the application can read, and a session that ends when the
            application lets go. Without that one option, the application would get a terminal
            window it never asked for, and output it could not read.
        """
        given:
            var lamp = Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start()

        expect:
            sandbox.engines.first().arguments == ['at', sandbox.lampPath().toString(), '--embedded']

        cleanup:
            lamp?.close()
    }

    def 'A lamp that cannot start says why, and does not claim to be running'() {
        reportInfo """
            The application needs to tell the player what went wrong, in the same words oillamp
            uses on a terminal: the problem, with its evidence and fixes, arrives as an event.
            Here the lamp directory is the home directory itself, which oillamp refuses.
        """
        given:
            var lamp = Lamp.at(sandbox.home).onEvent { received << it }
                           .launchedBy(sandbox.launcher).start()

        expect:
            !lamp.awaitRunning(Duration.ofSeconds(30))
            lamp.exitStatus().isPresent()
            lamp.exitStatus().get() != ExitStatus.SUCCESS
            received.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-LAMP-003' }

        cleanup:
            lamp?.close()
    }
}
