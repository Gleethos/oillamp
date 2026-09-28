package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import static oillamp.RealLamps.*

/**
 * The whole life of a lamp held by an application, such as a game hosting an agent, on this
 * machine with real podman.
 *
 * <p>Nothing is simulated. {@link Lamp} starts oillamp's engine as a separate Java process from this
 * JVM's own classpath, exactly as an application would; the engine checks the host, builds the
 * sandbox image if needed, starts the container, and runs the egress proxy and the SSH relay.
 * Every command below travels from this JVM over real ssh, through the relay, into the container.
 *
 * <p>The scenarios follow one lamp from creation to deletion, in order: each one relies on what the
 * one before it left behind, as an application would.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class RunningALampForAnApplicationSpec extends Specification {

    @Shared Path directory = newLampPath('application')
    @Shared Lamp lamp
    @Shared List<LampEvent> heard = new CopyOnWriteArrayList<>()
    @Shared String firstAgentId

    def cleanupSpec() {
        lamp?.close()
        remove(directory)
    }

    def 'An application starts a lamp from nothing, and hears it come up step by step'() {
        reportInfo """
            The application names a directory that does not exist yet and asks for a lamp there.
            oillamp does everything a person's first `oillamp at` does: checks the host, creates
            the lamp, builds the sandbox image if this version of oillamp has none yet (which can
            take many minutes), starts the container and opens the session.

            The application is told about each step as it happens, in the same events a terminal
            shows, so it can show the player progress rather than a frozen screen. It learns the
            session is usable from a single event that also says how to reach the sandbox.
        """
        when:
            lamp = Lamp.at(directory).onEvent { heard << it }.start()

        then: 'the session comes up'
            lamp.awaitRunning(FIRST_START)

        and: 'the application heard each stage succeed, in order: host, lamp, image, session'
            var areas = heard.findAll { it instanceof LampEvent.Ok }*.area().unique()
            areas.containsAll(['host', 'lamp', 'image', 'session'])
            ['host', 'lamp', 'image', 'session'].collect { areas.indexOf(it) } ==
                    ['host', 'lamp', 'image', 'session'].collect { areas.indexOf(it) }.sort()

        and: 'it was told the image is ready, and that the sandbox is running, in plain words'
            heard.any { it instanceof LampEvent.Ok && it.text().startsWith('sandbox image ready') }
            heard.any { it instanceof LampEvent.Ok && it.text().startsWith('sandbox running') }

        and: 'no problem was reported on the way'
            heard.findAll { it instanceof LampEvent.Failure }.isEmpty()

        and: 'the lamp exists on disk, with its identity and the agent\'s home'
            Files.isRegularFile(directory.resolve('oillamp.toml'))
            identity(directory).schemaVersion == 1
            Files.isDirectory(agentHome(directory).resolve('workspace'))
            Files.isRegularFile(directory.resolve('.oillamp/session.json'))

        and: 'podman runs its container, labelled with the lamp it belongs to'
            var container = containerOf(directory)
            containerExists(container)
            Spike.run('podman', 'container', 'inspect', '--format', '{{index .Config.Labels "oillamp.lamp"}}', container)
                 .out.strip() == directory.toRealPath().toString()

        and: 'no window opened on this desktop'
            heard.findAll { it instanceof LampEvent.WindowOpened }.isEmpty()

        cleanup:
            firstAgentId = agentId(directory)
    }

    def 'Commands run in the sandbox as the agent, in its home, with the same environment as its shell'() {
        reportInfo """
            An application runs programs in the sandbox, such as an agent harness, through
            `Lamp.exec`. They must run as the agent would run them by hand: as the unprivileged
            `agent` user, in its home directory, with the proxy, display and tool settings every
            shell in the sandbox gets. A harness started without the proxy setting, for example,
            would find no internet and conclude its model service is down.
        """
        expect: 'the agent user, in its home'
            with(inSandbox(lamp, 'sh', '-c', 'id -un; id -u; pwd')) {
                ok
                out.readLines() == ['agent', '1000', '/home/agent']
            }

        and: 'with the proxy, the desktop and the agent\'s own tool directories'
            with(inSandbox(lamp, 'sh', '-c', 'echo "$HTTPS_PROXY|$WAYLAND_DISPLAY|$DISPLAY"; echo "$PATH"')) {
                ok
                out.readLines()[0] == 'http://127.0.0.1:3128|/run/lamp/wayland-1|:0'
                out.readLines()[1].contains('/home/agent/.local/bin')
            }

        and: 'arguments arrive exactly as the application gave them: spaces, quotes and dollars included'
            with(inSandbox(lamp, 'printf', '%s\\n', 'two words', 'it\'s', '$HOME', '"quoted"', '')) {
                ok
                out.readLines() == ['two words', 'it\'s', '$HOME', '"quoted"', '']
            }

        and: 'a failing command reports its own exit code and error output, separately'
            with(inSandbox(lamp, 'sh', '-c', 'echo to-out; echo to-err >&2; exit 7')) {
                exit == 7
                out.strip() == 'to-out'
                err.strip() == 'to-err'
            }
    }

    def 'The application holds a conversation with a program in the sandbox, one message at a time'() {
        reportInfo """
            This is how an application will talk to an agent harness: it starts the harness with
            `Lamp.exec` and exchanges messages over its standard input and output, each one
            answered before the next is sent, as protocols such as ACP do.

            A pipe that only delivered its data when closed, or a relay that held bytes back until
            a buffer filled, would make that impossible while looking fine for one-shot commands.
            So each message here must be answered while the program is still running and still
            waiting for more. The program is a few lines of Python that answer JSON with JSON.
        """
        given: 'a program that answers each JSON message with one of its own'
            var program = '''
import json, sys
for line in sys.stdin:
    message = json.loads(line)
    print(json.dumps({"id": message["id"], "heard": message["say"].upper()}), flush=True)
'''
            Process conversation = lamp.exec('python3', '-u', '-c', program)
            var toProgram = new PrintWriter(new OutputStreamWriter(conversation.outputStream, 'UTF-8'), true)
            var fromProgram = new BufferedReader(new InputStreamReader(conversation.inputStream, 'UTF-8'))

        when: 'three messages are sent, each only after the answer to the one before'
            var answers = []
            ['a dragon', 'wakes up', 'hungry'].eachWithIndex { String text, int id ->
                toProgram.println("""{"id": $id, "say": "$text"}""")
                answers << fromProgram.readLine()
            }

        then: 'each answer came back in turn, while the program was still running'
            answers == ['{"id": 0, "heard": "A DRAGON"}',
                        '{"id": 1, "heard": "WAKES UP"}',
                        '{"id": 2, "heard": "HUNGRY"}']
            conversation.alive

        when: 'the application ends the conversation by closing the program\'s input'
            toProgram.close()

        then: 'the program finishes on its own'
            conversation.waitFor(30, TimeUnit.SECONDS)
            conversation.exitValue() == 0
    }

    def 'Several megabytes of arbitrary bytes cross into the sandbox and back unchanged'() {
        reportInfo """
            Files, images and model output will travel through the same pipes. Every byte passes
            through ssh, the supervisor's relay and a socket in the container, and any of them
            could mangle binary data or cut a large transfer short. Eight megabytes of random bytes
            go in, the sandbox hashes them, sends them back, and both sides must agree.
        """
        given:
            var bytes = new byte[8 * 1024 * 1024]
            new Random(42).nextBytes(bytes)
            var expected = MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()

        when: 'the bytes are sent to `tee`, which hashes them and echoes them back'
            Process echo = lamp.exec('sh', '-c', 'tee /tmp/received | cat; sha256sum /tmp/received >&2')
            var sender = Thread.start { echo.outputStream.withCloseable { it.write(bytes) } }
            var returned = echo.inputStream.bytes
            var hashedInside = echo.errorStream.text
            sender.join()
            echo.waitFor(1, TimeUnit.MINUTES)

        then: 'the sandbox received exactly what was sent'
            hashedInside.startsWith(expected)

        and: 'and what came back is identical too'
            returned.length == bytes.length
            MessageDigest.getInstance('SHA-256').digest(returned).encodeHex().toString() == expected
    }

    def 'What the agent writes in its home appears in the lamp on the host, owned by the user'() {
        reportInfo """
            The agent's home is the one place it shares with the host, on purpose: the application
            and the user can read what the agent produced, a campaign's notes or a generated map,
            without copying anything out of a container. Files are owned by the user who runs
            oillamp, so they can be edited, moved and committed without changing ownership.
        """
        when:
            var wrote = feeding(lamp, 'The old chapel is haunted.\n',
                                'sh', '-c', 'cat > ~/workspace/notes.md')

        then:
            wrote.ok
            var onHost = agentHome(directory).resolve('workspace/notes.md')
            Files.readString(onHost) == 'The old chapel is haunted.\n'
            Files.getOwner(onHost).name == System.getProperty('user.name')
    }

    def 'The sandbox reaches the internet through the proxy, and the private network is refused'() {
        reportInfo """
            The agent may use the internet: to install packages, read documentation, or reach its
            model service. It may not reach the host's own network: the router, a database on the
            local network, a service on this machine. Every connection goes through oillamp's
            proxy, which decides by where a name really points.

            A refusal is not a silent timeout. The agent gets an answer naming the rule, so it
            stops retrying, and the application is told, so it can show what was blocked. The
            decision is also written to the session's network log.
        """
        when: 'the agent fetches a public web page'
            var publicPage = inSandbox(lamp, 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}',
                                       '--max-time', '30', 'https://example.com/')

        then: 'it arrives'
            publicPage.out == '200'

        when: 'it tries an address on the local network'
            var sessionId = heard.find { it instanceof LampEvent.SessionOpened }.session()
            var privateAddress = inSandbox(lamp, 'curl', '-s', '-w', '\n%{http_code}', '--max-time', '30',
                                           'http://192.168.1.1/')

        then: 'the proxy refuses it, and says which rule did'
            privateAddress.out.readLines().last() == '403'
            privateAddress.out.contains('denied by rule "block private, internal and loopback ranges"')

        and: 'the application was told about the refusal'
            eventually(Duration.ofSeconds(10)) {
                heard.any { it instanceof LampEvent.Info && it.area() == 'network' && it.text().contains('192.168.1.1') }
            }

        and: 'and the network log recorded it, next to the page that was allowed'
            var log = directory.resolve(".oillamp/logs/network-${sessionId}.jsonl")
            eventually(Duration.ofSeconds(10)) { Files.exists(log) && Files.readString(log).contains('192.168.1.1') }
            Files.readAllLines(log).any { it.contains('"host":"192.168.1.1"') && it.contains('"decision":"deny"') }
            Files.readAllLines(log).any { it.contains('"host":"example.com"') && it.contains('"decision":"allow"') }
    }

    def 'There is no way around the proxy: the sandbox has no network of its own'() {
        reportInfo """
            A proxy only protects the host if nothing can go around it. The sandbox has no network
            interface except loopback, and no name service: a program that ignores the proxy
            settings cannot even look up a name, let alone connect.
        """
        expect: 'no network interface besides loopback'
            inSandbox(lamp, 'sh', '-c', 'ls /sys/class/net').out.readLines() == ['lo']

        and: 'no name can be looked up directly'
            !inSandbox(lamp, 'getent', 'hosts', 'example.com').ok

        and: 'a request told not to use the proxy fails'
            !inSandbox(lamp, 'curl', '-s', '--noproxy', '*', '--max-time', '10', 'https://example.com/').ok
    }

    def 'The agent cannot see the host, nor the rules that restrict it'() {
        reportInfo """
            The agent runs as the user's own uid inside the container, so what keeps it away from
            the user's files is what the container can see, not file permissions. It sees its
            home and nothing else of the host: not the user's home directory, and not the lamp's
            configuration, which holds the network rules. An agent that could edit those rules
            could lift its own restrictions.
        """
        expect: 'the user\'s real home directory is not there'
            !inSandbox(lamp, 'test', '-e', System.getProperty('user.home')).ok

        and: 'the lamp directory on the host is not there either'
            !inSandbox(lamp, 'test', '-e', directory.toString()).ok

        and: 'the configuration file is nowhere in the sandbox'
            inSandbox(lamp, 'sh', '-c', 'find / -path /proc -prune -o -name oillamp.toml -print 2>/dev/null')
                    .out.strip() == ''

        and: 'the system itself cannot be changed'
            !inSandbox(lamp, 'touch', '/usr/local/bin/planted').ok
    }

    def 'oillamp\'s own commands see the lamp the application started'() {
        reportInfo """
            When something goes wrong in a game, the person investigating opens a terminal. They
            must be able to ask the same questions of a lamp an application started as of one they
            started themselves: what is it doing, and let me look inside.
        """
        when:
            var status = oillamp('status', directory.toString())

        then:
            status.status() == ExitStatus.SUCCESS
            var answer = status.events().find { it instanceof LampEvent.Answer }.text()
            answer.contains('running, for the application that started it')
            answer.contains(containerOf(directory))

        and: 'the lamp is listed with the other sandboxes on this machine'
            oillamp('list').events().find { it instanceof LampEvent.Answer }.text()
                           .contains(containerOf(directory))
    }

    def 'Closing the lamp ends the session, even with a command still running, and leaves nothing behind'() {
        reportInfo """
            An application that exits must be able to trust that the sandbox exits with it, even if
            the agent is in the middle of something. Closing the lamp stops the container, which
            ends every process in it, and removes everything the session created: the container,
            the session file, the sockets. It returns only once that is done.
        """
        given: 'a command that would run for ten minutes'
            Process busy = lamp.exec('sleep', '600')
            var container = containerOf(directory)

        when:
            var closing = Instant.now()
            lamp.close()
            var took = Duration.between(closing, Instant.now())

        then: 'the engine ended cleanly, because the application asked it to'
            lamp.exitStatus() == Optional.of(ExitStatus.SUCCESS)
            heard.any { it instanceof LampEvent.Summary
                        && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'well within the time the engine allows for stopping a container'
            took < Duration.ofSeconds(90)

        and: 'the running command ended with it'
            busy.waitFor(30, TimeUnit.SECONDS)

        and: 'the container is gone, and so is the session'
            !containerExists(container)
            !Files.exists(directory.resolve('.oillamp/session.json'))
            oillamp('status', directory.toString()).reported('OIL-SESSION-001')

        and: 'the agent\'s work is still there'
            Files.exists(agentHome(directory).resolve('workspace/notes.md'))
    }

    def 'Opening the lamp again brings back the same agent and everything it kept'() {
        reportInfo """
            A campaign continues next week. The lamp is the same directory, so opening it again
            gives the same agent, with its home as it left it. The image already exists, so this
            start takes seconds rather than minutes.
        """
        given:
            heard.clear()

        when:
            lamp = Lamp.at(directory).onEvent { heard << it }.start()

        then:
            lamp.awaitRunning(LATER_START)

        and: 'the same agent'
            agentId(directory) == firstAgentId

        and: 'with the notes it wrote last time'
            inSandbox(lamp, 'cat', 'workspace/notes.md').out == 'The old chapel is haunted.\n'

        cleanup:
            lamp?.close()
    }

    def 'The lamp is deleted with oillamp, which removes what the user cannot'() {
        reportInfo """
            Some of a lamp's files belong to the sandbox's own infrastructure user, which is not
            the user on the host, so `rm -rf` fails on them. An application deleting a campaign
            uses oillamp's own command, which knows how.
        """
        when:
            var removed = oillamp('remove', directory.toString(), '--yes')

        then:
            removed.status() == ExitStatus.SUCCESS
            !Files.exists(directory)
    }
}
