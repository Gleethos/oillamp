package gui

import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Hardware
import dev.gui.model.OllamaSetup
import dev.gui.model.Settings
import spock.lang.Specification
import sprouts.Tuple

/**
 *  How a new user's genies get a model: the settings welcome them, and Genies sets up Ollama on
 *  their computer, with a model suggested for it.
 *
 *  <p>All of it follows from values: what the computer has, what Genies found, and the settings.
 *  So it is pinned here without a window, without Ollama and without the internet.
 */
class SettingUpAModelSpec extends Specification {

    def 'How large a model is, read from its name, and how it would run here'() {
        reportInfo """
            Ollama names a model by its size, such as qwen3:8b for eight billion parameters, and
            adds q8_0 or fp16 when each takes more bits than its usual four. From that, Genies
            tells about how much the model weighs, and whether it runs on the graphics card, on
            the processor, or not well at all. A model made of experts, such as qwen3:30b-a3b,
            works only a few of its parameters for each word, so it answers faster than its size.
        """
        given:
            def gigabyte = 1L << 30
            def laptop = new Hardware(16 * gigabyte, Optional.empty())
            def gaming = new Hardware(32 * gigabyte, Optional.of(new Hardware.Graphics('NVIDIA GeForce RTX 4070', 12 * gigabyte)))
        expect:
            laptop.fit(model).runs() == onTheLaptop
            gaming.fit(model).runs() == onTheGamingComputer
            laptop.fit(model).pace() == fairPace
            Math.round(laptop.fit(model).size()) == gigabytes
        where:
            model                     | onTheLaptop                  | onTheGamingComputer          | fairPace | gigabytes
            'qwen3:4b'                | Hardware.Runs.ON_PROCESSOR   | Hardware.Runs.ON_GRAPHICS    | true     | 2
            'qwen3:8b'                | Hardware.Runs.ON_PROCESSOR   | Hardware.Runs.ON_GRAPHICS    | true     | 5
            'qwen3:8b-q8_0'           | Hardware.Runs.TOO_LARGE      | Hardware.Runs.ON_PROCESSOR   | true     | 9
            'qwen3:14b'               | Hardware.Runs.TOO_LARGE      | Hardware.Runs.ON_PROCESSOR   | false    | 9
            'qwen3:30b-a3b'           | Hardware.Runs.TOO_LARGE      | Hardware.Runs.TOO_LARGE      | true     | 19
            'gpt-oss:20b'             | Hardware.Runs.TOO_LARGE      | Hardware.Runs.ON_PROCESSOR   | true     | 12
            'gemma3:270m'             | Hardware.Runs.ON_PROCESSOR   | Hardware.Runs.ON_GRAPHICS    | true     | 0
            'mixtral:8x7b'            | Hardware.Runs.TOO_LARGE      | Hardware.Runs.TOO_LARGE      | false    | 35
            'llama3.2'                | Hardware.Runs.UNKNOWN        | Hardware.Runs.UNKNOWN        | false    | 0
    }

    def 'Genies suggests the best model the computer runs well'() {
        reportInfo """
            Of the models it knows can use tools, as a genie must, Genies suggests the best one
            that runs on the graphics card, which answers far faster; without one that fits there,
            the best that runs at a fair pace on the processor. A large model
            whose every parameter works on each word is passed over on the processor: it would
            fit, but answer too slowly to be of help. With too little memory for any, it
            suggests the smallest.
        """
        given:
            def gigabyte = 1L << 30
            def card = graphics == 0 ? Optional.empty() : Optional.of(new Hardware.Graphics('a card', graphics * gigabyte))
        expect:
            new Hardware(memory * gigabyte, card).suggested() == suggested
        where:
            memory | graphics | suggested
            64     | 0        | 'qwen3:30b-a3b'
            32     | 0        | 'gpt-oss:20b'
            16     | 0        | 'qwen3:8b'
            8      | 0        | 'qwen3:1.7b'
            4      | 0        | 'qwen3:1.7b'
            32     | 12       | 'qwen3:8b'
            32     | 24       | 'gpt-oss:20b'
            64     | 48       | 'qwen3:32b'
    }

    def 'The computer and a model, in words'() {
        reportInfo """
            The settings say what the computer has, and how the model chosen would run on it, so
            the user knows what to expect before a long download.
        """
        given:
            def gigabyte = 1L << 30
            def laptop = new Hardware(16 * gigabyte, Optional.empty())
        expect:
            laptop.words() == '16 GB of memory, and no graphics card a model can run on.'
            new Hardware(64 * gigabyte, Optional.of(new Hardware.Graphics('NVIDIA GeForce RTX 4090', 24 * gigabyte))).words() ==
                    '64 GB of memory, and the graphics card NVIDIA GeForce RTX 4090 with 24 GB.'
            laptop.words('qwen3:8b') == 'About 5.0 GB to download. It runs on the processor, at a fair pace.'
            laptop.words('qwen3:14b').startsWith('About 8.7 GB to download. Running, it needs about 14 GB of memory')
            laptop.words('llama3.2') == 'Its name does not say how large it is, so Genies cannot tell how well it runs here.'
    }

    def 'Without genies, the window starts with the settings, and without the list of genies'() {
        reportInfo """
            A new user has no genies yet, and their genies need a model before anything else. So
            Genies starts with the settings, which welcome them. The settings show the simple way
            to a model, Ollama set up by Genies, unless the genies use another way already. When
            the last genie is deleted, the window goes back to the settings.
        """
        given:
            def none = Tuple.of(Genie)
            def eden = Settings.defaults()
            def ollama = Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
            def genie = Genie.named('Aladdin')
        when:
            def first = GeniesState.of(none, ollama, Optional.empty())
            def again = GeniesState.of(Tuple.of(Genie, genie), eden, Optional.of('sk-env'))
        then:
            first.page() == GeniesState.Page.SETTINGS
            !first.advanced()
            again.page() == GeniesState.Page.CHAT
            again.advanced()
            again.remove(genie.id()).page() == GeniesState.Page.SETTINGS
    }

    def 'What Genies found decides the model wanted at first'() {
        reportInfo """
            Once Genies looked at the computer, the model wanted is the one the genies use, if
            they use Ollama; otherwise the one Genies suggests. A model the user chose meanwhile
            stays chosen. Genies' own versions of the models, named genies/..., are not offered;
            the models they were made from are, before those Genies suggests.
        """
        given:
            def gigabyte = 1L << 30
            def found = new OllamaSetup.Found('/usr/local/bin/ollama', '0.24.0',
                    Tuple.of(String, 'qwen3:8b', 'genies/qwen3:8b', 'llava:7b'), new Hardware(64 * gigabyte, Optional.empty()))
            def eden = Settings.defaults()
            def ollama = Settings.defaults().withPlace(Settings.Place.THIS_MACHINE).withModel('genies/qwen3:8b')
        expect:
            OllamaSetup.NOT_YET.found(found, eden).wanted() == 'qwen3:30b-a3b'
            OllamaSetup.NOT_YET.found(found, ollama).wanted() == 'qwen3:8b'
            OllamaSetup.NOT_YET.withWanted('llava:7b').found(found, ollama).wanted() == 'llava:7b'
            OllamaSetup.NOT_YET.found(found, eden).step() == OllamaSetup.Step.IDLE
            OllamaSetup.NOT_YET.found(found, eden).offered().toList() ==
                    ['qwen3:8b', 'llava:7b', 'qwen3:32b', 'qwen3:30b-a3b', 'gpt-oss:20b', 'qwen3:14b', 'qwen3:4b', 'qwen3:1.7b']
    }

    def 'Setting up does only what is left to do'() {
        reportInfo """
            The button that sets up a model says what it will do: install Ollama and get the
            model, only get the model, or only use it, when Ollama has it already. Ollama that
            runs but was installed some other way, such as in a container, is used as it is.
        """
        given:
            def computer = new Hardware(16L << 30, Optional.empty())
            def setup = { String installed, String version, List<String> models ->
                OllamaSetup.NOT_YET.withWanted('qwen3:8b').found(
                        new OllamaSetup.Found(installed, version, Tuple.of(String, *models), computer), Settings.defaults())
            }
        expect:
            setup('', '', []).todo() == 'Install Ollama and get qwen3:8b'
            setup('', '0.24.0', []).todo() == 'Get qwen3:8b'
            setup('/usr/bin/ollama', '', []).todo() == 'Get qwen3:8b'
            setup('/usr/bin/ollama', '0.24.0', ['qwen3:8b']).todo() == 'Use qwen3:8b'
            setup('/usr/bin/ollama', '0.24.0', ['qwen3:latest']).withWanted('qwen3').todo() == 'Use qwen3'
    }

    def 'Once set up, the genies use the model as Genies prepared it'() {
        reportInfo """
            Genies makes its own version of the model, genies/qwen3:8b, with a context large
            enough for a genie's instructions and its tools' output. The settings then name that
            version, at Ollama's usual address on this computer, and the settings show the model
            is in use.
        """
        given:
            def state = GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.empty())
                    .withOllama(OllamaSetup.NOT_YET.withWanted('qwen3:8b').at(OllamaSetup.Step.TRYING, 0, 'Trying qwen3:8b…'))
        when:
            def ready = state.usingOllama('qwen3:8b')
        then:
            ready.settings().place() == Settings.Place.THIS_MACHINE
            ready.settings().service() == 'http://127.0.0.1:11434/v1'
            ready.settings().model() == 'genies/qwen3:8b'
            ready.settings().usesOllama()
            ready.ollama().inUse(ready.settings())
            ready.ollama().step() == OllamaSetup.Step.IDLE
            !ready.settingsProblem().isPresent()
    }
}
