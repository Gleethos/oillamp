package oillamp

import dev.lamp.Lamp
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Tag

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

import static oillamp.RealLamps.*

/**
 * A lamp whose model runs on this machine, with Ollama, as an application sets it up through
 * {@link Lamp}: the service with the path of its API, and a key the server ignores.
 *
 * <p>Needs Ollama listening on 127.0.0.1:11434 with the model {@code OILLAMP_SPIKE_LOCAL_MODEL}
 * pulled, by default {@code qwen2.5:0.5b} (about 400 MB, {@code ollama pull qwen2.5:0.5b}); without
 * it, the spike is skipped. Nothing here uses Eden AI.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() && UsingAModelOnThisMachineSpec.ollamaHas(UsingAModelOnThisMachineSpec.MODEL) })
class UsingAModelOnThisMachineSpec extends Specification {

    static final String MODEL = System.getenv('OILLAMP_SPIKE_LOCAL_MODEL') ?: 'qwen2.5:0.5b'
    static final String OLLAMA = 'http://127.0.0.1:11434'

    @Shared Path directory = newLampPath('local-model')
    @Shared Lamp lamp

    def cleanupSpec() {
        lamp?.close()
        remove(directory)
    }

    def 'A lamp pointed at Ollama starts, and its harness is not told to keep only EU models'() {
        reportInfo """
            Ollama answers OpenAI-style requests under /v1 on this machine's loopback. The lamp
            is given http://127.0.0.1:11434/v1 and any key. In the sandbox, nothing changes
            about the address, the relay's, but EDENAI_EU_ONLY is not set: Ollama's model list
            names no regions, and filtering it would leave nothing.
        """
        when:
            lamp = Lamp.at(directory).modelService(URI.create(OLLAMA + '/v1')).modelKey('local').start()

        then:
            lamp.awaitRunning(FIRST_START)

        and:
            inSandbox(lamp, 'sh', '-c', 'echo "[$EDENAI_BASE_URL] [${EDENAI_EU_ONLY:-unset}]"').out.strip() ==
                    '[http://127.0.0.1:3129/v3] [unset]'
    }

    def 'Through the relay, the sandbox sees Ollama\'s models and gets answers'() {
        reportInfo """
            The sandbox asks the relay under /v3, as it always does, and the relay asks Ollama
            under /v1. The model list is Ollama's own, and a chat request is answered by the
            model on this machine.
        """
        when:
            var models = inSandbox(lamp, 'curl', '-s', '--max-time', '30', 'http://127.0.0.1:3129/v3/models')
            var answer = inSandbox(lamp, 'curl', '-s', '--max-time', '120',
                    '-H', 'Content-Type: application/json',
                    '-d', '{"model":"' + MODEL + '","max_tokens":5,"messages":[{"role":"user","content":"Reply with the word ok"}]}',
                    'http://127.0.0.1:3129/v3/chat/completions')

        then:
            new JsonSlurper().parseText(models.out).data*.id.contains(MODEL)
            new JsonSlurper().parseText(answer.out).choices[0].message.content instanceof String
    }

    def 'pi uses the model on this machine'() {
        reportInfo """
            The harness itself, as an agent runs it: pi's Eden AI extension reads the model list
            through the relay, keeps all of it because EU only is off, and the local model
            answers.
        """
        when:
            var ran = inSandbox(lamp, 'pi', '--provider', 'edenai', '--model', MODEL, '--no-session',
                                '-p', 'Reply with the single word ok.')

        then:
            ran.ok
            !ran.out.isBlank()
    }

    static boolean ollamaHas(String model) {
        try {
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(OLLAMA + '/v1/models')).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString())
            (new JsonSlurper().parseText(response.body()).data ?: [])*.id.contains(model)
        } catch (Exception notThere) {
            false
        }
    }
}
