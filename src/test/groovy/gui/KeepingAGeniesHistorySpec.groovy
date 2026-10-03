package gui

import dev.gui.genie.GenieRunner
import dev.gui.genie.LampLighter
import dev.gui.genie.Lighter
import dev.gui.model.Genie
import dev.gui.model.History
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampEvent
import oillamp.ScenarioHost
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 *  A genie's history, read from its page, saved to by hand, and gone back to.
 *
 *  <p>oillamp saves a genie's home in its lamp whenever the genie wakes, answers and sleeps, and
 *  whenever the user saves by hand. Each save is a moment the genie can go back to: its files,
 *  and its conversations with them, become what they were then. Here the lamp is oillamp's real
 *  engine, in this JVM, on a simulated machine whose clock the scenario moves, with a stand-in pi.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesHistorySpec extends Specification {

    static final Instant NOW = Instant.parse('2026-09-29T12:32:00Z')
    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')

    @TempDir Path tmp
    ScenarioHost sandbox
    Path lamp
    Genie genie = Genie.named('Jafar')
    GenieRunner runner

    def setup() {
        sandbox = new ScenarioHost(tmp)
        lamp = sandbox.lampPath('jafar')
        sandbox.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin')
                            .windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt -> 'You said: ' + prompt } }
        runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
            synchronized (this) { genie = change.apply(genie) }
        } as Consumer)
    }

    def cleanup() {
        runner?.sleepAndWait(30)
    }

    def 'Waking, answering and sleeping are moments of the history, and a save by hand adds one'() {
        reportInfo """
            The history page lists the moments oillamp saved the genie's home at, newest first,
            in words: it woke, it answered a question (titled by the conversation, with what it
            said last). oillamp saves only what changed, so going to sleep straight after an
            answer adds no moment. The user can save by hand too, awake or asleep, with a few
            words to remember the moment by; the new moment is picked, so it stands out. When
            nothing changed since the newest moment, oillamp saves nothing, and the page says so
            rather than leaving the user to wonder.
        """
        given: 'a genie that woke, answered a question and went to sleep'
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            runner.sendMessage('Plot the sales figures')
            waitUntil { genie.phase() == Genie.Phase.READY && genie.conversations().chatCount() == 1 }
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }

        when:
            runner.history().read()

        then: 'the moments are there, newest first; going to sleep changed nothing, so it made none'
            waitUntil { genie.history().read() }
            var kinds = genie.history().moments()*.kind()
            kinds.first() == History.Kind.RAN
            kinds.last() == History.Kind.WOKE

        and: 'the answer is titled by its conversation, with what the genie said'
            var answered = genie.history().moments().first()
            var now = LocalDateTime.ofInstant(NOW, BERLIN)
            genie.history().title(answered, genie, now) == 'Answered: Plot the sales figures'
            answered.detail() == 'You said: Plot the sales figures'

        when: 'the user saves with nothing changed'
            runner.history().save('before the big refactor')

        then: 'nothing is saved, the newest moment is picked, and the page says why'
            waitUntil { genie.history().busy().isEmpty() && !genie.history().note().isEmpty() }
            genie.history().picked() == genie.history().moments().first().id()
            genie.history().moments().first().kind() == History.Kind.RAN

        when: 'a file changes in the genie\'s home, and the user saves again'
            Files.writeString(Lamp.agentHome(lamp).orElseThrow().resolve('plan.md'), 'refactor the parser')
            runner.history().save('before the big refactor')

        then: 'the save is the newest moment, picked, and titled by what the user wrote'
            waitUntil { genie.history().moments().first().kind() == History.Kind.SAVED }
            var saved = genie.history().moments().first()
            genie.history().title(saved, genie, now) == 'before the big refactor'
            waitUntil { genie.history().picked() == saved.id() }
            genie.history().note().isEmpty()
            genie.history().problem().isEmpty()
    }

    def 'Going back brings the files and the conversations back, puts an awake genie to sleep, and can be undone'() {
        reportInfo """
            Going back to a moment makes the genie's home what it was then, with its
            conversations, which pi keeps there. oillamp refuses while the sandbox runs, so an
            awake genie is put to sleep first. Before going back, oillamp saves the home as it is,
            if that changed since the newest moment, so nothing is lost: the history then offers
            to undo, which goes back to the moment that was the newest.
            An answer or a save afterwards ends that offer, since undoing would then lose them.
        """
        given: 'an awake genie that answered once, then got a file, then answered again'
            var home = { Lamp.agentHome(lamp).orElseThrow() }
            var questionsAsked = { genie.conversations().all().first().steps().count { it.asked() } }
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            runner.history().read()
            runner.sendMessage('Write a parser')
            waitUntil { genie.phase() == Genie.Phase.READY && genie.conversations().chatCount() == 1 }
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(20))) }
            Files.writeString(home().resolve('plan.md'), 'refactor the parser')
            runner.sendMessage('Now refactor it')
            waitUntil { genie.phase() == Genie.Phase.READY && questionsAsked() == 2 }
            waitUntil { genie.history().moments().count { it.kind() == History.Kind.RAN } == 2 }

        when: 'the user goes back to just after the first answer'
            var afterTheFirstAnswer = genie.history().moments().findAll { it.kind() == History.Kind.RAN }.last()
            runner.goBackTo(afterTheFirstAnswer)

        then: 'the genie sleeps, and its home is as it was then'
            waitUntil { genie.phase() == Genie.Phase.ASLEEP && genie.history().busy().isEmpty() }
            genie.history().problem() == ''
            !Files.exists(home().resolve('plan.md'))

        and: 'its conversation holds the first question only'
            waitUntil { questionsAsked() == 1 }

        and: 'the history says it went back, and offers to undo that'
            waitUntil { genie.history().moments().first().kind() == History.Kind.WENT_BACK }
            var wentBack = genie.history().moments().first()
            genie.history().wentBackTo(wentBack).get().id() == afterTheFirstAnswer.id()
            var undo = genie.history().undo()
            undo.isPresent()

        when: 'the user undoes it'
            runner.goBackTo(undo.get())

        then: 'the file and the second question are back'
            waitUntil { questionsAsked() == 2 && genie.history().busy().isEmpty() }
            Files.readString(home().resolve('plan.md')) == 'refactor the parser'

        when: 'the genie wakes, and answers again'
            runner.wake('Jafar', Settings.defaults(), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            runner.sendMessage('Thanks')
            waitUntil { genie.phase() == Genie.Phase.READY && questionsAsked() == 3 }

        then: 'there is nothing to undo any more'
            waitUntil { genie.history().moments().first().kind() == History.Kind.RAN }
            genie.history().undo().isEmpty()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /// oillamp's engine in this JVM; commands run in the sandbox run on this machine, in the
    /// genie's home.
    private Lighter lighter() {
        var real = new LampLighter(sandbox.launcher)
        return new Lighter() {
            Lighter.Lit light(Path directory, Settings settings, String key, Consumer<String> progress, Consumer<LampEvent> events) {
                var lit = real.light(directory, settings, key, progress, events)
                new Lighter.Lit() {
                    Process exec(String... command) {
                        var home = Lamp.agentHome(lamp).orElseThrow()
                        var builder = new ProcessBuilder(command as List<String>)
                        builder.environment().put('HOME', home.toString())
                        builder.directory(home.toFile())
                        builder.start()
                    }
                    dev.gui.desktop.Desktop desktop() { lit.desktop() }
                    LampEvent.Run send(Lamp.Question question) { lit.send(question) }
                    void cancel(String run) { lit.cancel(run) }
                    void close() { lit.close() }
                }
            }
            Lamp.Starting unlit(Path directory) { real.unlit(directory) }
        }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; the genie is ${genie.phase()} (${genie.activity()}), " +
                                 "its history ${genie.history()}" as Object)
    }
}
