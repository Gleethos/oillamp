package gui

import com.sun.net.httpserver.HttpServer
import dev.gui.genie.ModelCatalog
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 *  How Genies finds out which models a model server on this computer has.
 *
 *  <p>OpenAI-style servers, Ollama, LM Studio and llama.cpp's server among them, list their models
 *  at {@code <address>/models}. Genies asks from the host, when the user clicks Look up in the
 *  settings. Here a stand-in server answers the way Ollama does.
 */
class AskingAModelServerForItsModelsSpec extends Specification {

    HttpServer server
    String answer = '{"object":"list","data":[{"id":"qwen2.5:7b","object":"model","owned_by":"library"},' +
                    '{"id":"llama3.2:3b","object":"model","owned_by":"library"}]}'
    int status = 200
    final List<String> asked = []

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            asked << exchange.requestURI.toString()
            byte[] body = answer.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    def 'The server is asked under its API\'s own path, and its models are listed in its order'() {
        reportInfo """
            The address in the settings is where the server's API is, such as
            http://127.0.0.1:11434/v1, so the list is at /v1/models. The ids come back in the
            order the server gave them, and are exactly what a genie names its model with.
        """
        expect:
            ModelCatalog.ofServerAt(address('/v1/')).toList() == ['qwen2.5:7b', 'llama3.2:3b']
            asked == ['/v1/models']
    }

    def 'When the server cannot be asked, the reason says what to check'() {
        reportInfo """
            The usual mistakes: the server is not running, or the address lacks the API's path,
            so the server answers 404. Each gets a sentence the settings can show.
        """
        when:
            status = 404
            ModelCatalog.ofServerAt(address(''))

        then:
            var wrongPath = thrown(IOException)
            wrongPath.message.contains('answered 404')
            wrongPath.message.contains('…/v1')

        when:
            server.stop(0)
            ModelCatalog.ofServerAt(address('/v1'))

        then:
            var notRunning = thrown(IOException)
            notRunning.message.contains('Is the model server running?')
    }

    def 'A list that is not an OpenAI-style model list offers no models'() {
        reportInfo """
            Some other program may answer at the address. What it says is not taken for model
            names; the list is simply empty, and the settings say so.
        """
        when:
            answer = '<html>not a model server</html>'

        then:
            ModelCatalog.ofServerAt(address('/v1')).isEmpty()
    }

    private String address(String path) { "http://127.0.0.1:${server.address.port}${path}" }
}
