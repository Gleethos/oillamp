package gui

import dev.gui.Genies
import dev.gui.genie.GenieRunner
import dev.gui.genie.LampLighter
import dev.gui.genie.Lighter
import dev.gui.model.Conversations
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampEvent
import groovy.json.JsonSlurper
import oillamp.ScenarioHost
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 *  A genie's life: its lamp is lit, messages go to its agent and answers come back, it moves
 *  around its conversations, files go both ways, and it goes to sleep again.
 *
 *  <p>{@link GenieRunner} does all of it through the Lamp API. Here the lamp is oillamp's real
 *  engine, run in this JVM on a simulated machine, with a stand-in pi that answers the way pi
 *  does and keeps its conversations in files the way pi does. Only the commands that move files
 *  in and out, which reach into the sandbox over ssh, run on this machine instead, in the genie's
 *  home.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGenieAliveSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost host
    Path lamp
    Genie genie = Genie.named('Jafar')
    final List<String> prompts = new CopyOnWriteArrayList<>()
    final List<String> closed = new CopyOnWriteArrayList<>()
    final CountDownLatch release = new CountDownLatch(1)
    GenieRunner runner

    def setup() {
        host = new ScenarioHost(tmp)
        lamp = host.lampPath('jafar')
        host.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt ->
            prompts << prompt
            if (prompt.startsWith('draw')) {
                Files.writeString(home().resolve('outbox/map.svg'), '<svg/>')
                return '⚙ bash: draw-map > ~/outbox/map.svg\nYour map is in ~/outbox/map.svg.'
            }
            if (prompt.startsWith('take your time')) release.await()
            'You said: ' + prompt
        } }
        runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
            synchronized (this) { genie = change.apply(genie) }
        } as Consumer)
    }

    def cleanup() {
        release.countDown()
        runner?.sleepAndWait(30)
    }

    def 'Waking a genie lights its lamp, and tells its pi who it is and which model to use'() {
        reportInfo """
            The lamp's session runs the genie's pi, so Genies cannot give it options on a command
            line. It writes pi's own files in the genie's home instead: the genie's instructions,
            which pi adds to its system prompt, and the model from the settings, keeping whatever
            else pi's settings hold, such as its installed packages.
        """
        when:
            runner.wake('Jafar', Settings.defaults().withModel('mistral/mistral-large-latest'), 'sk-key')

        then:
            waitUntil { genie.phase() == Genie.Phase.READY }
            Files.isDirectory(home().resolve('outbox'))
            Files.readString(home().resolve('.pi/agent/APPEND_SYSTEM.md')).startsWith('You are Jafar, a genie')
            var settings = piSettings()
            settings.defaultProvider == 'edenai'
            settings.defaultModel == 'mistral/mistral-large-latest'

        when: 'it wakes again with another model, and pi has packages of its own'
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            Files.writeString(home().resolve('.pi/agent/settings.json'),
                    '{"packages":["git:github.com/edenai/pi-edenai"],"defaultModel":"old"}')
            runner.wake('Jafar', Settings.defaults().withModel('mistral/mistral-small-latest'), 'sk-key')

        then:
            waitUntil { genie.phase() == Genie.Phase.READY }
            piSettings().packages == ['git:github.com/edenai/pi-edenai']
            piSettings().defaultModel == 'mistral/mistral-small-latest'
    }

    def 'A message goes to the genie, its answer comes back as it is written, and a file it made is announced'() {
        reportInfo """
            The whole round trip of a chat message through the Lamp API: the message goes to the
            lamp's session, which runs it; the answer comes back piece by piece as the run's
            progress, a tool call is shown, and when the run ends the app looks into ~/outbox and
            announces the file the genie put there. The message reaches pi as it was typed.
        """
        given:
            awake()

        when:
            say('draw me a map')

        then:
            waitUntil { genie.phase() == Genie.Phase.READY && genie.handouts().size() == 1 }
            prompts == ['draw me a map']
            var entries = genie.transcript().entries()
            entries.find { it.kind() == Entry.Kind.TOOL }.text() == 'draw-map > ~/outbox/map.svg'
            entries.find { it.kind() == Entry.Kind.GENIE }.text() == 'Your map is in ~/outbox/map.svg.'
            entries.last().kind() == Entry.Kind.FILE
            entries.last().title() == 'map.svg'
    }

    def 'The next message continues the conversation, and the tree shows where the chat is'() {
        reportInfo """
            A chat goes on in one conversation: each message goes after the last answer. The
            tree of conversations marks the conversation and the entry the chat is at.
        """
        given:
            awake()

        when:
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            say('and again')
            waitUntil { genie.phase() == Genie.Phase.READY && said().size() == 4 }

        then:
            said() == ['hello', 'You said: hello', 'and again', 'You said: and again']
            var conversations = Lamp.conversations(lamp)
            conversations.size() == 1
            waitUntil { genie.conversations().all()*.id() == [conversations.first().id()] }
            genie.conversations().here() == new Conversations.Here(conversations.first().file(), conversations.first().leaf().orElseThrow())
    }

    def 'Going back to an earlier answer and asking from there forks the conversation'() {
        reportInfo """
            The user clicks an earlier answer in the tree. The chat shows the conversation up to
            there, and the next message is asked after that answer: the conversation forks, and
            the later questions stay on their own branch.
        """
        given:
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            say('and again')
            waitUntil { genie.phase() == Genie.Phase.READY && said().size() == 4 }
            var conversation = Lamp.conversations(lamp).first()
            var firstAnswer = conversation.entries().find { it.text() == 'You said: hello' }

        when:
            runner.goTo(conversation.file(), firstAnswer.id())
            waitUntil { said() == ['hello', 'You said: hello'] }
            say('something else')

        then:
            waitUntil { genie.phase() == Genie.Phase.READY && said().size() == 4 }
            said() == ['hello', 'You said: hello', 'something else', 'You said: something else']
            Lamp.conversation(lamp, conversation.id()).orElseThrow().children(firstAnswer.id())*.text() == ['and again', 'something else']
    }

    def 'A question asked differently leaves the old one as a branch'() {
        reportInfo """
            The user edits one of their questions. The new one is asked instead of it; the old
            question and its answer stay in the conversation as a branch of their own.
        """
        given:
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            var question = Lamp.conversations(lamp).first().entries().find { it.text() == 'hello' }

        when:
            synchronized (this) { genie = genie.askInstead(question.id(), 'good morning') }
            runner.askInstead(question.id(), 'good morning')

        then:
            waitUntil { genie.phase() == Genie.Phase.READY && said() == ['good morning', 'You said: good morning'] }
            var tree = Lamp.conversations(lamp).first()
            tree.entries().findAll { it.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT }*.text() == ['hello', 'good morning']
    }

    def 'A sleeping genie\'s conversations can be read and gone through without waking it'() {
        reportInfo """
            The conversations are files in the lamp, so a sleeping genie's tree is full, and the
            chat can show any of them. The genie only wakes when the user wants to say something.
        """
        given: 'two conversations, one of them asked from a terminal'
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            assert host.oillamp.run('ask', lamp.toString(), 'from a terminal').succeeded()
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            var other = Lamp.conversations(lamp).find { it.title() == 'from a terminal' }

        when:
            runner.lookAtConversations()
            runner.goTo(other.file(), '')

        then:
            waitUntil { said() == ['from a terminal', 'You said: from a terminal'] }
            genie.phase() == Genie.Phase.ASLEEP
            genie.conversations().all().size() == 2
    }

    def 'While the genie answers, the user can read another conversation and come back to the answer'() {
        reportInfo """
            An answer can take long. Meanwhile the user may read their other conversations: the
            chat shows the one they pick, and the one the genie answers in is put aside, still
            growing. Nothing can be sent from elsewhere, as it would only wait for that answer.
            Going back shows the question and the answer as far as it got, and follows it on.
        """
        given: 'a conversation of the chat, and one asked from a terminal'
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            assert host.oillamp.run('ask', lamp.toString(), 'from a terminal').succeeded()
            var mine = Lamp.conversations(lamp).find { it.title() == 'hello' }
            var other = Lamp.conversations(lamp).find { it.title() == 'from a terminal' }

        when: 'the genie takes its time over the next question, and the user goes to the other'
            say('take your time')
            waitUntil { prompts.contains('take your time') }
            runner.goTo(other.file(), '')

        then: 'the chat shows the other, the genie still works, and nothing can be sent from here'
            waitUntil { said() == ['from a terminal', 'You said: from a terminal'] }
            genie.phase() == Genie.Phase.WORKING
            !genie.withDraft('one more thing').canSend()

        when: 'the user goes back'
            runner.goTo(mine.file(), '')

        then: 'the question is there, and the answer arrives in it'
            waitUntil { said() == ['hello', 'You said: hello', 'take your time'] }
            genie.conversations().aside().isEmpty()

        when:
            release.countDown()

        then:
            waitUntil { genie.phase() == Genie.Phase.READY && said().size() == 4 }
            said().last() == 'You said: take your time'
            genie.withDraft('one more thing').canSend()
    }

    def 'An answer that ends while the user reads elsewhere leaves them where they are'() {
        reportInfo """
            The genie finishes while the user reads another conversation. The chat does not
            jump back: it stays where the user is, and the genie is ready. The answer is in its
            conversation when the user goes there.
        """
        given:
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            assert host.oillamp.run('ask', lamp.toString(), 'from a terminal').succeeded()
            var mine = Lamp.conversations(lamp).find { it.title() == 'hello' }
            var other = Lamp.conversations(lamp).find { it.title() == 'from a terminal' }
            say('take your time')
            waitUntil { prompts.contains('take your time') }
            runner.goTo(other.file(), '')
            waitUntil { said() == ['from a terminal', 'You said: from a terminal'] }

        when:
            release.countDown()

        then: 'the chat stays on the other conversation'
            waitUntil { genie.phase() == Genie.Phase.READY && genie.conversations().aside().isEmpty() }
            said() == ['from a terminal', 'You said: from a terminal']
            genie.conversations().here().file() == other.file()

        when: 'the user goes back'
            runner.goTo(mine.file(), '')

        then: 'the answer is there'
            waitUntil { said() == ['hello', 'You said: hello', 'take your time', 'You said: take your time'] }
    }

    def 'A new conversation the genie answers in has a row to come back to'() {
        reportInfo """
            pi writes a new conversation to disk only once there is an answer in it, so while
            the genie answers the first question of one, the user's tree has no row for it. When
            the user goes elsewhere meanwhile, the tree shows a "New conversation" row at the
            top, which leads back to it.
        """
        given:
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            var first = Lamp.conversations(lamp).first()
            runner.startAfresh()
            waitUntil { genie.transcript().isEmpty() }

        when: 'the user asks in the new conversation, and goes back to the first one meanwhile'
            say('take your time')
            waitUntil { prompts.contains('take your time') }
            runner.goTo(first.file(), '')
            waitUntil { said() == ['hello', 'You said: hello'] }

        then: 'the new one has a row of its own at the top'
            var rows = genie.conversations().chats()
            rows.first().id() == Conversations.ASIDE
            rows.first().title() == 'New conversation'
            genie.conversations().fileOf(Conversations.ASIDE) == Optional.of('')

        when: 'the user goes there'
            runner.goTo(genie.conversations().fileOf(Conversations.ASIDE).orElseThrow(), '')

        then:
            waitUntil { said() == ['take your time'] }

        when:
            release.countDown()

        then: 'the answer arrives, and the chat is in the conversation pi saved it in'
            waitUntil { genie.phase() == Genie.Phase.READY && said() == ['take your time', 'You said: take your time'] }
            waitUntil { genie.conversations().current().map { it.title() } == Optional.of('take your time') }
    }

    def 'Stopping the genie stops the run it works on, and it is ready again'() {
        reportInfo """
            The stop button cancels the run that answers the chat. The genie is then ready for
            the next message.
        """
        given:
            awake()

        when:
            say('take your time')
            waitUntil { prompts == ['take your time'] }
            runner.stop()

        then:
            waitUntil { genie.phase() == Genie.Phase.READY }
    }

    def 'Deleting the conversation the chat shows starts a new one'() {
        reportInfo """
            A conversation is deleted for good. The chat that showed it empties, and the next
            message starts a new conversation.
        """
        given:
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }
            var conversation = Lamp.conversations(lamp).first()

        when:
            runner.forget(conversation.file())

        then:
            waitUntil { genie.conversations().all().isEmpty() && genie.transcript().isEmpty() }
            genie.conversations().here() == Conversations.Here.UNKNOWN
            Lamp.conversations(lamp).isEmpty()
    }

    def 'A run the chat did not ask for changes only the tree'() {
        reportInfo """
            The lamp's agent also answers others: a scheduled job, or a question asked from a
            terminal. Their runs are in conversations of their own. The chat stays as it is, and
            the tree gains the new conversation.
        """
        given:
            awake()
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY }

        when:
            assert host.oillamp.run('ask', lamp.toString(), 'from a terminal').succeeded()

        then:
            waitUntil { genie.conversations().all().size() == 2 }
            said() == ['hello', 'You said: hello']
    }

    def 'Files go both ways, and only where the user chose on the host'() {
        reportInfo """
            A file the genie handed over is copied to the place the user picked in a file
            dialog, never anywhere else on the host. A file the user gives the genie lands in its
            ~/inbox under its own name, and the chat notes it.
        """
        given:
            awake()
            Files.writeString(home().resolve('outbox/poem.txt'), 'roses are red')
            var gift = Files.writeString(tmp.resolve('recipe.md'), '# soup')

        when:
            runner.save('poem.txt', tmp.resolve('saved-poem.txt'))
            runner.give(gift)

        then:
            waitUntil { Files.exists(tmp.resolve('saved-poem.txt')) && Files.exists(home().resolve('inbox/recipe.md')) }
            Files.readString(tmp.resolve('saved-poem.txt')) == 'roses are red'
            Files.readString(home().resolve('inbox/recipe.md')) == '# soup'
            waitUntil { genie.transcript().entries().any { it.kind() == Entry.Kind.FILE && it.title() == 'recipe.md' } }
    }

    def 'A lamp that cannot be lit leaves a broken genie that says why'() {
        reportInfo """
            The first wake of a genie builds the sandbox, and much can go wrong on the way: no
            podman, no disk space, no network. The lamp's reason is shown as the genie's status
            and in its chat.
        """
        given:
            runner = new GenieRunner(lamp, { dir, settings, key, progress, events ->
                progress.accept('checking this computer')
                throw new IOException('podman is not installed')
            } as Lighter, { UnaryOperator<Genie> change -> synchronized (this) { genie = change.apply(genie) } } as Consumer)

        when:
            runner.wake('Jafar', Settings.defaults(), 'sk-key')

        then:
            waitUntil { genie.phase() == Genie.Phase.BROKEN }
            genie.activity() == 'podman is not installed'
            genie.transcript().entries().last().isFailed()
    }

    def 'When the lamp stops under the genie, the genie says so'() {
        reportInfo """
            The sandbox can end without the app asking, for example with oillamp stop from a
            terminal. The genie is then broken, with the reason, and waking it starts afresh.
        """
        given:
            awake()

        when:
            assert host.oillamp.run('stop', lamp.toString()).succeeded()

        then:
            waitUntil { genie.phase() == Genie.Phase.BROKEN }
            genie.activity().contains('stopped')
            !runner.isAwake()
    }

    def 'A genie left running when the app closed is awake again when it opens, where it was'() {
        reportInfo """
            Closing Genies, the user may keep a genie running, so that it keeps to its schedule.
            When Genies opens again, it finds the genie's lamp still lit and joins it. The genie
            is awake at once, without waking it again, and shows the conversation it was in. It
            answers as before, and putting it to sleep ends its lamp, as for one Genies lit.
        """
        given: 'a genie that was asked something, and left running as the app closed'
            awake()
            say('hello there')
            waitUntil { genie.phase() == Genie.Phase.READY && said().contains('You said: hello there') }
            var file = genie.conversations().here().file()
            runner.leaveRunningAndWait(30)

        when: 'the app opens again'
            genie = Genie.named('Jafar')
            runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
                synchronized (this) { genie = change.apply(genie) }
            } as Consumer)
            runner.rejoin(file, '')

        then: 'the genie is awake, in the conversation it was in'
            waitUntil { genie.phase() == Genie.Phase.READY && said() == ['hello there', 'You said: hello there'] }
            runner.isAwake()

        when: 'the user asks something more'
            say('and now?')

        then: 'it answers'
            waitUntil { genie.phase() == Genie.Phase.READY && said().last() == 'You said: and now?' }

        when: 'it is put to sleep'
            runner.sleepAndWait(30)

        then: 'its lamp is out'
            !new LampLighter(host.launcher).isLit(lamp)
    }

    def 'A genie left running while it answers is found answering, and the answer comes in'() {
        reportInfo """
            The user may close Genies while the genie works on their question. Kept running, the
            genie goes on with it. When Genies opens again, the chat shows that conversation with
            the question, and the genie working on it, as if Genies had never closed. The answer
            arrives there, once.
        """
        given: 'a genie working on a question, left running as the app closed'
            awake()
            say('take your time')
            waitUntil { prompts.contains('take your time') }
            runner.leaveRunningAndWait(30)

        when: 'the app opens again'
            genie = Genie.named('Jafar')
            runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
                synchronized (this) { genie = change.apply(genie) }
            } as Consumer)
            runner.rejoin('', '')

        then: 'the genie is working on the question'
            waitUntil { genie.phase() == Genie.Phase.WORKING && said() == ['take your time'] }

        when: 'it is done'
            release.countDown()

        then: 'the answer is in the chat, and the genie waits for the user'
            waitUntil { genie.phase() == Genie.Phase.READY && said() == ['take your time', 'You said: take your time'] }
    }

    def 'A genie whose lamp is out stays asleep when the app opens'() {
        reportInfo """
            Genies looks for a lamp left running under every genie it keeps. Most are out, and
            those genies stay as they were: asleep, with nothing lit.
        """
        when:
            runner.rejoin('', '')
            Thread.sleep(500)

        then:
            genie.phase() == Genie.Phase.ASLEEP
            !runner.isAwake()
    }

    def 'Closing Genies with genies awake asks what becomes of them, by name'() {
        reportInfo """
            Closing Genies used to put every genie to sleep. Now, when any is awake, Genies asks
            first: put them to sleep, keep them running in the background, or not close after
            all. The question names the genies, and says what keeping them running means.
        """
        expect:
            Genies.awakeQuestion(['Jafar']) == 'Jafar is awake. Put it to sleep, or keep it running in the background?\n' +
                    'Kept running, it finishes what it is doing and keeps to its schedule, and Genies finds it again when it opens.'
            Genies.awakeQuestion(['Jafar', 'Iago', 'Abu']).startsWith('Jafar, Iago and Abu are awake. Put them to sleep')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private void awake() {
        runner.wake('Jafar', Settings.defaults(), 'sk-key')
        waitUntil { genie.phase() == Genie.Phase.READY }
    }

    private void say(String text) {
        synchronized (this) { genie = genie.withDraft(text).send() }
        runner.say(text)
    }

    private Path home() { Lamp.agentHome(lamp).orElseThrow() }

    /** The questions and answers in the chat, leaving out the model's thinking and the tools. */
    private List<String> said() {
        genie.transcript().entries().findAll { it.kind() in [Entry.Kind.YOU, Entry.Kind.GENIE] }*.text()
    }

    private Map piSettings() {
        new JsonSlurper().parseText(Files.readString(home().resolve('.pi/agent/settings.json'))) as Map
    }

    /**
     *  oillamp's engine in this JVM, through the Lamp API, except that a command run in the
     *  sandbox runs here, in the genie's home: the simulated machine has no sandbox to run it in.
     */
    private Lighter lighter() {
        var real = new LampLighter(host.launcher)
        var light = { Path directory, Settings settings, String key, Consumer<String> progress, Consumer<LampEvent> events ->
            here(real.light(directory, settings, key, progress, events))
        }
        var join = { Path directory, Consumer<LampEvent> events ->
            real.join(directory, events).map { here(it) }
        }
        return [light: light, join: join, isLit: { Path directory -> real.isLit(directory) },
                unlit: { Path directory -> real.unlit(directory) }] as Lighter
    }

    /** `lit`, except that its commands run here, in the genie's home. */
    private Lighter.Lit here(Lighter.Lit lit) {
            new Lighter.Lit() {
                Process exec(String... command) {
                    var builder = new ProcessBuilder(command as List<String>)
                    builder.environment().put('HOME', home().toString())
                    builder.directory(home().toFile())
                    builder.start()
                }
                Path desktop() { lit.desktop() }
                LampEvent.Run send(Lamp.Question question) { lit.send(question) }
                void cancel(String run) { lit.cancel(run) }
                void close() { closed << 'closed'; lit.close() }
                void leaveRunning() { lit.leaveRunning() }
            }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; the genie is ${genie.phase()} (${genie.activity()}), " +
                                 "saying ${said()}" as Object)
    }
}
