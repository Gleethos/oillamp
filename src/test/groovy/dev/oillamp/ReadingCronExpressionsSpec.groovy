package dev.oillamp

import spock.lang.Specification
import spock.lang.Timeout

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 *  How a repeating job's cron expression is read: when it runs next, and how close together its
 *  runs are. Both decide what a job may do, so the awkward cases are written out here.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReadingCronExpressionsSpec extends Specification {

    static final ZoneId UTC = ZoneId.of('UTC')

    /** A Tuesday, 2026-09-22. */
    static final Instant NOW = Instant.parse('2026-09-22T10:00:00Z')

    def 'A day that is both a day of the month and a weekday counts when either one matches'() {
        reportInfo """
            In cron, "0 0 13 * 5" means midnight on the 13th and also midnight on every Friday,
            not only on a Friday that is the 13th.
        """
        given:
            var cron = ((Result.Ok<CronExpression>) CronExpression.parse('0 0 13 * 5')).value()

        when:
            var first = cron.nextAfter(NOW, UTC).get()
            var second = cron.nextAfter(first, UTC).get()

        then: 'Friday the 25th comes before the 13th of October'
            first == Instant.parse('2026-09-25T00:00:00Z')
            second == Instant.parse('2026-10-02T00:00:00Z')
    }

    def 'A day field that starts with a star is not restricted, so both fields must match'() {
        reportInfo """
            "0 0 */2 * 1" is every other day of the month, and of those only Mondays: in cron a
            field written with a star does not count as restricted, so the day of the month and
            the day of the week must both match.
        """
        given:
            var cron = ((Result.Ok<CronExpression>) CronExpression.parse('0 0 */2 * 1')).value()

        when: 'the Mondays after Tuesday 22 September that fall on an odd day of the month'
            var first = cron.nextAfter(NOW, UTC).get()

        then: 'Monday 5 October is odd, and the 28th and 30th are not Mondays'
            first == Instant.parse('2026-10-05T00:00:00Z')
    }

    def 'The shortest gap is found even where the short runs are weeks away'() {
        reportInfo """
            "*/5 0 1 * *" runs every five minutes, but only just after midnight on the first of a
            month. A search of the next week from the 22nd would see no two runs at all, and the
            agent could be allowed a job that runs every five minutes whenever the month turns.
        """
        given:
            var cron = ((Result.Ok<CronExpression>) CronExpression.parse('*/5 0 1 * *')).value()

        expect:
            cron.shortestGap(NOW, NOW.plus(Duration.ofDays(14)), UTC) == Optional.of(Duration.ofMinutes(5))
            cron.shortestGap(NOW, NOW.plus(Duration.ofDays(5)), UTC).isEmpty()
    }
}
