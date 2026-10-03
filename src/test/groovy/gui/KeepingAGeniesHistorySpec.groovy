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
import sprouts.Tuple
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

    def 'Waking and answering are moments of the history, and a save by hand adds one'() {
        reportInfo """
            The history page lists the moments oillamp saved the genie's home at, newest first,
            in words: it woke, it answered a question (titled by the conversation, with what it
            said last). oillamp saves only what changed, so going to sleep straight after an
            answer adds no moment. The user can save by hand too, awake or asleep, with a few
            words to remember the moment by; the new moment is picked, so it stands out. When
            nothing changed since the newest moment, oillamp saves nothing, and the page says so
            rather than leaving the user to wonder. The chat says what came of a save too, since
            the user can save from there, through the genie's menu.
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
            genie.transcript().entries().last().text() == genie.history().note()

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

        and: 'the chat says so'
            var told = genie.transcript().entries().last().text()
            told == 'Saved as a moment of the history: before the big refactor. The history page brings the genie back to it.'
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

    def 'A long history shows its newest forty moments, day by day, and waking does not end the offer to undo'() {
        reportInfo """
            A genie that is used a lot gathers a moment for every answer, so the page shows the
            newest forty, grouped by the day they were saved on, and a button shows the rest.
            Right after going back, an awake genie is woken again, which may save a moment of
            its own; that must not take away the offer to undo, while an answer does.
        """
        given: 'fifty answers, one an hour, the newest first'
            var moment = { int hoursAgo, History.Kind kind, String message ->
                new History.Moment(Integer.toHexString(1000 + hoursAgo) + '0' * 36, NOW.minus(Duration.ofHours(hoursAgo)),
                                   kind, message, '', '', Optional.empty(), '')
            }
            var answers = (0..<50).collect { moment(it, History.Kind.RAN, '') }
            var history = History.UNREAD.readAs(Tuple.of(History.Moment, answers as History.Moment[]))

        expect: 'forty are shown, on the days they were saved on, the newest day first'
            history.hidden() == 10
            var days = history.days(BERLIN)
            days*.moments()*.size().sum() == 40
            days.first().date() == LocalDateTime.ofInstant(NOW, BERLIN).toLocalDate()
            days.first().date() > days.last().date()

        and: 'every one, once asked for'
            history.withEarlier(true).hidden() == 0
            history.withEarlier(true).days(BERLIN)*.moments()*.size().sum() == 50

        when: 'the genie went back to the third answer, then woke again'
            var third = answers[2]
            var before = moment(-1, History.Kind.BEFORE_GOING_BACK, 'before restoring ' + answers[0].id().take(8))
            var wentBack = moment(-1, History.Kind.WENT_BACK, 'back to ' + third.id().take(8) + ' (run, then)')
            var woke = moment(-2, History.Kind.WOKE, '')
            var afterwards = History.UNREAD.readAs(Tuple.of(History.Moment, ([woke, wentBack, before] + answers) as History.Moment[]))

        then: 'it offers to undo, and knows where it went back to'
            afterwards.undo().get() == before
            afterwards.wentBackTo(wentBack).get() == third

        when: 'the genie answers once more'
            var newest = moment(-3, History.Kind.RAN, '')
            var answeredAgain = History.UNREAD.readAs(afterwards.moments().addAt(0, newest))

        then:
            answeredAgain.undo().isEmpty()
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
