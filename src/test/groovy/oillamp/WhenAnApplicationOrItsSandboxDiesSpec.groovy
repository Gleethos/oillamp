package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

import static oillamp.RealLamps.*

/**
 * The two ways a lamp ends without anyone asking: the application that holds it crashes, or the
 * sandbox itself dies. Both on this machine, with real podman.
 *
 * <p>Neither is rare. A game gets killed by its player, by the desktop or by a bug; a container gets
 * killed by an out-of-memory condition or by someone tidying up with podman. What matters is what is
 * left afterwards, and who is told.
 */
@Tag('spike')
@Requires({ Spike.containerNetworkWorks() })
class WhenAnApplicationOrItsSandboxDiesSpec extends Specification {

    Path directory
    Lamp lamp
    final List<LampEvent> heard = new CopyOnWriteArrayList<>()

    def cleanup() {
        lamp?.close()
        remove(directory)
    }

    def 'When the application is killed, its sandbox shuts itself down cleanly'() {
        reportInfo """
            An application killed with `kill -9` runs no shutdown code at all. It cannot close its
            lamps, and nothing it planned for its last moments happens. What the kernel does
            is close every file the process held, and one of them is the engine's standard input.
            The engine notices that its input has ended and shuts the session down, exactly as if
            it had been asked: the container stops, the session's files go, the lock is released.

            The application here is played by a stand-in process that holds the engine's input,
            and it is that process which is killed. From the engine's side there is no difference:
            the process at the other end of its input is gone, without a word.
        """
        given: 'an engine whose standard input is held by a stand-in for the application'
            directory = newLampPath('killed-application')
            var standIn = directory.parent.resolve('application.pid')
            lamp = Lamp.at(directory).onEvent { heard << it }
                       .launchedBy(engineWithInputHeldBy(standIn)).start()
            lamp.awaitRunning(FIRST_START)
            var container = containerOf(directory)
            containerExists(container)

        when: 'the stand-in is killed without warning'
            Spike.run('kill', '-9', Files.readString(standIn).strip())

        then: 'the engine shuts the session down by itself, and exits cleanly'
            eventually(Duration.ofSeconds(90)) { lamp.exitStatus().isPresent() }
            lamp.exitStatus() == Optional.of(ExitStatus.SUCCESS)

        and: 'it says why: the application that started it let go'
            heard.any { it instanceof LampEvent.Summary
                        && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'nothing is left running, and the lamp is free for the next start'
            !containerExists(container)
            !Files.exists(directory.resolve('.oillamp/session.json'))
            oillamp('status', directory.toString()).reported('OIL-SESSION-001')
    }

    def 'When the sandbox dies, the application is told, and the lamp is cleaned up'() {
        reportInfo """
            The container can die under a running session: killed for using too much memory, or
            removed by hand with podman. The engine checks on it every two seconds, so the
            application learns within moments rather than finding out when its next command
            hangs. The session is reported as failed, not as a normal end, the container is
            removed, and the lamp refuses further commands instead of sending them nowhere.
        """
        given:
            directory = newLampPath('killed-sandbox')
            lamp = Lamp.at(directory).onEvent { heard << it }.start()
            lamp.awaitRunning(FIRST_START)
            var container = containerOf(directory)

        when: 'the container is killed from outside'
            Spike.run('podman', 'kill', container)

        then: 'the engine notices, and ends the session as failed'
            eventually(Duration.ofSeconds(90)) { lamp.exitStatus().isPresent() }
            lamp.exitStatus() == Optional.of(ExitStatus.SESSION_FAILED)

        and: 'the application was told what happened, in words it can show'
            heard.any { it instanceof LampEvent.Summary
                        && it.lines().toList().any { line -> line.contains('the sandbox container exited') } }

        and: 'nothing is left behind'
            !containerExists(container)
            !Files.exists(directory.resolve('.oillamp/session.json'))

        when: 'the application tries to use the lamp anyway'
            lamp.commandLine('true')

        then: 'it is refused plainly'
            thrown(IllegalStateException)
    }

    /**
     * Starts the engine the way {@link Lamp} normally does, from this JVM's classpath, but with its
     * standard input coming from a separate process, whose id is written to {@code pidFile}.
     *
     * <p>In the shell pipeline {@code stand-in | engine}, the stand-in is the only process holding
     * the pipe's writing end. The stand-in writes its own process id, then becomes {@code sleep}
     * (same process), which holds the pipe open and writes nothing, as a live application does.
     */
    private static Lamp.Launcher engineWithInputHeldBy(Path pidFile) {
        return { List<String> arguments ->
            var java = Path.of(System.getProperty('java.home'), 'bin', 'java').toString()
            var command = ['bash', '-c', '{ echo $BASHPID > "$0"; exec sleep infinity; } | exec "$@"',
                           pidFile.toString(),
                           java, '-cp', System.getProperty('java.class.path'), 'dev.oillamp.OilLamp',
                           *arguments]
            var engine = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            assert eventually(Duration.ofSeconds(10)) { Files.exists(pidFile) && Files.size(pidFile) > 0 }
            engine
        } as Lamp.Launcher
    }
}
