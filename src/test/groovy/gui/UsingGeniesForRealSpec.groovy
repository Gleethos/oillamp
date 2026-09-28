package gui

import dev.gui.Genies
import dev.gui.genie.LampLighter
import dev.gui.genie.Shelf
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import oillamp.RealLamps
import oillamp.Spike
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Tag
import sprouts.Tuple
import sprouts.Var

import javax.swing.SwingUtilities
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 *  The Genies app as the user clicks through it, minus the window: its {@link Genies} actions,
 *  called as the buttons call them, against real lamps on this machine.
 *
 *  <p>The shelf is a temporary directory rather than {@code ~/.local/share/genies}, so the user's
 *  own genies are left alone. The key is the one in {@code EDENAI_API_KEY}; without it the
 *  scenario that needs the model is skipped.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class UsingGeniesForRealSpec extends Specification {

    static final String KEY = System.getenv('EDENAI_API_KEY') ?: ''

    @Shared Path shelfDirectory = Files.createTempDirectory('genies-spike-')
    @Shared Shelf shelf = new Shelf(shelfDirectory)
    @Shared Var<GeniesState> state = Var.of(GeniesState.of(Tuple.of(Genie), Settings.defaults(),
                                                             Optional.of(KEY ?: 'sk-no-real-key')))
    @Shared Genies app = new Genies(state, shelf, new LampLighter())
    @Shared UUID id

    def cleanupSpec() {
        state.get().genies().each { genie -> onTheWindowsThread { app.delete(genie.id()) } }
        RealLamps.eventually(Duration.ofMinutes(4)) { !Files.exists(shelfDirectory.resolve('lamps')) || Files.list(shelfDirectory.resolve('lamps')).count() == 0 }
        Spike.removeTree(shelfDirectory.toString())
    }

    def 'A new genie wakes by itself, and is kept on the shelf'() {
        reportInfo """
            "New genie" is the one click it takes: the genie is named, listed, kept on the shelf
            and woken at once, and its lamp is made in the shelf's lamps directory.
        """
        when:
            onTheWindowsThread { app.newGenie() }
            id = state.get().genie().id()

        then:
            RealLamps.eventually(RealLamps.FIRST_START) { genie().phase() != Genie.Phase.WAKING }
            genie().phase() == Genie.Phase.READY
            genie().name() == 'Genie 1'
            Files.readString(shelfDirectory.resolve('genies.json')).contains(id.toString())
            Files.isDirectory(shelf.lampOf(id).resolve('.oillamp'))

        and: 'its desktop is there to be shown'
            app.desktopOf(id).map { Files.exists(it) }.orElse(false)
    }

    @Requires({ KEY })
    def 'What the user types is answered in the chat'() {
        reportInfo """
            Typing and pressing return: the draft becomes the user's message in the chat, the
            genie works, and its answer appears below it.
        """
        when:
            onTheWindowsThread {
                state.update { it.update(id) { genie -> genie.withDraft('Reply with the single word pong.') } }
                app.send()
            }

        then:
            RealLamps.eventually(Duration.ofMinutes(3)) {
                genie().phase() == Genie.Phase.READY && genie().transcript().entries().any { it.kind() == Entry.Kind.GENIE }
            }
            genie().transcript().entries().find { it.kind() == Entry.Kind.GENIE }.text().toLowerCase().contains('pong')
    }

    def 'Deleting the genie removes its lamp for good'() {
        reportInfo """
            Delete asks first, in the window; here the answer is yes. The genie leaves the list at
            once, and in the background its lamp is put out and removed through the engine,
            sandbox-owned files and all.
        """
        when:
            var lamp = shelf.lampOf(id)
            onTheWindowsThread { app.delete(id) }

        then:
            !state.get().hasGenies()
            RealLamps.eventually(Duration.ofMinutes(4)) { !Files.exists(lamp.resolve('.oillamp')) }
            !Files.readString(shelfDirectory.resolve('genies.json')).contains(id.toString())
    }

    @Requires({ oillamp.UsingAModelOnThisMachineSpec.ollamaHas(oillamp.UsingAModelOnThisMachineSpec.MODEL) })
    def 'A genie talks to a model running on this computer'() {
        reportInfo """
            The user picks "on this computer" in the settings, clicks Look up, and Ollama's
            models are offered, the first one chosen. A new genie then wakes with no key at all,
            and its answer comes from the model on this machine. Needs Ollama with the spike's
            small model pulled.
        """
        when: 'the settings are switched to Ollama, and its models looked up'
            onTheWindowsThread {
                state.update { it.withSettings(it.settings().withPlace(Settings.Place.THIS_MACHINE)
                        .withLocal(new Settings.OnThisMachine('http://127.0.0.1:11434/v1', ''))) }
                app.lookUpModels()
            }

        then:
            RealLamps.eventually(Duration.ofSeconds(20)) { !state.get().settings().local().model().isBlank() }
            state.get().lookUp().models().contains(oillamp.UsingAModelOnThisMachineSpec.MODEL)
            state.get().settingsProblem().isEmpty()

        when: 'a genie is made, the settings are set to the spike\'s model, and it is asked something'
            onTheWindowsThread {
                state.update { it.withSettings(it.settings().withModel(oillamp.UsingAModelOnThisMachineSpec.MODEL)) }
                app.newGenie()
            }
            id = state.get().genie().id()
            RealLamps.eventually(RealLamps.LATER_START + Duration.ofMinutes(1)) { genie().phase() != Genie.Phase.WAKING }
            onTheWindowsThread {
                state.update { it.update(id) { genie -> genie.withDraft('Say hello in one short sentence.') } }
                app.send()
            }

        then:
            RealLamps.eventually(Duration.ofMinutes(4)) {
                genie().phase() == Genie.Phase.READY && genie().transcript().entries().any { it.kind() == Entry.Kind.GENIE }
            }
            !genie().transcript().entries().find { it.kind() == Entry.Kind.GENIE }.text().isBlank()
    }

    private Genie genie() { state.get().find(id).orElseThrow() }

    private static void onTheWindowsThread(Closure action) {
        SwingUtilities.invokeAndWait { action() }
    }
}
