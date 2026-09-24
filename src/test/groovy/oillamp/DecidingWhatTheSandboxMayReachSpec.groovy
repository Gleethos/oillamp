package oillamp

import dev.oillamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 *  The egress proxy and its policy.
 *
 *  <p>The sandbox runs with {@code --network=none}. Everything it reaches, it reaches by asking
 *  oillamp's proxy, so these scenarios ask the proxy the same way the sandbox does: over the real proxy
 *  socket, in real HTTP, from a real session. The far side is a real server on loopback, so an
 *  allowed connection really carries bytes and a denied one really does not.
 *
 *  <p>The policy under test is the shipped default, not one written to make a scenario pass. It
 *  is the promise the tool makes about what an agent can touch, and the promise is what is worth
 *  checking.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DecidingWhatTheSandboxMayReachSpec extends Specification {

    static final String PRIVATE_RANGES = 'block private, internal and loopback ranges'

    @TempDir Path tmp
    @Subject Sandbox sandbox

    final List<LampEvent> reported = new CopyOnWriteArrayList<>()
    final List<String> requestsSeen = new CopyOnWriteArrayList<>()
    Thread session
    volatile OilLamp.Outcome finished
    ServerSocket origin

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        origin?.close()
        if (session?.alive) {
            sandbox.oillamp.run('stop', sandbox.lampPath().toString())
            session.join(20_000)
        }
    }

    def 'An allowed connection really carries bytes, all the way to the far side'() {
        reportInfo """
            The allow path, end to end: the proxy parses the request, decides, resolves, connects,
            rewrites the request into origin form and streams the answer back. A real server on
            the other side receives it and replies.

            The lamp here adds one allow rule *above* the default deny rule, which is what a user
            does to reach an internal service. It is also the only way to point this scenario at
            a server it can run: a test server is on loopback, which the default policy denies on
            purpose.
        """
        given: 'a server to reach, and a lamp told it may be reached'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when: 'the sandbox asks the proxy for it, exactly as curl with HTTP_PROXY set would'
            var answer = askTheProxy("GET http://localhost:${port}/packages HTTP/1.1\r\n\r\n")

        then: 'the proxy allowed it, and the server on the other side answered'
            answer.status == 200
            answer.body.contains('hello from the internet')

        and: 'the request really arrived, rewritten into origin form'
            requestsSeen.any { it.startsWith('GET /packages ') }
    }

    def 'The host and its own network stay out of reach, decided by address and not by name'() {
        reportInfo """
            The default policy allows the internet but denies private and loopback addresses. This
            is why the policy decides per *resolved address* rather than per host name.

            A name is a claim its owner controls. `totally-normal.example.com` can be pointed at
            127.0.0.1 whenever its DNS operator likes, and a proxy that trusted names would wave
            it through to whatever the user happens to be running on their own machine. Checking
            the address catches it whatever the name says. Here, `localhost` resolves to the
            loopback address and is refused.
        """
        given:
            var port = givenAnOriginServer()
            startASession()

        when: 'a name that resolves to the host\'s own loopback is asked for'
            var answer = askTheProxy("GET http://localhost:${port}/secrets HTTP/1.1\r\n\r\n")

        then: 'the proxy refuses'
            answer.status == 403

        and: 'and quotes the rule, so the agent can report why instead of guessing'
            answer.body.contains('denied by rule')
            answer.body.contains('block private, internal and loopback ranges')

        and: 'nothing whatsoever reached the server'
            requestsSeen.isEmpty()
    }

    def 'A denial is printed in the terminal the user is already watching'() {
        reportInfo """
            With `network.console_denied` on (the default), every denial is printed in the
            terminal oillamp was started from. The agent only sees its build fail; the user needs
            to see which rule refused the connection, so they know what to change.
        """
        given:
            var port = givenAnOriginServer()
            startASession()

        when:
            askTheProxy("GET http://127.0.0.1:${port}/x HTTP/1.1\r\n\r\n")

        then:
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('denied') } }
            reported.find { it instanceof LampEvent.Info && it.text().contains('denied') }
                    .text().contains('block private, internal and loopback ranges')
    }

    def 'Every denial reaches the session\'s record, even when many arrive at once'() {
        reportInfo """
            Everything oillamp says is kept in the outcome of the command as well as printed. During
            a session it is said from many threads at once: each proxied connection, each shell,
            the health check and the control socket have their own. An agent retrying a blocked
            address in parallel produces many denials in the same instant, and a record that
            could not be added to from several threads at once would lose some of them, or fail
            inside the thread that was reporting.
        """
        given:
            var port = givenAnOriginServer()
            startASession()

        when: 'three hundred denied requests arrive at the same moment'
            var requests = (1..300).collect { i ->
                Thread.startVirtualThread { askPersistently("GET http://127.0.0.1:${port}/${i} HTTP/1.1\r\n\r\n") }
            }
            requests*.join()
            waitUntil { reported.count { isDenial(it) } == 300 }

        and: 'the session ends'
            sandbox.oillamp.run('stop', sandbox.lampPath().toString())
            session.join(30_000)

        then: 'the outcome holds every one of them'
            finished != null
            finished.events().count { isDenial(it) } == 300
    }

    /** Asks again while the proxy's queue of waiting connections is full, as a patient client would. */
    private void askPersistently(String request) {
        for (int attempt = 0; ; attempt++) {
            try {
                askTheProxy(request)
                return
            } catch (IOException full) {
                if (attempt >= 100) throw full
                Thread.sleep(20)
            }
        }
    }

    private static boolean isDenial(LampEvent event) {
        event instanceof LampEvent.Info && event.text().startsWith('denied ')
    }

    def 'A burst of connections is answered, not turned away at the door'() {
        reportInfo """
            A build opens many connections at once: a package manager fetching dependencies in
            parallel, a browser loading a page. They all arrive at the proxy's socket in the same
            instant. The kernel holds new connections in a queue until the proxy accepts them, and
            that queue was 50 long, the default. The 51st connection in a burst was refused
            outright, and the agent saw a network error that no rule explained.
        """
        given:
            var port = givenAnOriginServer()
            startASession()

        when: 'three hundred requests arrive at the same moment, and none of them tries twice'
            var answers = new CopyOnWriteArrayList<Integer>()
            var failures = new CopyOnWriteArrayList<String>()
            var requests = (1..300).collect { i ->
                Thread.startVirtualThread {
                    try {
                        answers << askTheProxy("GET http://127.0.0.1:${port}/${i} HTTP/1.1\r\n\r\n").status
                    } catch (IOException refused) {
                        failures << refused.message
                    }
                }
            }
            requests*.join()

        then: 'every one of them got an answer from the proxy'
            failures.isEmpty()
            answers.size() == 300
            answers.every { it == 403 }
    }

    def 'Every connection is written to the session network log'() {
        reportInfo """
            Every connection is written to `.oillamp/logs/network-<session>.jsonl`, one JSON object
            per line: host, port, resolved address, decision, deciding rule and byte counts. Never
            content, because a CONNECT tunnel is copied without being read. The log answers "what
            did this agent talk to?" afterwards.
        """
        given:
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when:
            askTheProxy("GET http://localhost:${port}/p HTTP/1.1\r\n\r\n")

        then: 'one JSON object per connection'
            waitUntil { Files.exists(networkLog()) && !Files.readAllLines(networkLog()).isEmpty() }
            var log = Files.readAllLines(networkLog())
            log.every { it.startsWith('{') && it.endsWith('}') }

        and: 'recording the address it resolved to, the decision, and the rule that made it'
            log.any { it.contains('"host":"localhost"') && it.contains('"decision":"allow"') &&
                      it.contains('"address":"127.0.0.1"') && it.contains('the test origin server') }
    }

    def 'Something that mistakes the proxy for a web server is told what it is'() {
        reportInfo """
            An origin-form request means a client that has the proxy's address but does not know
            it is a proxy. A bare 400 would send whoever wrote it hunting for a bug in their URL,
            so the answer says what this is and what to set instead.
        """
        given:
            startASession()

        when:
            var answer = askTheProxy("GET /index.html HTTP/1.1\r\nHost: example.test\r\n\r\n")

        then:
            answer.status == 400
            answer.body.contains('egress proxy')
            answer.body.contains('HTTP_PROXY')
    }

    /**
     *  A lamp whose policy allows the scenario's own server.
     *
     *  <p>Allow rules go <em>above</em> the deny rule, because the first match wins. The deny rule
     *  is written out again because a lamp's list of rules replaces the default list instead of
     *  being added to it; listing only the allow rule would drop the deny rule.
     */
    private void givenALampAllowing(int port) {
        sandbox.givenConfig(sandbox.lampPath(), """
            schema_version = 1

            [[network.rules]]
            label  = "the test origin server"
            action = "allow"
            cidrs  = ["127.0.0.0/8"]
            ports  = [${port}]

            [[network.rules]]
            label  = "block private, internal and loopback ranges"
            action = "deny"
            cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                      "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                      "::1/128", "fc00::/7", "fe80::/10"]
            """.stripIndent())
    }

    /** A lamp whose only rule allows every address, as a hand-written policy might. */
    private void givenALampAllowingEverything() {
        sandbox.givenConfig(sandbox.lampPath(), """
            schema_version = 1

            [[network.rules]]
            label  = "anything goes"
            action = "allow"
            """.stripIndent())
    }

    def 'CONNECT opens a tunnel that oillamp carries without reading'() {
        reportInfo """
            Most real traffic uses CONNECT, because it is HTTPS: npm, pip, cargo, git over HTTPS
            and every API an agent calls. The policy is applied to the host and port in the
            CONNECT line, which is all a proxy can see of an encrypted connection without
            intercepting it. After the 200, bytes are copied in both directions without being
            read.

            Checked here by tunnelling a plain request through and getting the answer back, which
            proves the copying rather than taking it on trust.
        """
        given:
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when: 'a tunnel is asked for, and then used'
            var answer = tunnelThrough(port, "GET /through-the-tunnel HTTP/1.1\r\nHost: localhost\r\n\r\n")

        then: 'the proxy established it and the far side answered through it'
            answer.contains('200 Connection Established')
            answer.contains('hello from the internet')

        and: 'the server saw the request oillamp never parsed'
            requestsSeen.any { it.startsWith('GET /through-the-tunnel ') }
    }

    def 'A denied CONNECT is refused before any tunnel exists'() {
        reportInfo """
            The denial has to come first. A proxy that answered 200 and then closed would leave
            the agent looking at a TLS handshake that failed for no stated reason; refusing with
            403 and the rule label means the failure arrives as a sentence it can report.
        """
        given:
            var port = givenAnOriginServer()
            startASession()

        when: 'a tunnel to the host\'s own loopback is asked for under the shipped default'
            var answer = tunnelThrough(port, "GET / HTTP/1.1\r\n\r\n")

        then:
            answer.contains('403')
            answer.contains('block private, internal and loopback ranges')
            !answer.contains('200 Connection Established')

        and: 'nothing was tunnelled'
            requestsSeen.isEmpty()
    }

    def 'The host and its network stay out of reach however the address is written: #target'() {
        reportInfo """
            An address can be written many ways. 127.0.0.1 is also `::ffff:7f00:1` (the same
            address in IPv6 notation), and `::` means "this machine" to the operating system. A
            policy that compared only one spelling would let the others through to whatever the
            user runs on their own machine, which is the one thing the sandbox exists to prevent.

            So every spelling is turned into one form before the policy looks at it, and the
            proxy connects to exactly the address the policy approved.
        """
        given: 'a server listening on every address of this machine, as sshd does'
            var port = givenAnOriginServer('hello from the internet', '::')
            startASession()

        when:
            var answer = tunnelThrough(port, "GET / HTTP/1.1\r\n\r\n", target)

        then: 'the proxy refuses, saying why'
            answer.contains('403')
            answer.contains(reason)
            !answer.contains('200 Connection Established')

        and: 'nothing reached the server'
            requestsSeen.isEmpty()

        where:
            target                     | reason
            '127.0.0.1'                | PRIVATE_RANGES
            '[::1]'                    | PRIVATE_RANGES
            '[::ffff:7f00:1]'          | PRIVATE_RANGES
            '[::ffff:127.0.0.1]'       | PRIVATE_RANGES
            '[0:0:0:0:0:ffff:7f00:1]'  | PRIVATE_RANGES
            '127.1'                    | PRIVATE_RANGES
            '2130706433'               | PRIVATE_RANGES
            'localhost.'               | PRIVATE_RANGES
            '[::ffff:192.168.1.1]'     | PRIVATE_RANGES
            '[::ffff:a00:1]'           | PRIVATE_RANGES
            '[::]'                     | 'the unspecified address, which means this machine'
            '0.0.0.0'                  | 'the unspecified address, which means this machine'
    }

    def 'A lamp configured before the unspecified address was blocked still cannot reach the host through it'() {
        reportInfo """
            Every lamp keeps its own copy of the network rules in oillamp.toml, written when the
            lamp was created. A rule added to the shipped list later never reaches those copies.
            The unspecified address, `::` or `0.0.0.0`, means "this machine" to Linux and is never
            a real destination, so it is refused even when the lamp's own rules would allow it.
        """
        given: 'a lamp whose rules allow everything'
            var port = givenAnOriginServer('hello from the internet', '::')
            givenALampAllowingEverything()
            startASession()

        when:
            var answer = tunnelThrough(port, "GET / HTTP/1.1\r\n\r\n", '[::]')

        then:
            answer.contains('403')
            answer.contains('no rule can allow it')
            requestsSeen.isEmpty()
    }

    // ─── the world on the other side of the proxy ──────────────────────────────────────────

    /** A real HTTP server on loopback, standing in for somewhere out on the web. */
    private int givenAnOriginServer(String body = 'hello from the internet', String bindTo = '127.0.0.1') {
        origin = new ServerSocket(0, 8, InetAddress.getByName(bindTo))
        Thread.startVirtualThread {
            while (!origin.isClosed()) {
                try {
                    var client = origin.accept()
                    var reader = new BufferedReader(new InputStreamReader(client.inputStream))
                    var line = reader.readLine()
                    if (line != null) requestsSeen.add(line)
                    client.outputStream.write(("HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.length()}\r\nConnection: close\r\n\r\n" +
                            body).getBytes(StandardCharsets.UTF_8))
                    client.outputStream.flush()
                    client.close()
                } catch (IOException ignored) {
                    return      // the server was closed by cleanup; nothing to report
                }
            }
        }
        origin.localPort
    }

    // ─── talking to the proxy the way the sandbox does ─────────────────────────────────────

    private record Answer(int status, String body) {}

    /**
     *  Sends one request over the real proxy socket and reads the whole reply.
     *
     *  <p>This is the same socket the socat bridge inside the container connects to, reached the
     *  same way. Nothing about the proxy is stubbed: if this passes, an agent typing `curl` gets
     *  the same answer.
     */
    private Answer askTheProxy(String request) {
        var address = UnixDomainSocketAddress.of(proxySocket())
        try (var channel = SocketChannel.open(address)) {
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1)))
            var received = new ByteArrayOutputStream()
            var buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                var bytes = new byte[buffer.remaining()]
                buffer.get(bytes)
                received.write(bytes)
                buffer.clear()
            }
            var text = received.toString(StandardCharsets.UTF_8)
            var split = text.indexOf('\r\n\r\n')
            var head = split < 0 ? text : text.substring(0, split)
            var body = split < 0 ? '' : text.substring(split + 4)
            var status = head.isBlank() ? 0 : Integer.parseInt(head.split(' ')[1])
            new Answer(status, body)
        }
    }

    /**
     *  Asks for a CONNECT tunnel and, if it is granted, speaks through it.
     *
     *  <p>Written as one exchange on one channel because that is what a tunnel is: the same
     *  connection, carrying something oillamp has agreed not to interpret.
     */
    private String tunnelThrough(int port, String throughTheTunnel, String host = 'localhost') {
        var address = UnixDomainSocketAddress.of(proxySocket())
        try (var channel = SocketChannel.open(address)) {
            write(channel, "CONNECT ${host}:${port} HTTP/1.1\r\nHost: ${host}:${port}\r\n\r\n")
            var opening = readSome(channel)
            // A refusal is a whole response and then a close, so it has to be read to the end:
            // a single read can return the head with the body still in flight, and the body is
            // the part naming the rule. Only the 200 case stops reading, because there the
            // channel stays open for the tunnel.
            if (!opening.contains('200')) return opening + readAll(channel)
            write(channel, throughTheTunnel)
            opening + readAll(channel)
        }
    }

    private static void write(SocketChannel channel, String text) {
        channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)))
    }

    /** Reads whatever has arrived so far: enough for a status line, without waiting for close. */
    private static String readSome(SocketChannel channel) {
        var buffer = ByteBuffer.allocate(4096)
        var read = channel.read(buffer)
        if (read <= 0) return ''
        buffer.flip()
        var bytes = new byte[buffer.remaining()]
        buffer.get(bytes)
        new String(bytes, StandardCharsets.UTF_8)
    }

    private static String readAll(SocketChannel channel) {
        var received = new ByteArrayOutputStream()
        var buffer = ByteBuffer.allocate(8192)
        while (channel.read(buffer) >= 0) {
            buffer.flip()
            var bytes = new byte[buffer.remaining()]
            buffer.get(bytes)
            received.write(bytes)
            buffer.clear()
        }
        received.toString(StandardCharsets.UTF_8)
    }

    /**
     *  The proxy socket, reached through the short runtime directory. The lamp's own path is
     *  often longer than the kernel's 107-byte limit for a Unix socket path, so the runtime
     *  directory holds a symlink into the lamp, and oillamp always connects through it.
     */
    private Path proxySocket() {
        var agents = Files.list(sandbox.runtime.resolve('oillamp')).toList()
        assert agents.size() == 1 : "expected one agent runtime directory, found ${agents}"
        var socket = agents.first().resolve('sockets/host/proxy.sock')
        assert Files.exists(socket) : "the session never bound a proxy socket at ${socket}"
        socket
    }

    private Path networkLog() {
        Files.list(sandbox.lampPath().resolve('.oillamp/logs'))
             .filter { it.fileName.toString().startsWith('network-') }
             .findFirst()
             .orElse(sandbox.lampPath().resolve('.oillamp/logs/network-none.jsonl'))
    }

    // ─── running a session beside the scenario ─────────────────────────────────────────────

    private void startASession() {
        sandbox.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var oillamp = sandbox.oillamp.observedBy { reported.add(it) }
        session = Thread.start { finished = oillamp.run('at', sandbox.lampPath().toString()) }
        waitUntil { reported.any { it instanceof LampEvent.Summary &&
                                   it.title() == 'your session is up' } }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("the session never got there: ${condition}" as Object)
    }
}
