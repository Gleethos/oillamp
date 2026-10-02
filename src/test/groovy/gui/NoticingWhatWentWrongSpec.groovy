package gui

import dev.gui.ErrorLog
import dev.gui.LoggedErrors
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import dev.gui.model.Trouble
import spock.lang.Specification
import sprouts.Tuple
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 *  What Genies does with an exception no code caught: a bug of its own, or a library failing.
 *
 *  <p>Such an exception must neither vanish nor stop the app. The {@link ErrorLog} handles every
 *  one, on any thread: it writes it to {@code errors.log} in Genies' data folder, and hands it to
 *  the window, which tells the user.
 */
class NoticingWhatWentWrongSpec extends Specification {

    @TempDir Path tmp

    def 'An exception no code caught is written to the error log, with its stack trace, and shown'() {
        reportInfo """
            A thread that dies of an exception used to say so only on the error output, which
            nobody sees when Genies is started from the desktop. Now the whole stack trace is in
            errors.log, where a user can find it and send it along, and the window hears of it.
        """
        given:
            var log = new ErrorLog(tmp.resolve('genies/errors.log'))
            var shown = new CopyOnWriteArrayList<Trouble>()
            log.showIn { shown << it }

        when: 'a thread dies of an exception'
            var thread = Thread.ofVirtual().name('genie').uncaughtExceptionHandler(log)
                               .start { throw new IllegalStateException('a bug in Genies') }
            thread.join()

        then: 'the log holds it, with where it happened and the stack trace'
            var text = Files.readString(tmp.resolve('genies/errors.log'))
            text.contains('in thread genie')
            text.contains('java.lang.IllegalStateException: a bug in Genies')
            text.contains('\tat ')

        and: 'the window is told, in a line, and with all the details'
            shown.size() == 1
            shown[0].where() == 'thread genie'
            shown[0].what() == 'java.lang.IllegalStateException: a bug in Genies'
            shown[0].details().contains('\tat ')
    }

    def 'The same exception again and again is written in full once, and shown once'() {
        reportInfo """
            A bug in painting throws each time the window is painted, many times a second. Shown
            each time, it would bury the app; written in full each time, it would fill the disk.
            So a repeat of the last exception is one line in the log, and not shown again.
            A different one is written and shown as usual.
        """
        given:
            var log = new ErrorLog(tmp.resolve('errors.log'))
            var shown = new CopyOnWriteArrayList<Trouble>()
            log.showIn { shown << it }

        when: 'the same exception happens three times, then another'
            3.times { log.record('thread AWT-EventQueue-0', paintingFailure()) }
            log.record('thread genie', new IOException('the disk is full'))

        then:
            var text = Files.readString(tmp.resolve('errors.log'))
            text.count('java.lang.NullPointerException: no colour') == 3
            text.count('again, in thread AWT-EventQueue-0') == 2
            shown*.what() == ['java.lang.NullPointerException: no colour', 'java.io.IOException: the disk is full']
    }

    def 'A log grown too large is set aside, and a new one begun'() {
        reportInfo """
            Genies may run for weeks. Past about a megabyte, errors.log becomes errors.log.1,
            replacing an older one, so the log never takes more than twice that.
        """
        given:
            var file = tmp.resolve('errors.log')
            Files.writeString(file, 'x' * (ErrorLog.MOST_BYTES + 1))
            var log = new ErrorLog(file)

        when:
            log.record('thread genie', new IllegalStateException('a bug in Genies'))

        then:
            Files.size(tmp.resolve('errors.log.1')) == ErrorLog.MOST_BYTES + 1
            Files.readString(file).contains('a bug in Genies')
            Files.size(file) < ErrorLog.MOST_BYTES / 10
    }

    def 'Where the log cannot be written, recording still never throws'() {
        reportInfo """
            The error log is the last place an exception goes. If it threw itself, a second
            failure would hide the first, or kill the thread that was only reporting. Here the
            data folder is a file, so no log can be made there; the exception still reaches the
            window, and the error output.
        """
        given:
            Files.writeString(tmp.resolve('genies'), 'not a folder')
            var log = new ErrorLog(tmp.resolve('genies/errors.log'))
            var shown = new CopyOnWriteArrayList<Trouble>()
            log.showIn { shown << it }

        when:
            log.record('thread genie', new IllegalStateException('a bug in Genies'))

        then:
            noExceptionThrown()
            shown.size() == 1
    }

    def 'An exception SwingTree caught in an event handler, and logged, reaches the error log too'() {
        reportInfo """
            SwingTree catches an exception thrown by a button's handler, say, and logs it through
            slf4j rather than letting it through. Genies gives slf4j a provider of its own, which
            hands such errors to the error log, so they are written and shown like any other.
            Warnings only go to the error output, and the rest nowhere.
        """
        given: 'the error log handles what no code caught, as when Genies runs'
            var log = new ErrorLog(tmp.resolve('errors.log'))
            var shown = new CopyOnWriteArrayList<Trouble>()
            log.showIn { shown << it }
            var before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(log)

        when: 'SwingTree logs an error from a click handler, and a warning'
            var logger = new LoggedErrors().loggerFactory.getLogger('swingtree.UIForAnySwing')
            logger.error('Error in mouse click event action handler!', new IllegalStateException('a bug in Genies'))
            logger.warn('Something odd, but harmless')

        then: 'the error is shown, saying who logged it'
            shown.size() == 1
            shown[0].where() == 'swingtree.UIForAnySwing, which logged: Error in mouse click event action handler!'
            shown[0].what() == 'java.lang.IllegalStateException: a bug in Genies'
            Files.readString(tmp.resolve('errors.log')).contains('a bug in Genies')

        cleanup:
            Thread.setDefaultUncaughtExceptionHandler(before)
    }

    def 'The window says what went wrong until the user has looked, and keeps no more than twenty'() {
        reportInfo """
            The list of genies says when something went wrong, until the user clicks it to see
            what. What they saw is then off the window; one that happened while they looked
            stays, so it is not missed. Should a bug strike many times, only the latest twenty
            are kept in the window; the error log has every one.
        """
        given:
            var state = GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.empty())

        when: 'twenty-five different things go wrong'
            (1..25).each { state = state.withTrouble(trouble("failure $it")) }

        then: 'the latest twenty are on the window, oldest first'
            state.troubles().size() == GeniesState.MOST_TROUBLES
            state.troubles().first().what() == 'failure 6'
            state.troubles().last().what() == 'failure 25'

        when: 'the user looks at them, and meanwhile one more goes wrong'
            var seen = state.troubles()
            state = state.withTrouble(trouble('failure 26')).withoutTroubles(seen)

        then: 'only that one is left'
            state.troubles().toList()*.what() == ['failure 26']
    }

    private static Trouble trouble(String what) {
        new Trouble(Instant.now(), 'thread genie', what, what + '\n\tat somewhere')
    }

    private static NullPointerException paintingFailure() {
        try {
            throw new NullPointerException('no colour')
        } catch (NullPointerException thrown) {
            return thrown
        }
    }
}
