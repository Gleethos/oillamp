package gui

import dev.gui.genie.GenieRunner
import dev.gui.genie.LampLighter
import dev.gui.genie.Lighter
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.JobDraft
import dev.gui.model.Schedule
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampEvent
import oillamp.ScenarioHost
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 *  A genie's schedule, read and changed from its page, and the jobs on it waking the genie.
 *
 *  <p>The schedule lives in the genie's lamp, so it is read and changed whether the genie is
 *  awake or asleep, all through the Lamp API. Genies lights every lamp with scheduling on, so a
 *  job wakes the genie while it is awake; the page follows the run as it happens, and lists it
 *  afterwards with what the genie said. Here the lamp is oillamp's real engine, in this JVM, on
 *  a simulated machine whose clock the scenario moves, with a stand-in pi.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesScheduleSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-09-29T12:32:00Z')
    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')

    @TempDir Path tmp
    ScenarioHost sandbox
    Path lamp
    Genie genie = Genie.named('Jafar')
    final CountDownLatch release = new CountDownLatch(1)
    GenieRunner runner

    def setup() {
        sandbox = new ScenarioHost(tmp)
        lamp = sandbox.lampPath('jafar')
        sandbox.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin')
                            .windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt ->
            if (prompt.contains('Tidy the downloads')) {
                release.await()
                return 'I tidied the downloads folder.'
            }
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

    def 'A sleeping genie\'s schedule is read and changed through its lamp'() {
        reportInfo """
            Nothing about a schedule needs the genie's sandbox: the jobs are kept in its lamp. So
            the page reads them, and adds, switches off, pauses and removes them, with the genie
            asleep. A job written in the editor becomes the cron expression oillamp reads; once
            it is added, the editor closes and the new job is picked, so its times stand out.
        """
        given: 'a genie that has woken once, and sleeps'
            awake()
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            var keeper = runner.schedule()

        when:
            keeper.read()

        then:
            waitUntil { genie.schedule().read() }
            genie.schedule().jobs().isEmpty()
            genie.schedule().zone() == BERLIN

        when: 'the user writes a job for every weekday at nine'
            var draft = JobDraft.fresh(local()).withPrompt('  Check the build  ').withRepeats(true)
                    .withRepeat(JobDraft.Repeat.WEEKDAYS).withTime('9:00')
            change { it.withDraft(Optional.of(draft)) }
            keeper.save(draft, local(), BERLIN)

        then: 'it is on the schedule, as it was meant, with its times over the week'
            waitUntil { genie.schedule().jobs().size() == 1 && genie.schedule().draft().isEmpty() }
            with(genie.schedule().jobs().first()) {
                id() == 'job-1'
                repeats().get().describe() == 'Every weekday at 09:00'
                prompt() == 'Check the build'
                upcoming().size() == 5
            }
            genie.schedule().picked() == 'job-1'

        when:
            keeper.switchJob('job-1', false)

        then:
            waitUntil { !genie.schedule().jobs().first().enabled() }

        when:
            keeper.pause(true)

        then:
            waitUntil { genie.schedule().paused() }

        when:
            keeper.remove('job-1')

        then:
            waitUntil { genie.schedule().jobs().isEmpty() }
    }

    def 'Changing a job replaces it, and one that cannot be added leaves the old one and says why'() {
        reportInfo """
            oillamp has no way to edit a job in place, so a changed job is added anew and the old
            one removed, in that order: if oillamp refuses the new one, the old one stays, and the
            editor stays open with oillamp's reason.
        """
        given:
            awake()
            var keeper = runner.schedule()
            keeper.read()
            var daily = JobDraft.fresh(local()).withPrompt('Check the build').withRepeats(true).withTime('09:00')
            keeper.save(daily, local(), BERLIN)
            waitUntil { genie.schedule().jobs().size() == 1 }

        when: 'the user changes it to a cron expression oillamp cannot read'
            var wrong = daily.withReplaces(Optional.of('job-1')).withRepeat(JobDraft.Repeat.CUSTOM).withCron('0 25 * * *')
            change { it.withDraft(Optional.of(wrong)) }
            keeper.save(wrong, local(), BERLIN)

        then:
            waitUntil { !genie.schedule().busy() && !genie.schedule().problem().isEmpty() }
            genie.schedule().problem().contains('the hour 25 is not between 0 and 23')
            genie.schedule().draft().isPresent()
            genie.schedule().jobs()*.id() == ['job-1']

        when: 'they put it right'
            var right = wrong.withCron('0 7 * * *')
            change { it.withDraft(Optional.of(right)) }
            keeper.save(right, local(), BERLIN)

        then:
            waitUntil { genie.schedule().jobs()*.id() == ['job-2'] && genie.schedule().draft().isEmpty() }
            genie.schedule().jobs().first().repeats().get().describe() == 'Every day at 07:00'
    }

    def 'A job that wakes the genie shows as running, then as a run with what the genie said'() {
        reportInfo """
            While a job's run goes on, the timeline shows it at now, with a way to stop it. Once
            it ends, it is among the runs, with how it ended, what the genie said last, and the
            conversation it had, which the chat can open, and which is in the tree of scheduled
            runs rather than among the user's conversations. The chat itself was left as it was
            while the job ran.
        """
        given: 'a sleeping genie with a job for five minutes from now, and its schedule on show'
            awake()
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            var tidying = Lamp.at(lamp).launchedBy(sandbox.launcher).once('in 5m', 'Tidy the downloads folder')
            runner.schedule().read()

        when: 'it wakes ten minutes later, when the job is due'
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            awake()

        then: 'the page shows it running'
            waitUntil { genie.schedule().running().filter { it.job() == tidying.id() }.isPresent() }

        when:
            release.countDown()

        then: 'it ended, and is among the runs'
            waitUntil { genie.schedule().running().isEmpty() && genie.schedule().runs().size() == 1 }
            with(genie.schedule().runs().first()) {
                job() == 'job-1'
                outcome() == Schedule.Outcome.FINISHED
                said() == 'I tidied the downloads folder.'
                conversation().isPresent()
            }

        and: 'the chat was left alone'
            genie.transcript().isEmpty()

        and: 'its conversation is among the scheduled runs, not the user\'s conversations'
            waitUntil { genie.conversations().jobCount() == 1 }
            genie.conversations().jobRuns()*.id() == [genie.schedule().runs().first().conversation().get()]
            genie.conversations().chatCount() == 0

        when: 'the user opens the run\'s conversation'
            runner.openConversation(genie.schedule().runs().first().conversation().get())

        then:
            waitUntil { genie.transcript().entries().any { it.kind() == Entry.Kind.GENIE && it.text() == 'I tidied the downloads folder.' } }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private LocalDateTime local() {
        LocalDateTime.ofInstant(NOW, BERLIN)
    }

    private void change(Closure<Schedule> change) {
        synchronized (this) { genie = genie.withSchedule(change(genie.schedule())) }
    }

    private void awake() {
        runner.wake('Jafar', Settings.defaults(), 'sk-key')
        waitUntil { genie.phase() == Genie.Phase.READY }
    }

    /** oillamp's engine in this JVM, through the Lamp API; files move in the genie's home here. */
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
                    Path desktop() { lit.desktop() }
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
                                 "its schedule ${genie.schedule()}" as Object)
    }
}
