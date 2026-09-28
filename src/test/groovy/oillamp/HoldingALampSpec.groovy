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
