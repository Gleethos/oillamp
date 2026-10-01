package oillamp

import dev.lamp.LampEvent
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The agent shows the user something on its desktop.
 *
 * <p>When the agent has something to show, a chart, a page, a program it wrote, it opens it on its
 * desktop and runs {@code lamp show "what it shows"}. That sends one line to the session on the
 * host, through a socket the sandbox sees as {@code /oillamp/sockets/host/desktop.sock}, and the
 * session reports {@link LampEvent.LookAtDesktop}: an application that shows the desktop, such as
 * Genies, opens it, and the terminal of {@code oillamp at} says so.
 *
 * <p>These scenarios run the real {@code lamp} script against a session on a simulated machine.
 * The socket, the session and the events are real.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@Requires({ Files.isExecutable(Path.of('/usr/bin/python3')) })
class ShowingTheUserTheDesktopSpec extends Specification {

    static final Path LAMP = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()

    @TempDir Path tmp
    @Subject ScenarioHost host

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session
    def ended

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(30_000)
        }
    }

    def 'The agent asks the user to look, and whoever follows the session hears it'() {
        reportInfo """
            The agent opened a chart and runs `lamp show "the chart you asked for"`. The session
            reports that the agent asks the user to look at its desktop, with its words, and the
            terminal oillamp runs in prints it. The agent is told the user was asked; whether they
            look is up to them.
        """
        given:
            var lamp = aRunningSession()

        when:
            var shown = lampShow('the chart you asked for')
            var look = waitFor(LampEvent.LookAtDesktop)
            stop(lamp)

        then:
            shown.status == 0
            shown.output.strip() == 'The user was asked to look at your desktop. Whether and when they look is up to them.'
            look.what() == 'the chart you asked for'
            ended.console().contains('the agent asks you to look at its desktop: the chart you asked for')
    }

    def 'What the agent says reaches the user as plain text, and not too much of it'() {
        reportInfo """
            The agent's words are shown to the user, in an application or a terminal. Escape
            sequences and other control characters could change how a terminal shows everything
            after them, so they are taken out; and a few words say what is shown, so more than 200
            characters are cut. Saying nothing is fine too.
        """
        given:
            aRunningSession()

        when:
            agentAsks(op: 'show', what: 'the \u001B[31mred\u001B[0m chart\u0007 ' + 'x' * 500)
            agentAsks(op: 'show')

        then:
            var looks = waitFor(LampEvent.LookAtDesktop, 2)
            looks[0].what().startsWith('the  red  chart  xxx')
            looks[0].what().length() == 200
            looks[0].what().endsWith('x…')
            looks[1].what() == ''
    }

    def 'The socket does one thing: asking the user to look'() {
        reportInfo """
            The agent can send anything to this socket. Anything but `show` is refused with the
            reason, and nothing is reported.
        """
        given:
            aRunningSession()

        when:
            var answer = agentAsks(op: 'stop')

        then:
            !answer.ok
            answer.error == 'unknown request: stop. The one request here is show.'
            !reported.any { it instanceof LampEvent.LookAtDesktop }
    }

    def 'Without a session that answers, lamp show says the user was not told'() {
        reportInfo """
            An agent that believes the user looks at something, while nobody was told, would go
            on talking to an empty room. So when the session cannot be reached, lamp says so, and
            fails.
        """
        when:
            var shown = lampShow('anything', tmp.resolve('nobody-listens.sock'))

        then:
            shown.status != 0
            shown.output.contains('the session did not answer')
            shown.output.contains('The user was not told.')
    }

    def 'The session tells applications the desktop\'s own size'() {
        reportInfo """
            An application may give the desktop another size for a while, such as the size of the
            panel it shows it in. It gives it back its own size afterwards, which the session says
            when it opens: display.width and display.height.
        """
        given:
            aRunningSession('[display]\nwidth = 1600\nheight = 900\n')

        when:
            var opened = waitFor(LampEvent.SessionOpened)

        then:
            opened.desktopWidth() == 1600
            opened.desktopHeight() == 900
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private Path desktopSocket() { host.runtime.resolve('oillamp/k3v7x2ab/sockets/host/desktop.sock') }

    private Path aRunningSession(String toml = '') {
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n' + toml)
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(90)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { ended = oillamp.run('at', lamp.toString()) }
        waitFor(LampEvent.Summary) { it.title() == 'your session is up' }
        lamp
    }

    private void stop(Path lamp) {
        assert host.oillamp.run('stop', lamp.toString()).succeeded()
        session.join(30_000)
        assert !session.alive
    }

    private <T extends LampEvent> T waitFor(Class<T> kind, Closure<Boolean> which = { true }) {
        waitFor(kind, 1, which).first()
    }

    private <T extends LampEvent> List<T> waitFor(Class<T> kind, int count, Closure<Boolean> which = { true }) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = reported.findAll { kind.isInstance(it) && which(it) }
            if (found.size() >= count) return found as List<T>
            assert System.currentTimeMillis() < deadline : "no ${count} ${kind.simpleName} came"
            Thread.sleep(50)
        }
    }

    /** Runs the real `lamp show` with the desktop socket of the session, as the agent does. */
    private Map lampShow(String what, Path socket = desktopSocket()) {
        var process = new ProcessBuilder('bash', LAMP.toString(), 'show', what).redirectErrorStream(true)
        process.environment().put('LAMP_DESKTOP_SOCKET', socket.toString())
        var started = process.start()
        var output = started.inputStream.text
        started.waitFor()
        [status: started.exitValue(), output: output]
    }

    /** One JSON line to the session's desktop socket, one line back. */
    private Map agentAsks(Map request) {
        SocketChannel.open(UnixDomainSocketAddress.of(desktopSocket())).withCloseable { channel ->
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
