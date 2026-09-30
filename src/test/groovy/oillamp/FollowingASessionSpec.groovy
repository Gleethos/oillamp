package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.Lamp.Question
import dev.lamp.LampEvent
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  Following a session that someone else started: {@code oillamp follow}.
 *
 *  <p>The events of a session go to whoever started it. An application that closed and opens
 *  again, or a person at a terminal, wants to see the same: what the agent is doing now, and
 *  everything after. They ask the session over its control socket, and it answers with its
 *  events, as they happen, until it ends.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FollowingASessionSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    Path lamp
    Lamp held
    final List<LampEvent> followed = new CopyOnWriteArrayList<>()
    final CountDownLatch release = new CountDownLatch(1)

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
        lamp = sandbox.lampPath()
        assert sandbox.oillamp.run('at', lamp.toString()).succeeded()
        sandbox.machine { it.agent { String prompt ->
            if (prompt.startsWith('wait')) release.await()
            'Done: ' + prompt
        } }
        held = Lamp.at(lamp).launchedBy(sandbox.launcher).start()
        assert held.awaitRunning(Duration.ofSeconds(30))
    }

    def cleanup() {
        release.countDown()
        held?.close()
    }

    def 'Someone who follows late catches up on the run in progress, then hears the rest as it happens'() {
        reportInfo """
            The agent is working on one question, and another waits its turn, when someone starts
            following. They are not told about the session's past, only about its present: how
            to reach the sandbox, the run in progress from its start, and the run waiting. Then
            they hear everything else as it happens, both runs finishing, and at last the session
            ending, which ends following too.
        """
        given: 'the agent is at work on one question, and another waits'
            var first = held.send(Question.fresh('wait for me'))
            var second = held.send(Question.fresh('and then this'))
            eventually { held.agentStatus().waiting().any { it.id() == second.id() } }

        when: 'someone starts following'
            var following = follow()

        then: 'they learn how to reach the sandbox, and what the agent is doing'
            eventually { followed.any { it instanceof LampEvent.SessionOpened } }
            eventually { followed.any { it instanceof LampEvent.RunStarted && it.run().id() == first.id() } }
            eventually { followed.any { it instanceof LampEvent.RunQueued && it.run().id() == second.id() } }

        when: 'the agent is done'
            release.countDown()

        then: 'they hear both answers'
            eventually { finished(first.id()) && finished(second.id()) }
            followed.find { it instanceof LampEvent.RunFinished && it.run().id() == second.id() }
                    .answer() == 'Done: and then this'

        when: 'the session ends'
            held.close()
            following.join(30_000)

        then: 'following ends with it, and says so'
            following.outcome.status() == ExitStatus.SUCCESS
            followed.any { it instanceof LampEvent.Summary }
    }

    def 'An application that stops following ends nothing'() {
        reportInfo """
            An application follows with `oillamp follow --embedded`, and stops by closing its
            standard input, the same signal that ends a session it started. Here that signal only
            ends the following: the session, which someone else holds, keeps running.
        """
        when: 'an application follows, and closes its end a second later'
            sandbox.machine { it.standardInput(closingAfter(Duration.ofSeconds(1))) }
            var outcome = sandbox.oillamp.run('follow', lamp.toString(), '--embedded')

        then: 'following ended cleanly'
            outcome.status() == ExitStatus.SUCCESS
            outcome.events().any { it instanceof LampEvent.SessionOpened }

        and: 'the session is still running'
            held.exitStatus().isEmpty()
            held.agentStatus() != null
    }

    def 'There is nothing to follow where no session runs'() {
        reportInfo """
            Following a lamp that is not running says so, as `oillamp status` does, rather than
            waiting for a session that is not coming.
        """
        given:
            held.close()

        when:
            var outcome = sandbox.oillamp.run('follow', lamp.toString())

        then:
            !outcome.succeeded()
            outcome.reported('OIL-SESSION-001')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** `oillamp follow <lamp>` on a thread of its own, as a terminal would run it. */
    private Following follow() {
        var following = new Following()
        following.thread = Thread.start {
            following.outcome = sandbox.oillamp.observedBy { followed << it }.run('follow', lamp.toString())
        }
        following
    }

    private static class Following {
        Thread thread
        volatile dev.oillamp.OilLamp.Outcome outcome
        void join(long millis) { thread.join(millis) }
    }

    /** A standard input that ends after `delay`, as an application's does when it closes. */
    private static InputStream closingAfter(Duration delay) {
        new InputStream() {
            @Override int read() {
                Thread.sleep(delay.toMillis())
                -1
            }
        }
    }

    private boolean finished(String run) {
        followed.any { it instanceof LampEvent.RunFinished && it.run().id() == run }
    }

    private static void eventually(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            assert System.currentTimeMillis() < deadline : 'it never happened'
            Thread.sleep(20)
        }
    }
}
