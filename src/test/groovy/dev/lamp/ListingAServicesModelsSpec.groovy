package dev.lamp

import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 *  An application asking, before any lamp runs, which models a model service will offer the
 *  agent, so its settings can list them instead of a list written into the application.
 *
 *  <p>{@link Lamp#models} asks from this machine, where the engine's relay would ask for the
 *  sandbox, with the key the relay would send. A stand-in server answers the way Ollama does.
 *  That the relay asks the same place is checked in {@code ChoosingTheModelForALampSpec}.
 */
class ListingAServicesModelsSpec extends Specification {

    HttpServer server
    String answer = '{"object":"list","data":[{"id":"qwen2.5:7b","object":"model","owned_by":"library"},' +
                    '{"id":"llama3.2:3b","object":"model","owned_by":"library"}]}'
    int status = 200
    final List<String> asked = []
    final List<String> keys = []

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            asked << exchange.requestURI.toString()
            keys << exchange.requestHeaders.getFirst('Authorization')
            byte[] body = answer.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    def 'A service with a path is asked under it, and its models come back in its order'() {
        reportInfo """
            The address is where the server's OpenAI-style API is, such as
            http://127.0.0.1:11434/v1 for Ollama, so the list is at /v1/models. The ids come back
            in the order the server gave them, and are exactly what the agent names its model with.
        """
        expect:
            Lamp.models(address('/v1/')) == ['qwen2.5:7b', 'llama3.2:3b']
            asked == ['/v1/models']
    }

    def 'A service without a path is asked under /v3, as Eden AI is'() {
        reportInfo """
            Inside the sandbox the model service is always under /v3, Eden AI's path. A service
            given without a path of its own gets the sandbox's path unchanged, so its list is
            at /v3/models.
        """
        expect:
            Lamp.models(address('')) == ['qwen2.5:7b', 'llama3.2:3b']
            asked == ['/v3/models']
    }

    def 'The key goes with the request, as the relay sends it; without one, none is sent'() {
        reportInfo """
            A model server elsewhere, such as Ollama behind a proxy that checks a key, lists its
            models only for that key. It is sent the way the relay sends it with every model
            request: as a bearer token. A server on this machine or Eden AI, whose list is
            public, is asked without one.
        """
        when:
            Lamp.models(address('/v1'), '  sk-for-the-server  ')
            Lamp.models(address('/v1'))

        then:
            keys == ['Bearer sk-for-the-server', null]
    }

    def 'Only a service the engine would accept is asked, since the key travels to it'() {
        reportInfo """
            The engine refuses a model service over plain http anywhere but on this machine,
            because the key would cross the network readable. Asking such a service for its
            list would send the key the same way, so it is refused before anything is sent.
        """
        when:
            Lamp.models(URI.create('http://192.168.1.20:11434/v1'), 'sk-for-the-server')

        then:
            var refused = thrown(IllegalArgumentException)
            refused.message.contains('must be https://')
    }

    def 'When the service cannot be asked, the reason says what to check'() {
        reportInfo """
            The usual mistakes: the server is not running, the address lacks the API's path so
            the server answers 404, or the key is wrong. Each gets a sentence an application can
            show next to its settings.
        """
        when:
            status = 404
            Lamp.models(address(''))

        then:
            var wrongPath = thrown(IOException)
            wrongPath.message.contains('answered 404')
            wrongPath.message.contains('…/v1')

        when:
            status = 401
            Lamp.models(address('/v1'), 'sk-wrong')

        then:
            var wrongKey = thrown(IOException)
            wrongKey.message.contains('refused the key')

        when:
            server.stop(0)
            Lamp.models(address('/v1'))

        then:
            var notRunning = thrown(IOException)
            notRunning.message.contains('Is the model server running?')
    }

    def 'A list that is not an OpenAI-style model list offers no models'() {
        reportInfo """
            Some other program may answer at the address. What it says is not taken for model
            names; the list is simply empty.
        """
        when:
            answer = '<html>not a model server</html>'

        then:
            Lamp.models(address('/v1')).isEmpty()
    }

    def 'Of Eden AI\'s models, only those served in the EU are listed, as in the sandbox'() {
        reportInfo """
            Eden AI's list says where each model is served, and the harness in the sandbox keeps
            only the EU ones. An application should not offer a model the agent will not have.
            Any other service's list names no regions and is kept whole. (Eden AI is recognised
            by its host, which a stand-in cannot have, so the list is read directly here.)
        """
        given:
            var list = '''{"data":[
                {"id":"mistral/mistral-small-latest","regions":[{"code":"eu"}]},
                {"id":"openai/gpt-far-away","regions":[{"code":"us"}]},
                {"id":"mistral/mistral-large-latest","regions":[{"code":"us"},{"code":"EU"}]},
                {"id":"somewhere/unknown"}]}'''

        expect:
            ModelList.isEdenAi(URI.create('https://api.eu.edenai.run'))
            !ModelList.isEdenAi(URI.create('https://ollama.example.com/v1'))
            ModelList.parse(list, true) == ['mistral/mistral-small-latest', 'mistral/mistral-large-latest']
            ModelList.parse(list, false).size() == 4
    }

    private URI address(String path) { URI.create("http://127.0.0.1:${server.address.port}${path}") }
}
