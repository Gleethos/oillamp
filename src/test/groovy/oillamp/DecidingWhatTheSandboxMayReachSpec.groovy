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
 *  M5, the egress proxy — spec §18.
 *
 *  <p>The sandbox runs with {@code --network=none}. Everything it reaches, it reaches by asking
 *  oillamp — so these scenarios ask oillamp the same way the sandbox does: over the real proxy
 *  socket, in real HTTP, from a real session. The far side is a real server on loopback, so an
 *  allowed connection really carries bytes and a denied one really does not.
 *
 *  <p>The policy under test is the shipped default, not one written to make a scenario pass. It
 *  is the promise the tool makes about what an agent can touch, and the promise is what is worth
 *  checking.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DecidingWhatTheSandboxMayReachSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    final List<LampEvent> reported = new CopyOnWriteArrayList<>()
    final List<String> requestsSeen = new CopyOnWriteArrayList<>()
    Thread session
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

            The lamp here adds one allow rule *above* the shipped deny rule, which is exactly what
            a user is told to do for an internal service — and is the only way to point this
            scenario at a server it can actually run, since anything a test can start is on
            loopback, which the shipped default denies on purpose. The default-allow half of the
            policy is what lets a public host through; the rule below is the only thing standing
            in the way here, and lifting it is the documented move.
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

    def 'The host and its own network stay out of reach — decided by address, not by name'() {
        reportInfo """
            This is the half of the default that makes the other half safe, and it is why the policy
            decides per *resolved address* rather than per host name.

            A name is a claim its owner controls. `totally-normal.example.com` can be pointed at
            127.0.0.1 whenever its DNS operator likes, and a proxy that trusted names would wave
            it through to whatever the user happens to be running on their own machine. Checking
            the address catches it whatever the name says — which is precisely this scenario,
            since the server being asked for really is on loopback.
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
            `console_denied` exists because the two halves of a denial happen in different places:
            the agent sees a build fail, and the reason lives on the host. Without this the user
            reads "connection refused" in one window with nowhere to go and find out who refused.
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

    def 'Every connection is written to the session network log'() {
        reportInfo """
            the session's network journal. Host, port, resolved address, decision, deciding rule and byte counts — and
            never content, because a CONNECT tunnel is copied without being read. What the log is
            for is answering "what did this agent talk to" afterwards, without having had to watch.
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
     *  A lamp whose policy allows the scenario's own server — the §18.4 shape, exactly.
     *
     *  <p>Allow-rules go <em>above</em> the deny rule, because the first match wins. Writing the
     *  deny rule out again is not redundancy: arrays replace rather than merge (§20.1), so a
     *  config that listed only the allow rule would silently drop the protection that makes
     *  allow-by-default reasonable in the first place.
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

    def 'CONNECT opens a tunnel that oillamp carries without reading'() {
        reportInfo """
            The path almost everything real takes, because almost everything real is HTTPS: npm,
            pip, cargo, git-over-https and every API an agent calls. The policy is applied to the
            host and port in the CONNECT line — which is all a proxy can see of a TLS connection
            without becoming a man in the middle of it — and after the 200, bytes are copied in
            both directions without being looked at.

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

    // ─── the world on the other side of the proxy ──────────────────────────────────────────

    /** A real HTTP server on loopback, standing in for somewhere out on the web. */
    private int givenAnOriginServer(String body = 'hello from the internet') {
        origin = new ServerSocket(0, 8, InetAddress.getByName('127.0.0.1'))
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
     *  The proxy socket by its <em>short</em> path, which is the only one that can be connected to.
     *
     *  <p>D-25: {@code sockets} under the runtime directory is a symlink into the lamp, because
     *  the lamp's own path is routinely longer than the kernel's 107-byte limit for a Unix socket
     *  address. Going through the symlink is not a shortcut here, it is the requirement — and it
     *  is exactly what the socat bridge inside the container does.
     */
    /**
     *  Asks for a CONNECT tunnel and, if it is granted, speaks through it.
     *
     *  <p>Written as one exchange on one channel because that is what a tunnel is: the same
     *  connection, carrying something oillamp has agreed not to interpret.
     */
    private String tunnelThrough(int port, String throughTheTunnel) {
        var address = UnixDomainSocketAddress.of(proxySocket())
        try (var channel = SocketChannel.open(address)) {
            write(channel, "CONNECT localhost:${port} HTTP/1.1\r\nHost: localhost:${port}\r\n\r\n")
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

    /** Reads whatever has arrived so far — enough for a status line, without waiting for close. */
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
        session = Thread.start { oillamp.run('at', sandbox.lampPath().toString()) }
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
