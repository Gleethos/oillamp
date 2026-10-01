package gui

import dev.gui.desktop.RfbConnection
import dev.gui.genie.GenieRunner
import dev.gui.genie.LampLighter
import dev.gui.model.Conversations
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.Settings
import dev.lamp.Lamp
import oillamp.RealLamps
import oillamp.Spike
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 *  A genie, the way Genies runs one, on this machine: a real lamp lit through {@code dev.lamp},
 *  a real pi in RPC mode inside it, real files through ~/outbox and ~/inbox, and the real
 *  wayvnc desktop.
 *
 *  <p>This is Genies without its window: the {@link GenieRunner} the window drives, fed the same
 *  settings and key. The model answers only when {@code EDENAI_API_KEY} holds a real Eden AI
 *  key; without one, the scenarios that need an answer are skipped, and everything else runs.
 *  The key never appears in this spike's output.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class RunningARealGenieSpec extends Specification {

    static final String KEY = System.getenv('EDENAI_API_KEY') ?: ''
    static final String MODEL = System.getenv('OILLAMP_SPIKE_MODEL') ?: 'mistral/mistral-small-latest'

    @Shared Path lamp = RealLamps.newLampPath('genie')
    @Shared Genie genie = Genie.named('Spike')
    @Shared List<String> activities = new CopyOnWriteArrayList<>()
    @Shared GenieRunner runner = new GenieRunner(lamp, new LampLighter(), { UnaryOperator<Genie> change ->
        synchronized (RunningARealGenieSpec) {
            genie = change.apply(genie)
            if (activities.isEmpty() || activities.last() != genie.activity()) activities << genie.activity()
        }
    } as Consumer)

    def cleanupSpec() {
        runner.sleepAndWait(180)
        RealLamps.remove(lamp)
    }

    def 'A genie wakes in a real lamp, ready to be asked'() {
        reportInfo """
            The runner lights the lamp through dev.lamp, with the model service and key from the
            settings, makes ~/outbox and ~/inbox, and writes the genie's instructions and model
            into pi's files in its home. The lamp's session starts pi at the first message. On a
            first start the sandbox image is built, which takes minutes; the genie's status says
            so while it happens.
        """
        when:
            runner.wake('Spike', Settings.defaults().withModel(MODEL), KEY ?: 'sk-no-real-key')

        then:
            RealLamps.eventually(RealLamps.FIRST_START) { genie.phase() != Genie.Phase.WAKING }
            genie.phase() == Genie.Phase.READY
            activities.size() > 2

        and: 'a new genie has no conversation yet, and pi said so rather than failing'
            genie.transcript().entries().every { !it.isFailed() }
    }

    def 'The genie\'s desktop can be watched through the socket the lamp names'() {
        reportInfo """
            Genies draws the desktop in its own window through a small VNC client. Here it talks
            to the real wayvnc: no password, 32-bit pixels, and a first picture of the whole
            screen.
        """
        given:
            var painted = new CopyOnWriteArrayList<String>()

        when:
            var desktop = RfbConnection.open(runner.desktop().get(), new RfbConnection.Listener() {
                void resized(int width, int height) {}
                void painted(int x, int y, int width, int height) { painted << "${width}x${height}".toString() }
                void ended(Optional<String> reason) {}
            })

        then:
            desktop.screen().width >= 640
            RealLamps.eventually(Duration.ofSeconds(20)) { !painted.isEmpty() }

        cleanup:
            desktop?.close()
    }

    def 'A file the user gives lands in the genie\'s inbox'() {
        reportInfo """
            The user picks a file on the host; it travels as the input of a command over the
            lamp's ssh command and lands in ~/inbox, owned by the agent. Nothing of the host is
            mounted into the sandbox for it.
        """
        given:
            var gift = Files.writeString(Files.createTempFile('genie-gift', '.txt'), 'a gift for the genie')

        when:
            runner.give(gift)

        then:
            RealLamps.eventually(Duration.ofSeconds(30)) {
                genie.transcript().entries().any { it.kind() == Entry.Kind.FILE && it.title() == gift.fileName.toString() }
            }
            genie.transcript().entries().every { !it.isFailed() }

        cleanup:
            Files.deleteIfExists(gift)
    }

    def 'The genie goes between the branches of a conversation, read from its home'() {
        reportInfo '''
            pi keeps a conversation as a tree, in a file in the genie's home. Here a conversation
            that forked at its second question is put there, as pi 0.87 would have written it, and
            the chat is sent to one branch and then the other. The chat shows the way to each, and
            the tree marks where it is. No model is needed.
        '''
        given: 'a conversation whose second question was asked twice'
            var home = Lamp.agentHome(lamp).get()
            var sessions = Files.createDirectories(home.resolve(Conversations.DIRECTORY))
            Files.writeString(sessions.resolve('forked.jsonl'), [
                    '{"type":"session","version":3,"id":"forked","timestamp":"2026-09-29T10:00:00.000Z","cwd":"/home/agent"}',
                    said('q1', null, 'user', 'Plan a trip'), said('a1', 'q1', 'assistant', 'Where to?'),
                    said('q2', 'a1', 'user', 'By train'), said('a2', 'q2', 'assistant', 'Trains it is.'),
                    said('q2b', 'a1', 'user', 'By bike'), said('a2b', 'q2b', 'assistant', 'Bikes it is.')].join('\n') + '\n')
            var file = Conversations.DIRECTORY + '/forked.jsonl'

        when:
            runner.lookAtConversations()
            RealLamps.eventually(Duration.ofSeconds(20)) { genie.conversations().find('forked').isPresent() }
            runner.goTo(file, 'a2')

        then: 'the chat shows the train branch'
            RealLamps.eventually(Duration.ofSeconds(30)) {
                genie.transcript().entries()*.text() == ['Plan a trip', 'Where to?', 'By train', 'Trains it is.']
            }
            genie.conversations().here() == new Conversations.Here(file, 'a2')

        when:
            runner.goTo(file, 'a2b')

        then: 'and then the bike branch, which the tree marks'
            RealLamps.eventually(Duration.ofSeconds(30)) {
                genie.transcript().entries()*.text() == ['Plan a trip', 'Where to?', 'By bike', 'Bikes it is.']
            }
            RealLamps.eventually(Duration.ofSeconds(20)) { genie.conversations().herePath().toList() == ['forked', 'q2b'] }
            genie.transcript().entries().every { !it.isFailed() }

        cleanup: 'back to a conversation of its own, for the scenarios after this one'
            runner.startAfresh()
            RealLamps.eventually(Duration.ofSeconds(30)) { genie.conversations().here().file() != file }
    }

    /** One entry of a pi session file, a message, shaped as pi 0.87 writes it. */
    private static String said(String id, String parent, String role, String text) {
        var message = role == 'user' ? [role: 'user', content: [[type: 'text', text: text]], timestamp: 1]
                : [role: 'assistant', content: [[type: 'text', text: text]], api: 'x', provider: 'x', model: 'x',
                   usage: [input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
                           cost: [input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0]],
                   stopReason: 'stop', timestamp: 2]
        groovy.json.JsonOutput.toJson([type: 'message', id: id, parentId: parent,
                                       timestamp: '2026-09-29T10:00:01.000Z', message: message])
    }

    @Requires({ KEY })
    def 'The genie answers a message, and hands over the file it was asked for'() {
        reportInfo """
            The whole point, for real: a message in, the model's answer out through oillamp's
            relay, a tool call that writes a file into ~/outbox, and the app announcing the file
            once the genie is done. Needs a real key; the model is small and the file tiny.
        """
        when:
            synchronized (RunningARealGenieSpec) { genie = genie.withDraft('x').send() }
            runner.say('Use your bash tool to run exactly this command: echo hello > ~/outbox/hello.txt ' +
                       '— then reply with the single word done.')

        then:
            RealLamps.eventually(Duration.ofMinutes(4)) {
                genie.phase() == Genie.Phase.READY && genie.handouts().any { it.name() == 'hello.txt' }
            }
            genie.transcript().entries().any { it.kind() == Entry.Kind.TOOL }
            genie.transcript().entries().any { it.kind() == Entry.Kind.FILE && it.title() == 'hello.txt' }
            genie.tokens() > 0
    }

    @Requires({ KEY })
    def 'The file the genie handed over is saved where the user chose'() {
        reportInfo """
            The user clicks Save and picks a place; the file's bytes come out of the sandbox as a
            command's output and are written there, and nowhere else.
        """
        given:
            var target = Files.createTempDirectory('genie-saved').resolve('hello.txt')

        when:
            runner.save('hello.txt', target)

        then:
            RealLamps.eventually(Duration.ofSeconds(30)) { Files.exists(target) }
            Files.readString(target).strip() == 'hello'

        cleanup:
            Files.deleteIfExists(target)
            Files.deleteIfExists(target.parent)
    }

    @Requires({ KEY })
    def 'A genie woken again remembers the conversation'() {
        reportInfo """
            pi keeps the conversation in the genie's home, inside the lamp. After sleeping, a
            woken genie is started with --continue, and the chat shows what was said before.
        """
        when:
            runner.sleep()
            RealLamps.eventually(Duration.ofMinutes(3)) { genie.phase() == Genie.Phase.ASLEEP }
            runner.wake('Spike', Settings.defaults().withModel(MODEL), KEY)

        then:
            RealLamps.eventually(RealLamps.LATER_START + Duration.ofMinutes(1)) { genie.phase() == Genie.Phase.READY }
            RealLamps.eventually(Duration.ofSeconds(30)) {
                genie.transcript().entries().any { it.kind() == Entry.Kind.YOU && it.text().contains('echo hello') }
            }
    }
}
