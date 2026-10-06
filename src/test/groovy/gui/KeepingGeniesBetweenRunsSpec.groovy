package gui

import dev.gui.Genies
import dev.gui.genie.Lighter
import dev.gui.genie.OllamaKeeper
import dev.gui.genie.Shelf
import dev.gui.model.GeniesState
import dev.gui.model.Genie
import dev.gui.model.Settings
import spock.lang.Specification
import spock.lang.TempDir
import sprouts.From
import sprouts.Tuple
import sprouts.Var

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 *  What Genies remembers between runs, and where.
 *
 *  <p>The genies themselves, their names, and the model settings live in one directory, usually
 *  {@code ~/.local/share/genies}. A genie's conversation is not among them: it is in the genie's
 *  own home, inside its lamp, kept by its harness.
 */
class KeepingGeniesBetweenRunsSpec extends Specification {

    @TempDir Path tmp

    def 'The genies come back in order, with their names, asleep'() {
        reportInfo """
            When Genies starts, the user finds the genies they had, in the order they made them.
            All are asleep: a lamp is lit only when its genie is woken.
        """
        given:
            var shelf = new Shelf(tmp)
            var kept = Tuple.of(Genie, Genie.named('Aladdin').waking(), Genie.named('Iago'))

        when:
            shelf.keep(kept)
            var found = new Shelf(tmp).genies()

        then:
            found*.id() == kept*.id()
            found*.name() == ['Aladdin', 'Iago']
            found.every { it.phase() == Genie.Phase.ASLEEP }
    }

    def 'Each genie\'s lamp is a directory of its own on the shelf'() {
        reportInfo """
            The lamp directory is named after the genie's id, which never changes, so renaming a
            genie leaves its lamp where it is.
        """
        given:
            var genie = Genie.named('Aladdin')

        expect:
            new Shelf(tmp).lampOf(genie.id()) == tmp.resolve('lamps').resolve(genie.id().toString())
    }

    def 'Settings are kept however the user leaves the settings page'() {
        reportInfo """
            The settings page has a Done button, but a user may just as well click a genie in
            the list to get back to it. Either way, what they entered is kept, so it is still
            there the next time Genies starts, not only until then.
        """
        given:
            var genie = Genie.named('Genie 1')
            var state = Var.of(GeniesState.of(Tuple.of(Genie, genie), Settings.defaults(), Optional.of('sk-env'))
                    .withPage(GeniesState.Page.SETTINGS))
            // Held until the end: the app watches the page through views it keeps, which go when it goes.
            var app = new Genies(state, new Shelf(tmp), { directory, settings, key, progress, events -> throw new IOException('no lamps here') } as Lighter,
                                 new OllamaKeeper(tmp.resolve('ollama')))

        when: 'the user changes the model, then clicks the genie instead of Done'
            state.update(From.VIEW, { it.withSettings(it.settings().withModel('mistral/mistral-large-latest')) })
            state.update(From.VIEW, { it.select(genie.id()) })

        then:
            new Shelf(tmp).settings().model() == 'mistral/mistral-large-latest'
            app != null
    }

    def 'The settings are kept where only this user can read them, the key only when it is used'() {
        reportInfo """
            An entered model key is kept, so the user does not type it every time, in a file only
            they can read, like ~/.ssh keys. When the settings say to use the key from the
            environment, an earlier entered Eden AI key is not kept at all.
        """
        given:
            var shelf = new Shelf(tmp)
            var entered = Settings.defaults()
                    .withEdenAi(new Settings.EdenAi(Settings.KeySource.ENTERED, 'sk-entered', 'mistral/mistral-medium-latest'))
                    .withElsewhere(new Settings.Elsewhere('https://ollama.example.com/v1', 'sk-server', 'llama3.3:70b'))

        when:
            shelf.keep(entered)

        then:
            new Shelf(tmp).settings() == entered
            PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp.resolve('settings.json'))) == 'rw-------'

        when:
            shelf.keep(entered.withEdenAi(entered.edenAi().withKeySource(Settings.KeySource.ENVIRONMENT)))

        then:
            !Files.readString(tmp.resolve('settings.json')).contains('sk-entered')
    }

    def 'Every place is remembered, and which one the genies use'() {
        reportInfo """
            Eden AI, a server elsewhere and one on this computer each keep their settings, and
            the place chosen is kept with them.
        """
        given:
            var shelf = new Shelf(tmp)
            var chosen = Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
                                .withLocal(new Settings.OnThisMachine('http://127.0.0.1:1234/v1', 'qwen2.5:7b'))
                                .withElsewhere(new Settings.Elsewhere('https://ollama.example.com/v1', '', 'llama3.3:70b'))

        when:
            shelf.keep(chosen)

        then:
            new Shelf(tmp).settings() == chosen
    }

    def 'An older settings file still reads, its other service as a server elsewhere'() {
        reportInfo """
            Before a server elsewhere could be chosen, the settings had one "hosted service",
            Eden AI unless the user named another, with a key and a model. Eden AI's become Eden
            AI's settings. Another service's become those of a server elsewhere, which is what it
            was, with its entered key; the genies go on using it.
        """
        when: 'a file naming Eden AI'
            Files.writeString(tmp.resolve('settings.json'),
                    '{"place":"HOSTED","service":"https://api.eu.edenai.run","keySource":"ENTERED","key":"sk-e","model":"m",' +
                    '"local":{"address":"http://127.0.0.1:1234/v1","model":"q"}}')

        then:
            with(new Shelf(tmp).settings()) {
                place() == Settings.Place.EDEN_AI
                edenAi() == new Settings.EdenAi(Settings.KeySource.ENTERED, 'sk-e', 'm')
                elsewhere() == Settings.defaults().elsewhere()
                local() == new Settings.OnThisMachine('http://127.0.0.1:1234/v1', 'q')
            }

        when: 'a file naming another service'
            Files.writeString(tmp.resolve('settings.json'),
                    '{"service":"https://llm.example.com/v1","keySource":"ENTERED","key":"sk-own","model":"m"}')

        then:
            with(new Shelf(tmp).settings()) {
                place() == Settings.Place.ELSEWHERE
                elsewhere() == new Settings.Elsewhere('https://llm.example.com/v1', 'sk-own', 'm')
                edenAi() == Settings.defaults().edenAi()
                local() == Settings.defaults().local()
            }
    }

    def 'The first time, there are no genies and the settings are the defaults'() {
        reportInfo """
            Nothing needs to exist before the first start. A missing or unreadable file is read
            as nothing kept yet.
        """
        given:
            Files.writeString(tmp.resolve('genies.json'), 'not json')

        expect:
            new Shelf(tmp).genies().isEmpty()
            new Shelf(tmp.resolve('never')).settings() == Settings.defaults()
    }

    def 'The shelf is in the user\'s data directory'() {
        reportInfo """
            Where Linux applications keep their data: under XDG_DATA_HOME when it is set, and in
            ~/.local/share otherwise.
        """
        expect:
            Shelf.standard { it == 'XDG_DATA_HOME' ? Optional.of('/data/me') : Optional.empty() }.root() == Path.of('/data/me/genies')
            Shelf.standard { Optional.empty() }.root() == Path.of(System.getProperty('user.home'), '.local/share/genies')
    }
}
