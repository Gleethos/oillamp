package gui

import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import spock.lang.Specification
import sprouts.Tuple

/**
 *  The genies a user keeps, and the model settings they share.
 *
 *  <p>Genies is a chat app in the spirit of Open WebUI or LM Studio, except that every
 *  conversation partner is an agent with a sandboxed desktop of its own. The user makes as many
 *  genies as they like; each one has a lamp. These scenarios pin the value the window is drawn
 *  from, {@link GeniesState}.
 */
class KeepingManyGeniesSpec extends Specification {

    GeniesState state = GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.of('sk-env'))

    def 'A new genie gets a name of its own and its chat is shown'() {
        reportInfo """
            Making a genie is one click, as starting a new chat is elsewhere. It is named
            "Genie 1", "Genie 2" and so on, skipping names already taken, and the user can rename
            it. The new genie's chat is shown at once.
        """
        when:
            var one = state.add(Genie.named(state.freshName()))
            var two = one.add(Genie.named(one.freshName()))

        then:
            two.genies()*.name() == ['Genie 1', 'Genie 2']
            two.genie().name() == 'Genie 2'
            two.page() == GeniesState.Page.CHAT
    }

    def 'Deleting the genie on show shows the one before it'() {
        reportInfo """
            After deleting a genie the chat does not go blank if there are others; it moves to
            the neighbour, as a tab bar does. With none left, there is nothing selected.
        """
        given:
            var a = Genie.named('A')
            var b = Genie.named('B')
            var c = Genie.named('C')
            var three = state.add(a).add(b).add(c).select(b.id())

        expect:
            three.remove(b.id()).genie().name() == 'A'
            three.remove(c.id()).genie().name() == 'B'
            three.remove(a.id()).remove(b.id()).remove(c.id()).selected() == GeniesState.NONE
            !three.remove(a.id()).remove(b.id()).remove(c.id()).hasGenies()
    }

    def 'News from a genie that was deleted meanwhile changes nothing'() {
        reportInfo """
            A genie's lamp keeps reporting while it shuts down, and those reports reach the
            window by the genie's id. If the user deleted the genie in the meantime, the reports
            have nothing to change.
        """
        given:
            var gone = Genie.named('Gone')

        expect:
            state.update(gone.id(), { it.withName('Back') }) == state
    }

    def 'In a narrow window the list of genies steps aside, and comes back when there is room'() {
        reportInfo """
            Windows get snapped to half a screen, or tiled into a strip. Below a certain width
            the list of genies and a conversation no longer fit side by side, so the list hides,
            and the user can still open it. Widening the window shows it again. Around the
            line there is a margin, so dragging the window's edge does not make it flicker.
        """
        when:
            var narrow = state.withViewWidth(600)

        then:
            narrow.narrow()
            !narrow.sidebarShown()

        and: 'the user may open it anyway, and a little wider changes nothing'
            narrow.withSidebarShown(true).withViewWidth(860).sidebarShown()
            narrow.withViewWidth(860).narrow()

        and: 'well past the line, it comes back'
            !narrow.withViewWidth(1000).narrow()
            narrow.withViewWidth(1000).sidebarShown()
    }

    def 'The header shows its buttons with words only when they fit'() {
        reportInfo """
            The header above a conversation holds the genie's name and buttons such as Desktop,
            Sleep and Delete. With the list of genies open, a window that is not narrow yet can
            still leave the header too little room for their words, and the last button would be
            cut off. So the buttons show only their signs whenever the conversation's area is
            small, whatever the window's width.
        """
        expect: 'a roomy area has words'
            state.withArea(1000, 700).roomForWords()

        and: 'a window wide enough for the list, but whose area is small, has signs'
            !state.withArea(520, 700).roomForWords()
            !state.withArea(520, 700).narrow()

        and: 'a narrow window has signs'
            !state.withArea(1000, 700).withViewWidth(600).roomForWords()
    }

    def 'Out of the box, genies use Eden AI\'s EU endpoint with the key from the environment'() {
        reportInfo """
            A user who has EDENAI_API_KEY set needs to set nothing: the settings default to Eden
            AI's EU endpoint, that key, and a model the EU endpoint offers. Without the key, the
            settings say so, in words that say what to do.
        """
        expect:
            state.settings().service() == 'https://api.eu.edenai.run'
            state.settings().place() == Settings.Place.HOSTED
            state.settings().hosted().keySource() == Settings.KeySource.ENVIRONMENT
            state.settings().keyFrom(Optional.of('sk-env')) == Optional.of('sk-env')
            state.settingsProblem().isEmpty()

        and:
            GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.empty())
                    .settingsProblem().get().contains('EDENAI_API_KEY is not set')
    }

    def 'The user can enter a key of their own, and another service'() {
        reportInfo """
            A user without the variable, or with a second account, enters a key in the settings;
            a company may run its own model gateway. The entered key takes the place of the one
            in the environment. It never reaches a genie: the lamp's engine adds it on the host.
        """
        when:
            var own = state.settings().withHosted(state.settings().hosted()
                    .withKeySource(Settings.KeySource.ENTERED).withKey(' sk-mine ').withService('https://llm.example.com:8443'))

        then:
            own.keyFrom(Optional.of('sk-env')) == Optional.of('sk-mine')
            own.problem(Optional.of('sk-env')).isEmpty()

        and: 'an entered key that is empty is a problem, even with one in the environment'
            own.withHosted(own.hosted().withKey('')).problem(Optional.of('sk-env')).get().contains('Enter a key')
    }

    def 'A service the key must not be sent to is refused in the settings already'() {
        reportInfo """
            The same rules as oillamp's own: the key travels to the service with every request,
            so it must be https, unless the service runs on this machine. The settings say so
            while the user types, not when a genie fails to wake.
        """
        expect:
            hostedAt(service).problem(Optional.of('k')).isPresent() == refused

        where:
            service                          || refused
            'https://api.eu.edenai.run'      || false
            'http://127.0.0.1:8080'          || false
            'http://api.eu.edenai.run'       || true
            'https://api.eu.edenai.run/v3'   || false
            'https://api.eu.edenai.run?k=1'  || true
            'not an address'                 || true
    }

    def 'A model server on this computer needs no key, and must be on this computer'() {
        reportInfo """
            Instead of a hosted service, the genies can use a model server running on this
            computer: Ollama, LM Studio or llama.cpp's server. It asks for no key, so none is
            needed, not even the one in the environment; the lamp is given a stand-in the server
            ignores. Its address is where its OpenAI-style API is, such as
            http://127.0.0.1:11434/v1 for Ollama, and it has to be on this computer. The model is
            named as the server lists it.
        """
        given:
            var local = Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)

        expect: 'with a model named, it is ready, without any key'
            local.withModel('qwen2.5:7b').problem(Optional.empty()).isEmpty()
            local.keyFrom(Optional.empty()).isPresent()
            local.withModel('qwen2.5:7b').service() == 'http://127.0.0.1:11434/v1'

        and: 'without a model, or somewhere else, it is not'
            local.problem(Optional.empty()).get().contains('Name the model')
            local.withModel('m').withLocal(new Settings.OnThisMachine('https://llm.example.com/v1', 'm'))
                 .problem(Optional.empty()).get().contains('on this computer')
    }

    def 'Switching between a hosted service and this computer loses neither\'s settings'() {
        reportInfo """
            A user may try a local model and go back to the hosted one. Each place keeps its own
            address and model, and the one the genies use is the one chosen.
        """
        given:
            var both = Settings.defaults().withModel('mistral/mistral-medium-latest')
                    .withPlace(Settings.Place.THIS_MACHINE).withModel('qwen2.5:7b')

        expect:
            both.model() == 'qwen2.5:7b'
            both.withPlace(Settings.Place.HOSTED).model() == 'mistral/mistral-medium-latest'
            both.withPlace(Settings.Place.HOSTED).service() == 'https://api.eu.edenai.run'
    }

    def 'Looking up a model server\'s models offers them, and picks the first if none was chosen'() {
        reportInfo """
            Nobody remembers the exact names a model server gives its models. Look up asks it,
            and the settings offer what it has. If no model was chosen yet, the first one is, so
            a user who just installed Ollama and pulled one model has nothing else to do. The
            note under the list says what was found, or why nothing was.
        """
        when:
            var found = state.modelsFound(Tuple.of(String, 'qwen2.5:7b', 'llama3.2:3b'))

        then:
            found.lookUp().models().toList() == ['qwen2.5:7b', 'llama3.2:3b']
            found.settings().local().model() == 'qwen2.5:7b'
            found.lookUp().note() == 'The model server offers 2 models.'

        and: 'a model already chosen stays chosen'
            state.withSettings(state.settings().withLocal(state.settings().local().withModel('mine')))
                 .modelsFound(Tuple.of(String, 'qwen2.5:7b')).settings().local().model() == 'mine'

        and: 'an empty server, or none, says what to do'
            state.modelsFound(Tuple.of(String)).lookUp().note().contains('ollama pull')
            state.modelsNotFound('Nothing answers at http://127.0.0.1:11434/v1.').lookUp().note().startsWith('Nothing answers')
    }

    def 'Settings never show the key when printed'() {
        reportInfo """
            Settings end up in logs and error messages. Printing them says whether a key was
            entered, never what it is.
        """
        expect:
            !Settings.defaults().withHosted(Settings.defaults().hosted().withKeySource(Settings.KeySource.ENTERED)
                     .withKey('sk-secret-123')).toString().contains('sk-secret-123')
    }

    private static Settings hostedAt(String service) {
        Settings.defaults().withHosted(Settings.defaults().hosted().withService(service))
    }
}
