package gui

import dev.gui.model.JobDraft
import dev.gui.model.Recurrence
import dev.gui.model.Schedule
import dev.gui.model.Timeline
import spock.lang.Specification
import sprouts.Tuple

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 *  What a genie's schedule page shows and lets the user write, as pure values.
 *
 *  <p>The page offers a job's timing in words (every weekday at nine, some days of the week) and
 *  turns them into the cron expressions oillamp reads; a job read back from oillamp is shown in
 *  the same words. The timeline lays the jobs' times and their past runs out day by day around
 *  now. All of it is worked out here without a window or a lamp.
 */
class PlanningAGeniesWeekSpec extends Specification {

    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')
    /** A Tuesday afternoon. */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 14, 32)

    static Instant at(LocalDateTime time) { time.atZone(BERLIN).toInstant() }

    def 'Each way of repeating becomes a cron expression, and reads back as the same words'() {
        reportInfo """
            The user never has to write cron. What they pick becomes an expression oillamp reads,
            and a job read from oillamp, whoever wrote it, is shown in words again. An expression
            that is none of the page's shapes is kept as it is, and still said as well as can be.
        """
        expect:
            recurrence.cron() == cron
            recurrence.describe() == words
            Recurrence.of(cron) == recurrence

        where:
            recurrence                                                                           || cron             | words
            new Recurrence.Hourly(15)                                                            || '15 * * * *'     | 'Every hour at 15 past'
            new Recurrence.Daily(LocalTime.of(7, 5))                                             || '5 7 * * *'      | 'Every day at 07:05'
            new Recurrence.Weekdays(LocalTime.of(9, 0))                                          || '0 9 * * 1-5'    | 'Every weekday at 09:00'
            new Recurrence.Weekly(Tuple.of(DayOfWeek, DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalTime.of(8, 30)) || '30 8 * * 1,4' | 'Every Monday and Thursday at 08:30'
            new Recurrence.Custom('0 9 1 * *')                                                   || '0 9 1 * *'      | 'On the cron schedule “0 9 1 * *”'
    }

    def 'Expressions written elsewhere are recognised when they mean one of the page\'s shapes'() {
        reportInfo """
            The agent, or someone at the command line, may write the same schedule another way.
            Picking every day of the week is every day; Monday to Friday is weekdays; the
            shorthands oillamp knows mean what they say.
        """
        expect:
            Recurrence.of('0 9 * * mon-fri') == new Recurrence.Weekdays(LocalTime.of(9, 0))
            Recurrence.of('0 9 * * 0-6') == new Recurrence.Daily(LocalTime.of(9, 0))
            Recurrence.of('@hourly') == new Recurrence.Hourly(0)
            Recurrence.of('*/30 * * * *').describe() == 'Every 30 minutes'
    }

    def 'The editor says what stands in the way of adding a job, and what the job comes to'() {
        reportInfo """
            Nothing the user types is refused while they type. The editor says in one sentence
            what still stands in the way, and, once the timing can be read, when the job will
            run, so they can see they asked for what they meant before saving.
        """
        given:
            var draft = JobDraft.fresh(NOW)

        expect: 'a new job runs once, a little later today, and needs its task written'
            !draft.repeats()
            draft.once() == Optional.of(LocalDateTime.of(2026, 9, 29, 16, 0))
            draft.problem(NOW) == Optional.of('Write what the genie should do.')
            draft.summary(NOW) == 'Once, today at 16:00 — in 1 hour.'

        and: 'a time is read the ways people write it, and one that has passed is refused'
            draft.withTime('9.30').clock() == Optional.of(LocalTime.of(9, 30))
            draft.withTime('0930').clock() == Optional.of(LocalTime.of(9, 30))
            draft.withTime('25:00').clock().isEmpty()
            draft.withPrompt('x').withTime('14:00').problem(NOW) == Optional.of('That time has passed. Pick a later one.')

        and: 'a weekly job needs a day, and says when it first runs and until when'
            var weekly = draft.withPrompt('Triage').withRepeats(true).withRepeat(JobDraft.Repeat.WEEKLY).withTime('8:30')
            weekly.withDays(Tuple.of(DayOfWeek)).problem(NOW) == Optional.of('Pick at least one day.')
            with(weekly.withDays(Tuple.of(DayOfWeek, DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
                       .withEndsOn(Optional.of(NOW.toLocalDate().plusDays(20)))) {
                problem(NOW).isEmpty()
                recurrence().get().cron() == '30 8 * * 1,4'
                summary(NOW) == 'Every Monday and Thursday at 08:30, first on Thursday 1 October at 08:30, until Monday 19 October.'
                ends() == Optional.of(LocalDateTime.of(2026, 10, 20, 0, 0))
            }
    }

    def 'Changing a job opens the editor with the job as it was'() {
        reportInfo """
            A job is changed in the same editor it was written in, with every field as the job
            has it, so the user changes only what they meant to.
        """
        given:
            var job = new Schedule.Job('job-3', Optional.of(Recurrence.of('0 9 * * 1-5')), Optional.empty(),
                    Optional.empty(), Tuple.of(Instant), 'Check the build', false,
                    Optional.of(at(LocalDateTime.of(2026, 10, 10, 0, 0))), true)

        when:
            var draft = JobDraft.of(job, BERLIN, NOW)

        then:
            draft.replaces() == Optional.of('job-3')
            draft.prompt() == 'Check the build'
            draft.repeats()
            draft.repeat() == JobDraft.Repeat.WEEKDAYS
            draft.time() == '09:00'
            draft.endsOn() == Optional.of(NOW.toLocalDate().withDayOfMonth(9).plusMonths(1))
    }

    def 'The timeline lays the week out day by day: what ran, now, and what will run'() {
        reportInfo """
            Before now come the last runs, each with how it ended; then now; then the times each
            job runs over the coming week, under the day they fall on. A job that runs many times
            a day folds into one line for that day, so an hourly job does not bury the rest.
        """
        given:
            var daily = new Schedule.Job('job-1', Optional.of(Recurrence.of('0 9 * * *')), Optional.empty(),
                    Optional.of(at(NOW.plusDays(1).withHour(9).withMinute(0))),
                    Tuple.of(Instant, at(NOW.plusDays(1).withHour(9).withMinute(0))), 'Check the build', false, Optional.empty(), true)
            var hourly = new Schedule.Job('job-2', Optional.of(Recurrence.of('15 * * * *')), Optional.empty(),
                    Optional.of(at(NOW.withHour(15).withMinute(15))),
                    Tuple.of(Instant, (15..19).collect { at(NOW.withHour(it).withMinute(15)) } as Instant[]),
                    'Watch the queue', true, Optional.empty(), true)
            var ran = new Schedule.Run('run-4', 'job-1', at(NOW.minusDays(1).withHour(9).withMinute(3)),
                    Schedule.Outcome.FINISHED, 'All green.\nNothing to fix.', Optional.of('c-4'))
            var schedule = Schedule.unread(BERLIN).readAs(false, BERLIN, Tuple.of(Schedule.Job, daily, hourly), Tuple.of(Schedule.Run, ran))

        when:
            var timeline = Timeline.of(schedule, at(NOW))

        then: 'yesterday, today and tomorrow'
            timeline.days()*.label() == ['Yesterday', 'Today', 'Tomorrow']

        and: 'yesterday\'s run, with how it ended and what the genie said'
            with(timeline.days()[0].moments().first()) {
                kind() == Timeline.Kind.RAN
                clock() == '09:03'
                title() == 'Check the build'
                detail() == 'Done · All green.'
                conversation() == Optional.of('c-4')
            }

        and: 'today, now and then the hourly job, folded'
            timeline.days()[1].moments()*.kind() == [Timeline.Kind.NOW, Timeline.Kind.REPEATING]
            with(timeline.days()[1].moments()[1]) {
                clock() == '5×'
                detail() == 'Every hour at 15 past · 15:15, 16:15, 17:15, 18:15, 19:15'
            }

        and: 'tomorrow, the daily job'
            timeline.days()[2].moments()*.clock() == ['09:00']

        when: 'the user picks the hourly job'
            var picked = Timeline.of(schedule.pick('job-2'), at(NOW))

        then: 'its times stand forward, and the rest step back'
            picked.days()[1].moments()[1].emphasis() == Timeline.Emphasis.PICKED
            picked.days()[2].moments()[0].emphasis() == Timeline.Emphasis.FADED
    }

    def 'A day with many times of one job says how many, and from when to when, rather than one time'() {
        reportInfo """
            A line that stands for several times cannot show one of them where a time goes: that
            reads as if the job ran only then. It shows how many times instead, and names them,
            or, when there are more than fit in a line, gives the first and the last.
        """
        given: 'a job every 15 minutes, all of tomorrow'
            var tomorrow = NOW.toLocalDate().plusDays(1).atStartOfDay()
            var often = new Schedule.Job('job-1', Optional.of(Recurrence.of('*/15 * * * *')), Optional.empty(),
                    Optional.of(at(tomorrow)), Tuple.of(Instant, (0..<96).collect { at(tomorrow.plusMinutes(15 * it)) } as Instant[]),
                    'Watch the queue', false, Optional.empty(), true)
            var schedule = Schedule.unread(BERLIN).readAs(false, BERLIN, Tuple.of(Schedule.Job, often), Tuple.of(Schedule.Run))

        expect:
            with(Timeline.of(schedule, at(NOW)).days()[1].moments().first()) {
                kind() == Timeline.Kind.REPEATING
                clock() == '96×'
                detail() == 'Every 15 minutes · 96 times, 00:00 to 23:45'
            }
    }

    def 'A job whose time came while the genie slept shows as due, now'() {
        reportInfo """
            A job only runs while its genie is awake. One whose time passed while it slept is not
            lost: it runs once as soon as the genie wakes, and the timeline says so at now.
        """
        given:
            var missed = new Schedule.Job('job-1', Optional.of(Recurrence.of('0 9 * * *')), Optional.empty(),
                    Optional.of(at(NOW.withHour(9).withMinute(0))), Tuple.of(Instant), 'Check the build', false, Optional.empty(), true)
            var schedule = Schedule.unread(BERLIN).readAs(false, BERLIN, Tuple.of(Schedule.Job, missed), Tuple.of(Schedule.Run))

        expect:
            Timeline.of(schedule, at(NOW)).days()[0].moments()*.kind() == [Timeline.Kind.NOW, Timeline.Kind.DUE]
    }
}
