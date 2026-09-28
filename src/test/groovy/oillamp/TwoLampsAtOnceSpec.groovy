package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

import static oillamp.RealLamps.*

/**
 * One application holding several lamps, such as a game with two campaigns open, or two agents
 * that must not see each other's notes. On this machine, with real podman.
 */
@Tag('spike')
@Requires({ Spike.containerNetworkWorks() })
class TwoLampsAtOnceSpec extends Specification {

    Path north = newLampPath('north')
    Path south = newLampPath('south')
    Lamp first
    Lamp second

    def cleanup() {
        first?.close()
        second?.close()
        remove(north)
        remove(south)
    }

    def 'Two lamps run side by side, each agent in its own sandbox, unaware of the other'() {
        reportInfo """
            Each lamp is its own sandbox: its own container, its own agent id, its own home, its
            own proxy and relays. Two of them running at once must not collide on a name or a
            socket, and neither agent may see the other's files, because an agent's home is the
            only part of the host its sandbox contains. Closing one leaves the other running.
        """
        when: 'both lamps are started at the same time'
            first = Lamp.at(north).start()
            second = Lamp.at(south).start()

        then: 'both come up'
            first.awaitRunning(FIRST_START)
            second.awaitRunning(FIRST_START)

        and: 'as two different agents in two different containers'
            agentId(north) != agentId(south)
            containerExists(containerOf(north))
            containerExists(containerOf(south))

        when: 'each agent writes a secret into its own home'
            feeding(first, 'north secret', 'sh', '-c', 'cat > ~/secret.txt')
            feeding(second, 'south secret', 'sh', '-c', 'cat > ~/secret.txt')

        then: 'each reads back only its own'
            inSandbox(first, 'cat', 'secret.txt').out == 'north secret'
            inSandbox(second, 'cat', 'secret.txt').out == 'south secret'

        and: 'neither can find the other\'s home anywhere'
            inSandbox(first, 'sh', '-c', "find / -path /proc -prune -o -name 'agent-lamp-*' -print 2>/dev/null")
                    .out.strip() == ''
            !inSandbox(first, 'grep', '-rqs', 'south secret', '/home', '/tmp', '/oillamp').ok

        when: 'the first lamp is closed'
            first.close()

        then: 'it ended cleanly, and its container is gone'
            first.exitStatus() == Optional.of(ExitStatus.SUCCESS)
            !containerExists(containerOf(north))

        and: 'the second carries on, untouched'
            containerExists(containerOf(south))
            inSandbox(second, 'cat', 'secret.txt').out == 'south secret'
    }

    def 'The same lamp cannot be opened twice, and the refusal leaves the running one alone'() {
        reportInfo """
            One lamp is one sandbox. A second start on a directory that is already running, for
            example from a second window of the same game, would fight the first over its
            container and its sockets. oillamp refuses it with a problem that says the lamp is
            busy, and the session already running is not disturbed.
        """
        given: 'a running lamp'
            first = Lamp.at(north).start()
            first.awaitRunning(FIRST_START)
            var refusals = new CopyOnWriteArrayList<LampEvent>()

        when: 'the same directory is opened a second time'
            second = Lamp.at(north).onEvent { refusals << it }.start()

        then: 'the second never runs, and says the lamp is busy'
            !second.awaitRunning(LATER_START)
            second.exitStatus() == Optional.of(ExitStatus.LAMP_BUSY)
            refusals.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-LOCK-001' }

        and: 'the first is still running and still answering'
            containerExists(containerOf(north))
            inSandbox(first, 'echo', 'still here').out.strip() == 'still here'
    }
}
