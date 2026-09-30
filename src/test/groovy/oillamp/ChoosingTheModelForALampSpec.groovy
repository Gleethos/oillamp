package oillamp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
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
import java.util.concurrent.TimeUnit

/**
 *  An application choosing, for a lamp it holds, where model requests go and which key they
 *  carry.
 *
 *  <p>An application such as a chat app has its own settings screen, where its user types in a
 *  model service and a key. {@link Lamp.Starting#modelService} and {@link Lamp.Starting#modelKey}
 *  hand them to the engine, which adds the key to the sandbox's requests on the host, exactly as
 *  it does with a key from its environment. The sandbox never sees either.
 *
 *  <p>The engine runs in this JVM against the simulated machine, connected to the lamp by real
 *  pipes. A stand-in model service runs on this machine's loopback, and the scenarios send
 *  requests into the relay's socket as the relay inside the container does.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ChoosingTheModelForALampSpec extends Specification {

    static final String APP_KEY = 'sk-typed-into-the-app-0123456789'

    @TempDir Path tmp
    @Subject Sandbox sandbox
    HttpServer service
    /** The Authorization header of each request the stand-in model service received. */
    final List<String> received = new CopyOnWriteArrayList<>()
    /** The path of each request the stand-in model service received. */
    final List<String> paths = new CopyOnWriteArrayList<>()
    final List<LampEvent> events = new CopyOnWriteArrayList<>()
    Lamp lamp

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
        service = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        service.createContext('/') { HttpExchange exchange ->
            received << exchange.requestHeaders.getFirst('Authorization')
            paths << exchange.requestURI.toString()
            byte[] body = '{"data":[]}'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        service.start()
    }

    def cleanup() {
        lamp?.close()
        service?.stop(0)
    }

    def 'The service and key the application gives are the ones the sandbox\'s requests use'() {
        reportInfo """
            The user typed a service and a key into the application's settings. The application
            starts the lamp with both, and the sandbox's model requests go to that service with
            that key, not to the lamp's configured service with the key from the environment.
            The lamp's own configuration is not touched: the choice is the application's, for
            this session.
        """
        given: 'a key in the environment the application inherited, which should not be used'
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', 'sk-from-the-environment') }

        when: 'the application starts the lamp with its own service and key'
            lamp = Lamp.at(sandbox.lampPath()).onEvent { events << it }
                       .modelService(standInService())
                       .modelKey(APP_KEY)
                       .launchedBy(sandbox.launcher).start()

        then:
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the sandbox asks for the model catalog, holding only its placeholder'
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n'
              + 'Authorization: Bearer held-by-oillamp-on-the-host\r\n\r\n')

        then: 'the stand-in service received it, with the application\'s key'
            received == ["Bearer ${APP_KEY}".toString()]

        and: 'the configuration file oillamp wrote for the new lamp holds neither'
            var written = Files.readString(sandbox.lampPath().resolve('oillamp.toml'))
            !written.contains(APP_KEY)
            !written.contains(standInService().toString())
    }

    def 'The key reaches the engine in its environment, never on its command line'() {
        reportInfo """
            Every user of the machine can read the command line of every process, but only the
            process's own user can read its environment. So the lamp puts the key in the
            engine's environment and tells the engine, on the command line, only the name of the
            variable that holds it. The service is not a secret and goes on the command line.
        """
        when:
            lamp = Lamp.at(sandbox.lampPath()).modelService(standInService()).modelKey(APP_KEY)
                       .launchedBy(sandbox.launcher).start()
            var engine = sandbox.engines.first()

        then: 'the command line names the service and the variable'
            engine.arguments == ['at', sandbox.lampPath().toString(), '--embedded',
                                 '--model-service', standInService().toString(),
                                 '--model-key-env', 'OILLAMP_MODEL_KEY']

        and: 'but not the key'
            !engine.arguments.any { it.contains(APP_KEY) }

        and: 'which is in the engine\'s environment'
            engine.environment == [OILLAMP_MODEL_KEY: APP_KEY]
    }

    def 'Without a choice from the application, the lamp\'s settings and the environment decide'() {
        reportInfo """
            Out of the box, an application needs no model settings at all: the engine inherits
            the application's environment, so EDENAI_API_KEY there is used for Eden AI's EU
            endpoint, or whatever the lamp's `[model]` settings say. Nothing extra is on the
            engine's command line.
        """
        given:
            sandbox.machine { it.environmentVariable('EDENAI_API_KEY', 'sk-from-the-environment') }
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [model]
                service = "${standInService()}"
            """.stripIndent())

        when:
            lamp = Lamp.at(sandbox.lampPath()).launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')

        then:
            sandbox.engines.first().arguments == ['at', sandbox.lampPath().toString(), '--embedded']
            received == ['Bearer sk-from-the-environment']
    }

    def 'An application can choose the key alone, and keep the configured service'() {
        reportInfo """
            The common case in a settings screen: the user pastes their own key and leaves the
            service as it is. The lamp's configured service, by default Eden AI's EU endpoint,
            is used with the application's key.
        """
        given:
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [model]
                service = "${standInService()}"
            """.stripIndent())

        when:
            lamp = Lamp.at(sandbox.lampPath()).modelKey(APP_KEY).launchedBy(sandbox.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')

        then:
            received == ["Bearer ${APP_KEY}".toString()]
    }

    def 'An application can point a lamp at a model server on this machine'() {
        reportInfo """
            A user may run their own models, with Ollama, LM Studio or llama.cpp's server, and
            want their genie to use those. Such a server answers under /v1 on this machine's
            loopback, needs no key, and lists no regions. The application gives its address
            with that path, and any key, which the server ignores: the lamp's requests go there,
            and the harness in the sandbox is not told to keep only EU models, which would
            leave none of the server's.
        """
        when:
            lamp = Lamp.at(sandbox.lampPath())
                       .modelService(URI.create("http://127.0.0.1:${service.address.port}/v1"))
                       .modelKey('local')
                       .launchedBy(sandbox.launcher).start()

        then:
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')

        then: 'the server was asked under its own path'
            paths == ['/v1/models']

        and: 'the sandbox is told not to filter the models by region'
            Files.readString(sandbox.lampPath().resolve('.oillamp/session/runtime.env')).contains("OILLAMP_MODEL_EU_ONLY='0'")
    }

    def 'The models an application lists are asked for where the sandbox asks, with the same key'() {
        reportInfo """
            An application's settings list the models from Lamp.models, asked on the host
            before any lamp runs. That list is only right if it is the one the agent gets: the
            relay's answer to the sandbox's GET /v3/models. So both ask the same path of the
            service, with the same key, for a service with a path of its own and one without.
        """
        given:
            URI given = URI.create("http://127.0.0.1:${service.address.port}${path}")
            lamp = Lamp.at(sandbox.lampPath()).modelService(given).modelKey(APP_KEY)
                       .launchedBy(sandbox.launcher).start()

        expect:
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')
            Lamp.models(given, APP_KEY)

        then:
            paths.size() == 2
            paths[0] == paths[1]
            received == ["Bearer ${APP_KEY}".toString()] * 2

        where:
            path << ['/v1', '']
    }

    def 'A service the key must not be sent to is refused, and the application is told why'() {
        reportInfo """
            The key travels with every request, so a service reached over plain http, anywhere
            but on this machine, would show it to the network. The engine refuses it before
            anything starts, and the lamp reports the refusal as an event the application can
            show, in the words oillamp uses on a terminal.
        """
        when:
            lamp = Lamp.at(sandbox.lampPath()).onEvent { events << it }
                       .modelService(URI.create('http://llm.example.com'))
                       .modelKey(APP_KEY)
                       .launchedBy(sandbox.launcher).start()

        then:
            !lamp.awaitRunning(Duration.ofSeconds(30))
            lamp.exitStatus() == Optional.of(ExitStatus.USAGE)
            events.any { it instanceof LampEvent.Failure && it.problem().whatHappened().contains('must be https://') }
    }

    def 'A blank key is refused at once, before an engine is started'() {
        reportInfo """
            An empty settings field would otherwise start a whole session whose every model
            request fails. The lamp refuses it where the application passes it, so the
            application can point at the field.
        """
        when:
            Lamp.at(sandbox.lampPath()).modelKey('  ')

        then:
            thrown(IllegalArgumentException)
            sandbox.engines.isEmpty()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private URI standInService() { URI.create("http://127.0.0.1:${service.address.port}") }

    /** Sends one request into the relay's socket, as the sandbox does, and returns the answer. */
    private String ask(String request) {
        Path socket
        try (var lamps = Files.list(sandbox.runtime.resolve('oillamp'))) {
            socket = lamps.findFirst().orElseThrow().resolve('sockets/host/model.sock')
        }
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socket))
        channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)))
        var answer = new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8)
        channel.close()
        answer
    }
}
