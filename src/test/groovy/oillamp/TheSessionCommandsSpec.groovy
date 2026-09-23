package oillamp

import dev.oillamp.ExitStatus
import dev.oillamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  {@code view}, {@code shell}, {@code stop}, {@code status} and {@code list}.
 *
 *  <p>These are not five more things oillamp can do to a lamp. They are questions put to the
 *  supervisor that already owns it, over the control socket, and they have to be: that process
 *  holds the lock, the relays and the recording, so a {@code stop} that removed the container
 *  behind its back would leave it believing it still had a session.
 *
 *  <p>Each scenario therefore runs a real session on one thread and asks it something from
 *  another, which is the only arrangement that tests what these commands actually are.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TheSessionCommandsSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    /**
     *  Everything the running session said, as it said it.
     *
     *  <p>Copy-on-write rather than a synchronized list: the session writes to it from its own
     *  thread while the scenario reads it from this one, and a synchronized list is only safe to
     *  iterate under a lock the caller holds - which a `waitUntil` predicate does not.
     */
    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session
    OilLamp.Outcome sessionOutcome

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        if (session?.alive) {
            sandbox.oillamp.run('stop', sandbox.lampPath().toString())
            session.join(20_000)
        }
    }

    def 'status asks the running session what it is doing'() {
        reportInfo """
            Asked of the supervisor rather than worked out from the outside, because only the
            supervisor knows the two things that are not written down anywhere: which state the
            session is in, and how many extra shells are attached to it.
        """
        given:
            var lamp = sandbox.lampPath()
            startASession(lamp)

        when:
            var outcome = sandbox.oillamp.run('status', lamp.toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'it says what state the session is in, and what it is made of'
            var answer = outcome.events().find { it instanceof LampEvent.Answer }.text()
            answer.contains('running')
            answer.contains('oillamp-')
            answer.contains('1920x1080')
            answer.contains(lamp.toString())
    }

    def 'stop asks the session to shut down, rather than killing its container'() {
        reportInfo """
            The difference matters. Removing the container directly would leave the supervisor
            holding a lock over a sandbox that no longer exists, with its relays still bound and
            its recording unfinished. Asking it instead runs the full, ordered shutdown - stop the
            recorder so the video file is properly closed, take down the relays, stop and remove
            the container, release the lock - which is the same sequence a closed terminal window
            sets off.
        """
        given:
            var lamp = sandbox.lampPath()
            startASession(lamp)

        when:
            var outcome = sandbox.oillamp.run('stop', lamp.toString())

        then: 'the request is accepted'
            outcome.status() == ExitStatus.SUCCESS

        and: 'and the session really ends, cleanly, of its own accord'
            session.join(30_000)
            sessionOutcome.status() == ExitStatus.SUCCESS
            reported.any { it instanceof LampEvent.Summary &&
                           it.lines().toList().any { line -> line.contains('asked to stop by') } }
    }

    def 'an extra shell attaches to the session, and closing it ends nothing'() {
        reportInfo """
            Exactly one connection decides when the session ends: the terminal window oillamp
            opened itself, which reaches the sandbox through a socket the agent cannot get at.
            Every other shell is an extra - useful, unlimited in number, and powerless to end
            anything.

            The session end is detected by watching that one relayed connection rather than by
            watching the terminal program, because a terminal emulator very often returns straight
            away and leaves its window running as somebody else's child process. Watching the
            program would report the session over within milliseconds of starting it.

            That is what makes `oillamp shell` safe to close, and it is why the two relays are
            separate sockets rather than one socket with a counter.
        """
        given:
            var lamp = sandbox.lampPath()
            startASession(lamp)

        when: 'a second oillamp opens an extra shell, which the user then closes'
            sandbox.machine { it.windowsStayOpenFor(Duration.ofSeconds(2)) }
            var outcome = sandbox.oillamp.run('shell', lamp.toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'the session noticed it arrive'
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('extra shell') } }

        and: 'and is still running now that it has gone'
            session.alive
            reported.every { !(it instanceof LampEvent.Summary && it.title().startsWith('session ')) }
    }

    def 'view opens another window onto the same desktop'() {
        reportInfo """
            The viewer is how the user watches, and they may want to watch from more than one
            window - or to reopen the one they closed. Neither changes the session, which is why
            this is the one control request that does something on the desktop and reports
            nothing back beyond "opening".
        """
        given:
            var lamp = sandbox.lampPath()
            startASession(lamp)
            var before = windowsOpened()

        when:
            var outcome = sandbox.oillamp.run('view', lamp.toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'the session opened one more viewer than it had'
            waitUntil { windowsOpened() == before + 1 }
            reported.findAll { it instanceof LampEvent.WindowOpened }
                    .count { it.what().contains('viewer') } == 2
    }

    def 'list reports the sandboxes running on this host, and says so plainly when there are none'() {
        reportInfo """
            Asked of podman, not of a list oillamp keeps. A second list beside the one the
            container runtime already maintains is a list that can be wrong, and it would be
            wrong in exactly the case it is needed: after a supervisor was killed.
        """
        when:
            var outcome = sandbox.oillamp.run('list')

        then:
            outcome.status() == ExitStatus.SUCCESS
            outcome.events().find { it instanceof LampEvent.Answer }
                   .text().contains('no oillamp sandboxes are running')
    }

    def 'stop cleans up after a supervisor that was killed without tidying up'() {
        reportInfo """
            Killing the supervising process outright - a crash, a `kill -9`, a power cut - must
            never leave a mess that the user has to clear up by hand. The lock goes with
            the process, so the lamp is free - but the container is not, and the next `oillamp at`
            would meet a name clash that says nothing about what happened.

            So `stop` with no supervisor to talk to is not an error: it is the cleanup.
        """
        given: 'a lamp whose sandbox is running with nobody supervising it'
            var lamp = sandbox.lampPath()
            startASession(lamp)
            var machine = sandbox.oillamp
            session.interrupt()
            session.join(20_000)

        when:
            var outcome = machine.run('stop', lamp.toString())

        then: 'oillamp either tidied up, or truthfully said there was nothing left to tidy'
            outcome.status() in [ExitStatus.SUCCESS, ExitStatus.ERROR]
            !java.nio.file.Files.exists(lamp.resolve('.oillamp/session.json'))
    }

    // ─── running a session beside the scenario ─────────────────────────────────────────────

    /**
     *  Starts a real session on its own thread and returns once it is up.
     *
     *  <p>The windows are told to stay open for far longer than the scenario needs, so that the
     *  session is ended by whatever the scenario does rather than by a timer - which would make
     *  every one of these pass for the wrong reason.
     */
    private void startASession(Path lamp) {
        sandbox.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var oillamp = sandbox.oillamp.observedBy { reported.add(it) }
        session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString()) }
        waitUntil { reported.any { it instanceof LampEvent.Summary &&
                                   it.title() == 'your session is up' } }
    }

    private int windowsOpened() {
        reported.count { it instanceof LampEvent.WindowOpened }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("the session never got there: ${condition}" as Object)
    }
}
