package gui

import dev.gui.genie.GenieRunner
import dev.gui.genie.Lighter
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.Settings
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 *  A genie's life: its lamp is lit, its harness started in the sandbox, messages go in, answers
 *  and files come out, and it goes to sleep again.
 *
 *  <p>{@link GenieRunner} does all of that through a lamp's two ways in: commands run over ssh,
 *  and the desktop socket. Here the lamp is a stand-in whose commands run on this machine, in a
 *  home directory of its own, and pi is a small shell script that answers the way pi's RPC mode
 *  does. So everything real about the runner is real here: the processes, the pipes, the
 *  threads, the files. Only the sandbox around them is missing; a spike adds it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class KeepingAGenieAliveSpec extends Specification {

    @TempDir Path tmp
    Path home
    Genie genie = Genie.named('Jafar')
    final List<List<String>> commands = new CopyOnWriteArrayList<>()
    final List<String> closed = new CopyOnWriteArrayList<>()
    String pi = STAND_IN_PI
    GenieRunner runner

    def setup() {
        home = Files.createDirectories(tmp.resolve('home'))
        runner = new GenieRunner(tmp.resolve('lamp'), lighter(), { UnaryOperator<Genie> change ->
            synchronized (this) { genie = change.apply(genie) }
        } as Consumer)
    }

    def cleanup() {
        runner?.sleepAndWait(10)
    }

    def 'Waking a genie lights its lamp, starts its harness, and brings back its conversation'() {
        reportInfo """
            The genie wakes in three steps: its lamp is lit, which is oillamp's work; ~/outbox
            and ~/inbox are made; and pi is started over the lamp's ssh command, in RPC mode, on
            the model from the settings, continuing its last conversation, and told how the app
            around it works. pi is then asked for the conversation so far, which the chat shows.
        """
        when:
            runner.wake('Jafar', Settings.defaults().withModel('mistral/mistral-small-latest'), 'sk-key')

        then:
            waitUntil { genie.phase() == Genie.Phase.READY }
            Files.isDirectory(home.resolve('outbox'))
            Files.isDirectory(home.resolve('inbox'))

        and: 'pi, as the harness, on the chosen model, continuing, with the genie\'s instructions'
            var harness = commands.find { it.first() == 'pi' }
            harness.subList(0, 8) == ['pi', '--mode', 'rpc', '--provider', 'edenai', '--model',
                                      'mistral/mistral-small-latest', '--continue']
            harness[8] == '--append-system-prompt'
            harness[9].startsWith('You are Jafar, a genie')
            harness[9].contains('~/outbox')

        and: 'the conversation it had before'
            waitUntil { genie.transcript().entries()*.text() == ['remember me?', 'I do.'] }
    }

    def 'A genie is awake only once its conversation is back, so nothing the user sends is overwritten'() {
        reportInfo """
            pi takes a few seconds to start, and answers commands in turn. Were the genie shown
            as awake before pi had sent the conversation so far, the user could send a message
            that the late conversation would then replace in the chat. So waking ends when the
            conversation is back. Here pi takes a second to send it.
        """
        given:
            pi = STAND_IN_PI.replace('*get_messages*)', '*get_messages*) sleep 1;')

        when:
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() != Genie.Phase.WAKING }

        then: 'the moment it is awake, the conversation is there'
            genie.phase() == Genie.Phase.READY
            genie.transcript().entries()*.text() == ['remember me?', 'I do.']
    }

    def 'A message goes to the genie, its answer comes back as it is written, and a file it made is announced'() {
        reportInfo """
            The whole round trip of a chat message, through real pipes: the message is written
            to pi's input as a prompt, the answer comes back in pieces and complete, a tool call
            is shown, and when pi says it is done the app looks into ~/outbox and announces the
            file the genie put there.
        """
        given:
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }

        when:
            synchronized (this) { genie = genie.withDraft('draw me a map').send() }
            runner.say('draw me a map', false)

        then:
            waitUntil { genie.phase() == Genie.Phase.READY && genie.handouts().size() == 1 }
            var entries = genie.transcript().entries()
            entries.find { it.kind() == Entry.Kind.TOOL }.text() == 'draw-map > ~/outbox/map.svg'
            entries.find { it.kind() == Entry.Kind.GENIE && it.text() == 'Your map is in ~/outbox/map.svg.' }
            entries.last().kind() == Entry.Kind.FILE
            entries.last().title() == 'map.svg'
            genie.tokens() == 77
    }

    def 'Files go both ways, and only where the user chose on the host'() {
        reportInfo """
            A file the genie handed over is copied to the place the user picked in a file
            dialog, never anywhere else on the host. A file the user gives the genie lands in its
            ~/inbox under its own name, and the chat notes it. Both travel through the lamp's ssh
            command as a command's output and input; nothing is shared between host and sandbox.
        """
        given:
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            Files.writeString(home.resolve('outbox/poem.txt'), 'roses are red')
            var gift = Files.writeString(tmp.resolve('recipe.md'), '# soup')

        when:
            runner.save('poem.txt', tmp.resolve('saved-poem.txt'))
            runner.give(gift)

        then:
            waitUntil { Files.exists(tmp.resolve('saved-poem.txt')) && Files.exists(home.resolve('inbox/recipe.md')) }
            Files.readString(tmp.resolve('saved-poem.txt')) == 'roses are red'
            Files.readString(home.resolve('inbox/recipe.md')) == '# soup'
            waitUntil { genie.transcript().entries().any { it.kind() == Entry.Kind.FILE && it.title() == 'recipe.md' } }
    }

    def 'A lamp that cannot be lit leaves a broken genie that says why'() {
        reportInfo """
            The first wake of a genie builds the sandbox, and much can go wrong on the way: no
            podman, no disk space, no network. The lamp's reason is shown as the genie's status
            and in its chat.
        """
        given:
            runner = new GenieRunner(tmp.resolve('lamp'), { dir, settings, key, progress ->
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

    def 'When the harness dies, the genie says so, and its lamp is put out'() {
        reportInfo """
            pi may crash, or the sandbox may stop under it. The genie is then broken, with the
            last words pi wrote to its error output as the reason, and its lamp is put out, so
            that waking it again starts cleanly.
        """
        given:
            pi = 'read line; echo "pi: model catalog failed" >&2; exit 3'

        when:
            runner.wake('Jafar', Settings.defaults(), 'sk-key')

        then:
            waitUntil { genie.phase() == Genie.Phase.BROKEN }
            genie.activity().contains('exit 3')
            genie.activity().contains('pi: model catalog failed')
            waitUntil { closed.size() == 1 }
    }

    def 'Sleeping ends the harness and puts the lamp out, and the conversation stays'() {
        reportInfo """
            A sleeping genie costs nothing: no container, no process. Its home stays on disk,
            with pi's record of the conversation in it, which is why it remembers when it wakes.
        """
        given:
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }

        when:
            runner.sleep()

        then:
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            closed.size() == 1
            !runner.isAwake()
            !genie.transcript().isEmpty()
    }

    // ─── the stand-in lamp and harness ─────────────────────────────────────────────────────

    /**
     *  Answers the way pi's RPC mode does: the conversation when asked for it, and for a prompt
     *  a tool call, an answer in two pieces, a file in the outbox, and "settled".
     */
    static final String STAND_IN_PI = '''
        while IFS= read -r line; do
          case "$line" in
            *get_messages*)
              echo '{"type":"response","command":"get_messages","success":true,"data":{"messages":[{"role":"user","content":"remember me?"},{"role":"assistant","content":[{"type":"text","text":"I do."}]}]}}' ;;
            *prompt*)
              echo '{"type":"response","command":"prompt","success":true}'
              echo '{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"draw-map > ~/outbox/map.svg"}}'
              echo '<svg/>' > "$HOME/outbox/map.svg"
              echo '{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","isError":false,"result":{"content":[{"type":"text","text":""}]}}'
              echo '{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Your map is "}}'
              echo '{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"in ~/outbox/map.svg."}}'
              echo '{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Your map is in ~/outbox/map.svg."}],"stopReason":"stop","usage":{"totalTokens":77}}}'
              echo '{"type":"agent_settled"}' ;;
          esac
        done
    '''

    /** A lamp whose commands run here, as bash, in a home directory of their own. */
    private Lighter lighter() {
        return { Path directory, Settings settings, String key, Consumer<String> progress ->
            progress.accept('making the sandbox')
            new Lighter.Lit() {
                Process exec(String... command) {
                    commands << (command as List<String>)
                    var local = command[0] == 'pi' ? ['bash', '-c', pi] : (command as List<String>)
                    var builder = new ProcessBuilder(local)
                    builder.environment().put('HOME', home.toString())
                    builder.start()
                }
                Path desktop() { tmp.resolve('vnc.sock') }
                void close() { closed << 'closed' }
            }
        } as Lighter
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; the genie is ${genie.phase()} (${genie.activity()}), " +
                                 "saying ${genie.transcript().entries()*.text()}" as Object)
    }
}
