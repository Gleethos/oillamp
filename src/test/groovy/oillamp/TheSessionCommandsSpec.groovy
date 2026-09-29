package oillamp

import dev.lamp.ExitStatus
import dev.lamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
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
            the container, release the lock - which is the same sequence Ctrl-C sets off.
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
            No shell decides when the session ends. Extra shells are useful, unlimited in number,
            and powerless to end anything, and so is the window oillamp opened itself.
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

    def '--no-windows runs a session where there is no display, and says how to get in'() {
        reportInfo """
            For someone who starts oillamp under tmux, so that it outlives the desktop they are
            logged into, and who attaches to it on their own terms. oillamp opens nothing, so it
            does not need a display, and the terminal it runs in lists the ways in instead: a
            shell with `oillamp shell`, the desktop with `oillamp view`, or from another machine
            by forwarding the desktop's socket over ssh. The session ends the usual way.
        """
        given: 'a machine with no desktop session'
            var lamp = sandbox.lampPath()
            sandbox.machine { it.noGraphicalSession() }
            var oillamp = sandbox.oillamp.observedBy { reported.add(it) }

        when:
            session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString(), '--no-windows') }
            waitUntil { reported.any { it instanceof LampEvent.Summary &&
                                       it.title() == 'your session is up' } }

        then: 'no window opened, and the missing display was not held against it'
            reported.findAll { it instanceof LampEvent.WindowOpened }.isEmpty()
            reported.every { !(it instanceof LampEvent.Failure) }

        and: 'the terminal says how to get in, including from another machine'
            var briefing = reported.find { it instanceof LampEvent.Summary &&
                                           it.title() == 'your session is up' }.lines().join('\n')
            briefing.contains("oillamp shell ${lamp}")
            briefing.contains("oillamp view ${lamp}")
            briefing.contains('ssh -N -L 5901:') && briefing.contains('vnc.sock')

        and: 'and it says which machine each way in is run on, since an ssh forward to itself does nothing'
            briefing.contains('on this machine')
            briefing.contains('from another machine')

        and: 'the session is running without anyone having connected'
            sandbox.oillamp.run('status', lamp.toString()).console().contains('no windows opened')

        when: 'the user ends it'
            sandbox.oillamp.run('stop', lamp.toString())
            session.join(20_000)

        then:
            sessionOutcome.status() == ExitStatus.SUCCESS
    }

    def 'after the shell window closes, the session keeps running and another shell can attach'() {
        reportInfo """
            The user closed the shell window oillamp opened, perhaps because a program in it hung.
            That is not the user saying they are finished. The sandbox, the agent's programs and
            the desktop carry on, and `oillamp shell` opens a new way in.
        """
        given: 'a session whose shell window the user closes after a second'
            var lamp = sandbox.lampPath()
            sandbox.machine { it.windowsStayOpenFor(Duration.ofSeconds(1))
                                .userStopsTheSessionAfter(Duration.ofSeconds(60)) }
            var oillamp = sandbox.oillamp.observedBy { reported.add(it) }
            session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString()) }
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('the shell window closed') } }

        when:
            var status = sandbox.oillamp.run('status', lamp.toString())
            var shell = sandbox.oillamp.run('shell', lamp.toString())

        then: 'the session still answers, and says the window is closed'
            status.status() == ExitStatus.SUCCESS
            status.console().contains('running')
            status.console().contains('shell window oillamp opened is closed')

        and: 'a new shell attached to it'
            shell.status() == ExitStatus.SUCCESS
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('extra shell attached') } }

        and: 'and nothing has ended'
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
            Killing the supervising process outright (a crash, a `kill -9`, a power cut) must
            never leave a mess that the user has to clear up by hand. The lock goes with the
            process, but its control socket file and `session.json` stay, and so may its
            container. `status` then says the session does not answer and suggests `oillamp stop`.

            So `stop` with no supervisor to talk to is the cleanup. It once removed only the
            container: when that was already gone, it removed nothing and repeated the same advice,
            so the user went round in a circle.
        """
        given: 'what a killed supervisor leaves behind: a socket nobody listens on, and session.json'
            var lamp = sandbox.lampPath()
            var socket = leftBehindByAKilledSupervisor(lamp)
            sandbox.machine { containerStillThere ? it.commandSucceeding('podman container exists', '')
                                                  : it.commandFailing('podman container exists', 1, '') }

        expect: 'status says the session does not answer'
            sandbox.oillamp.run('status', lamp.toString()).reported('OIL-SESSION-002')

        when:
            var stopped = sandbox.oillamp.run('stop', lamp.toString())

        then: 'stop cleans up and says what it did'
            stopped.status() == ExitStatus.SUCCESS
            stopped.console().contains(containerStillThere ? 'the sandbox left by the previous session has been removed'
                                                          : 'its sandbox was already gone')
            !Files.exists(socket)
            !Files.exists(lamp.resolve('.oillamp/session.json'))

        and: 'afterwards status says plainly that no session is running'
            sandbox.oillamp.run('status', lamp.toString()).reported('OIL-SESSION-001')

        where:
            containerStillThere << [true, false]
    }

    def 'A connection to the control socket that never says anything does not shut out the others'() {
        reportInfo """
            The session answered its control socket one connection at a time, and waited for
            each to send its request for as long as it took. One that never sent anything, such
            as an `oillamp status` suspended with Ctrl-Z at the wrong moment, left `stop`,
            `status`, `view` and `shell` waiting behind it for the rest of the session. Each
            connection now has its own thread, and a few seconds to say what it wants.
        """
        given:
            var lamp = sandbox.lampPath()
            startASession(lamp)
            var silent = java.nio.channels.SocketChannel.open(UnixDomainSocketAddress.of(controlSocket(lamp)))

        when:
            var started = System.nanoTime()
            var outcome = sandbox.oillamp.run('status', lamp.toString())

        then: 'status is answered straight away'
            outcome.status() == ExitStatus.SUCCESS
            Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)

        and: 'the silent connection is closed once its time is up'
            silent.configureBlocking(true)
            silent.read(java.nio.ByteBuffer.allocate(16)) == -1

        cleanup:
            silent?.close()
    }

    def 'A session that accepts a connection but never answers is reported, not waited on forever'() {
        reportInfo """
            A supervisor that is frozen, stopped with Ctrl-Z or stuck, still has its socket, and
            the kernel still accepts connections to it. `status` and `stop` then waited for an
            answer that never came, with no way to tell the user anything. They now give up after
            a few seconds and say the session did not answer.
        """
        given: 'a control socket that accepts connections and never answers them'
            var lamp = sandbox.lampPath()
            var socket = leftBehindByAKilledSupervisor(lamp)
            Files.delete(socket)
            var frozen = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            frozen.bind(UnixDomainSocketAddress.of(socket))

        when:
            var started = System.nanoTime()
            var outcome = sandbox.oillamp.run(command, lamp.toString())

        then: 'the user is told the session did not answer'
            outcome.reported('OIL-SESSION-002')
            Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(20)

        and: 'nothing of a session that may still be alive is removed'
            Files.exists(socket)
            Files.exists(lamp.resolve('.oillamp/session.json'))

        cleanup:
            frozen?.close()

        where:
            command << ['status', 'stop']
    }

    private static Path controlSocket(Path lamp) {
        var sessionJson = Files.readString(lamp.resolve('.oillamp/session.json'))
        Path.of((sessionJson =~ /"controlSocket": "([^"]+)"/)[0][1] as String)
    }

    /**
     *  Runs a session to its end, then puts back what a supervisor killed with `kill -9` would
     *  have left: its control socket file with nothing listening, and `session.json`.
     *
     *  @return the control socket
     */
    private Path leftBehindByAKilledSupervisor(Path lamp) {
        startASession(lamp)
        var sessionJson = Files.readString(lamp.resolve('.oillamp/session.json'))
        var socket = Path.of((sessionJson =~ /"controlSocket": "([^"]+)"/)[0][1] as String)
        sandbox.oillamp.run('stop', lamp.toString())
        session.join(20_000)

        var abandoned = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        abandoned.bind(UnixDomainSocketAddress.of(socket))
        abandoned.close()       // closing does not remove the file, as a killed process would not
        Files.writeString(lamp.resolve('.oillamp/session.json'), sessionJson)
        socket
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
