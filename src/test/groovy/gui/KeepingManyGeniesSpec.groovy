package gui

import dev.gui.model.Fold
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

    def 'A genie that is not awake can have its conversations folded into its card, an awake one cannot'() {
        reportInfo """
            Under each genie's card are its conversations. While the genie is asleep, waking or
            broken, the user can fold them away into the card, to keep a long list of genies
            short. Awake, the genie is busy with them, so they are always shown, and the fold
            is kept for when it sleeps again.
        """
        given:
            var asleep = Genie.named('Jinn')
            var folded = asleep.withConversations(asleep.conversations().withFolded(true))

        expect:
            asleep.showsConversations()
            !folded.showsConversations()
            !folded.withPhase(Genie.Phase.WAKING).showsConversations()
            !folded.withPhase(Genie.Phase.BROKEN).showsConversations()
            folded.withPhase(Genie.Phase.READY).showsConversations()
            folded.withPhase(Genie.Phase.WORKING).showsConversations()
            !folded.withPhase(Genie.Phase.READY).withPhase(Genie.Phase.ASLEEP).showsConversations()
    }

    def 'In a narrow window the list of genies steps aside, and comes back when there is room'() {
        reportInfo """
            Windows get snapped to half a screen, or tiled into a strip. Below a certain width
            the list of genies and a conversation no longer fit side by side, so the list hides,
            and the user can still open it. Widening the window shows it again. Around the
            line there is a margin, so dragging the window's edge does not make it flicker.
            In a narrow window the list is above the genie, as tall as the user drags it; it
            keeps that height while it is folded away and while the window is wide.
        """
        when:
            var narrow = state.withViewWidth(600)

        then:
            narrow.narrow()
            !narrow.genieList().shown()

        and: 'the user may open it anyway, and a little wider changes nothing'
            narrow.withGenieList(narrow.genieList().toggled()).withViewWidth(860).genieList().shown()
            narrow.withViewWidth(860).narrow()

        and: 'well past the line, it comes back'
            !narrow.withViewWidth(1000).narrow()
            narrow.withViewWidth(1000).genieList().shown()

        when: 'the user opens the list above the genie, and drags it taller'
            var taller = narrow.withGenieList(narrow.genieList().toggled().withHeight(320))

        then: 'it keeps its height, folded, wide, and narrow again'
            taller.withGenieList(taller.genieList().toggled()).genieList().height() == 320
            taller.withViewWidth(1000).withViewWidth(600).genieList() == new Fold(false, 320)
    }

    def 'The header makes room in steps: first its buttons drop their words, then the pages go into the menu'() {
        reportInfo """
            The header above a conversation holds the genie's name, the switch between its
            pages (chat, schedule, history) and buttons such as Desktop and Sleep. Each button
            has a sign beside its words. With the list of genies open, a window that is not
            narrow yet can still leave the header too little room for the words, and the last
            button would be cut off. So the buttons show only their signs whenever the
            conversation's area is small, whatever the window's width. In a smaller area still,
            even the signs of the pages do not fit beside the genie's name; the switch steps
            aside, and the pages are in the genie's menu behind "⋯" instead.
        """
        expect: 'a roomy area has words, and the pages'
            state.withArea(1000, 700).roomForWords()
            state.withArea(1000, 700).roomForPages()

        and: 'a window wide enough for the list, but whose area is small, has signs, and the pages'
            !state.withArea(600, 700).roomForWords()
            state.withArea(600, 700).roomForPages()
            !state.withArea(600, 700).narrow()

        and: 'a narrow window has signs'
            !state.withArea(1000, 700).withViewWidth(600).roomForWords()

        and: 'a smaller area has signs, and its pages in the menu'
            !state.withArea(480, 700).roomForWords()
            !state.withArea(480, 700).roomForPages()
    }

    def 'Out of the box, genies use Eden AI\'s EU endpoint with the key from the environment'() {
        reportInfo """
            A user who has EDENAI_API_KEY set needs to set nothing: the settings default to Eden
            AI's EU endpoint, that key, and a model the EU endpoint offers. Without the key, the
            settings say so, in words that say what to do.
        """
        expect:
            state.settings().service() == 'https://api.eu.edenai.run'
            state.settings().place() == Settings.Place.EDEN_AI
            state.settings().edenAi().keySource() == Settings.KeySource.ENVIRONMENT
            state.settings().keyFrom(Optional.of('sk-env')) == Optional.of('sk-env')
            state.settingsProblem().isEmpty()

        and:
            GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.empty())
                    .settingsProblem().get().contains('EDENAI_API_KEY is not set')
    }

    def 'The user can enter an Eden AI key of their own'() {
        reportInfo """
            A user without the variable, or with a second account, enters a key in the settings.
            The entered key takes the place of the one in the environment. It never reaches a
            genie: the lamp's engine adds it on the host.
        """
        when:
            var own = state.settings().withEdenAi(state.settings().edenAi()
                    .withKeySource(Settings.KeySource.ENTERED).withKey(' sk-mine '))

        then:
            own.keyFrom(Optional.of('sk-env')) == Optional.of('sk-mine')
            own.problem(Optional.of('sk-env')).isEmpty()

        and: 'an entered key that is empty is a problem, even with one in the environment'
            own.withEdenAi(own.edenAi().withKey('')).problem(Optional.of('sk-env')).get().contains('Enter a key')
    }

    def 'A model server elsewhere is reached at its address, with its key if it asks for one'() {
        reportInfo """
            A user may run Ollama on another machine, behind a proxy that checks a key. The
            settings take its address, with the path of its OpenAI-style API, and the key. A
            server that asks for no key is sent a stand-in, since the lamp needs one to send; the
            model list is then asked for without any key.
        """
        given:
            var remote = Settings.defaults().withPlace(Settings.Place.ELSEWHERE)
                    .withElsewhere(new Settings.Elsewhere('https://ollama.example.com/v1', ' sk-server ', 'qwen2.5:7b'))

        expect:
            remote.service() == 'https://ollama.example.com/v1'
            remote.model() == 'qwen2.5:7b'
            remote.keyFrom(Optional.empty()) == Optional.of('sk-server')
            remote.listingKeyFrom(Optional.empty()) == Optional.of('sk-server')
            remote.problem(Optional.empty()).isEmpty()

        and: 'without a key, a stand-in goes with its requests, and nothing with the question for its models'
            var open = remote.withElsewhere(remote.elsewhere().withKey(''))
            open.keyFrom(Optional.empty()).isPresent()
            open.listingKeyFrom(Optional.empty()).isEmpty()
            open.problem(Optional.empty()).isEmpty()

        and: 'without an address or a model, it is not ready'
            Settings.defaults().withPlace(Settings.Place.ELSEWHERE).problem(Optional.empty()).get().contains('Enter the server\'s address')
            remote.withModel('').problem(Optional.empty()).get().contains('Name the model')
    }

    def 'A server the key must not be sent to is refused in the settings already'() {
        reportInfo """
            The same rules as oillamp's own: the key travels to the server with every request,
            so it must be https, unless the server runs on this machine. The settings say so
            while the user types, not when a genie fails to wake.
        """
        expect:
            elsewhereAt(address).problem(Optional.empty()).isPresent() == refused

        where:
            address                              || refused
            'https://ollama.example.com/v1'      || false
            'https://ollama.example.com:8443/v1' || false
            'http://127.0.0.1:8080/v1'           || false
            'http://192.168.1.20:11434/v1'       || true
            'https://ollama.example.com/v1?k=1'  || true
            'not an address'                     || true
    }

    def 'A model server on this computer needs no key, and must be on this computer'() {
        reportInfo """
            The genies can also use a model server running on this computer: Ollama, LM Studio
            or llama.cpp's server. It asks for no key, so none is needed, not even the one in the
            environment; the lamp is given a stand-in the server ignores. Its address is where
            its OpenAI-style API is, such as http://127.0.0.1:11434/v1 for Ollama, and it has to
            be on this computer. The model is named as the server lists it.
        """
        given:
            var local = Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)

        expect: 'with a model named, it is ready, without any key'
            local.withModel('qwen2.5:7b').problem(Optional.empty()).isEmpty()
            local.keyFrom(Optional.empty()).isPresent()
            local.listingKeyFrom(Optional.of('sk-env')).isEmpty()
            local.withModel('qwen2.5:7b').service() == 'http://127.0.0.1:11434/v1'

        and: 'without a model, or somewhere else, it is not'
            local.problem(Optional.empty()).get().contains('Name the model')
            local.withModel('m').withLocal(new Settings.OnThisMachine('https://llm.example.com/v1', 'm'))
                 .problem(Optional.empty()).get().contains('on this computer')
    }

    def 'Switching between the places loses none of their settings'() {
        reportInfo """
            A user may try a local model, or one on a server elsewhere, and go back to Eden AI.
            Each place keeps its own address and model, and the one the genies use is the one
            chosen.
        """
        given:
            var all = Settings.defaults().withModel('mistral/mistral-medium-latest')
                    .withPlace(Settings.Place.ELSEWHERE).withElsewhere(new Settings.Elsewhere('https://ollama.example.com/v1', '', 'llama3.3:70b'))
                    .withPlace(Settings.Place.THIS_MACHINE).withModel('qwen2.5:7b')

        expect:
            all.model() == 'qwen2.5:7b'
            all.withPlace(Settings.Place.EDEN_AI).model() == 'mistral/mistral-medium-latest'
            all.withPlace(Settings.Place.EDEN_AI).service() == 'https://api.eu.edenai.run'
            all.withPlace(Settings.Place.ELSEWHERE).model() == 'llama3.3:70b'
            all.withPlace(Settings.Place.ELSEWHERE).service() == 'https://ollama.example.com/v1'
    }

    def 'Looking up a service\'s models offers them, and picks the first if none was chosen'() {
        reportInfo """
            Nobody remembers the exact names a service gives its models, and a list written into
            Genies would soon be out of date. So the settings ask the service they name, and
            offer what it has, in alphabetical order. If no model was chosen yet, the first one
            is, so a user who just installed Ollama and pulled one model has nothing else to do.
            The note under the list says what was found, or why nothing was.
        """
        given:
            var local = state.withSettings(state.settings().withPlace(Settings.Place.THIS_MACHINE))
            var address = 'http://127.0.0.1:11434/v1'

        when:
            var found = local.modelsFound(Settings.Place.THIS_MACHINE, address, Tuple.of(String, 'qwen2.5:7b', 'llama3.2:3b'))

        then:
            found.lookUp().models().toList() == ['llama3.2:3b', 'qwen2.5:7b']
            found.settings().local().model() == 'llama3.2:3b'
            found.lookUp().note() == 'The model server offers 2 models.'

        and: 'a model already chosen stays chosen'
            local.withSettings(local.settings().withModel('mine'))
                 .modelsFound(Settings.Place.THIS_MACHINE, address, Tuple.of(String, 'qwen2.5:7b')).settings().local().model() == 'mine'

        and: 'Eden AI\'s list says it is the EU\'s'
            state.modelsFound(Settings.Place.EDEN_AI, 'https://api.eu.edenai.run', Tuple.of(String, 'a', 'b'))
                 .lookUp().note() == 'Eden AI offers 2 models in the EU.'

        and: 'an empty server, or none, says what to do'
            local.modelsFound(Settings.Place.THIS_MACHINE, address, Tuple.of(String)).lookUp().note().contains('ollama pull')
            local.modelsNotFound(Settings.Place.THIS_MACHINE, address, 'Nothing answers at http://127.0.0.1:11434/v1.')
                 .lookUp().note().startsWith('Nothing answers')
    }

    def 'Models found for another place, or another address, are not offered'() {
        reportInfo """
            Asking takes a moment, and the user may choose another place, or change the address,
            meanwhile. The answer that then arrives is for a service the settings no longer
            name: its models are not offered, and it does not choose a model for the place on
            show.
        """
        given:
            var local = state.withSettings(state.settings().withPlace(Settings.Place.THIS_MACHINE))

        when: 'Eden AI answers after the user switched to this computer'
            var late = local.modelsFound(Settings.Place.EDEN_AI, 'https://api.eu.edenai.run', Tuple.of(String, 'mistral/x'))

        then:
            !late.lookUp().isFor(late.settings())
            late.settings().local().model() == ''

        when: 'the address changes after the answer'
            var answered = local.modelsFound(Settings.Place.THIS_MACHINE, 'http://127.0.0.1:11434/v1', Tuple.of(String, 'qwen2.5:7b'))
            var moved = answered.withSettings(answered.settings().withLocal(answered.settings().local().withAddress('http://127.0.0.1:1234/v1')))

        then:
            answered.lookUp().isFor(answered.settings())
            !moved.lookUp().isFor(moved.settings())
    }

    def 'Settings never show a key when printed'() {
        reportInfo """
            Settings end up in logs and error messages. Printing them says whether a key was
            entered, never what it is.
        """
        expect:
            !Settings.defaults().withEdenAi(Settings.defaults().edenAi().withKeySource(Settings.KeySource.ENTERED)
                     .withKey('sk-secret-123'))
                     .withElsewhere(new Settings.Elsewhere('https://ollama.example.com/v1', 'sk-server-456', 'm'))
                     .toString().matches(/.*(sk-secret-123|sk-server-456).*/)
    }

    private static Settings elsewhereAt(String address) {
        Settings.defaults().withPlace(Settings.Place.ELSEWHERE).withElsewhere(new Settings.Elsewhere(address, 'k', 'm'))
    }
}
