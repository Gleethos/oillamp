package oillamp

import dev.oillamp.ExitStatus
import dev.oillamp.Machine
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Starting the sandbox, and knowing whether it really started — design spec §15, §16.
 *
 * <p>Every scenario here comes from one run on a real machine. The second session on a lamp came
 * up with a working shell and a dead desktop, and oillamp printed a green tick, told the user
 * which socket to connect to, and returned. The container died two seconds later. The reason was
 * in `podman logs` the whole time, and nothing surfaced it.
 *
 * <p>The cause was one file: /oillamp/sockets is a bind mount, so it outlives the container, and
 * `vnc.sock` from the previous session was still there when wayvnc tried to bind. wayvnc has no
 * equivalent of socat's `unlink-early`, so it failed — and every check on both sides of the
 * mount was an existence test that the leftover file satisfied.
 *
 * <p>So these scenarios are all the same question asked in different places: does oillamp know
 * the difference between a socket that is there and a server that is listening?
 */
class StartingTheSandboxSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'A session is not reported as started until oillamp has opened every socket itself'() {
        reportInfo """
            ready.json is the sandbox's own account of itself, and a report written by the thing
            being reported on is worth checking. The container writes it after its servers come
            up; if one of them then dies - or never bound in the first place - the file still
            says everything is fine.

            So the last thing `at` does is connect to each socket it is about to hand the user.
            That is the same act the viewer performs a moment later, which is what makes it proof
            rather than another assertion.
        """
        given: 'a lamp'
            var lamp = sandbox.lampPath()

        when:
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then: 'the session starts'
            outcome.status() == ExitStatus.SUCCESS

        and: 'and oillamp checked the sockets rather than trusting the readiness file'
            outcome.stepKinds().contains('CheckEndpoints')

        and: 'naming both, because a desktop without a shell is as broken as the reverse'
            outcome.stepDetails().any { it.contains('vnc.sock') && it.contains('ssh.sock') }
    }

    def 'A sandbox that reports itself ready but refuses connections is caught, with its log'() {
        reportInfo """
            This is the exact failure the user hit, reduced: the socket file is present, the
            readiness file is written, and nothing is listening.

            The requirement is not only that oillamp fails. It is that the user learns why
            without going looking - the container's own log is the only place the reason exists,
            so the problem carries it.
        """
        given: 'a sandbox whose VNC socket is there but answers nothing'
            sandbox.machine { it.endpointRefusingConnections('vnc.sock') }
            var lamp = sandbox.lampPath()

        when:
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then: 'oillamp refuses to call this a session'
            outcome.status() != ExitStatus.SUCCESS

        and: 'and says which part is not answering, in the words the user would use'
            outcome.errors().any { it.toString().contains('desktop') }
            outcome.errors().any { it.toString().contains('OIL-SANDBOX-004') }

        and: 'and hands over the sandbox log rather than leaving it to be dug out of podman'
            outcome.console().contains('sandbox log')

        and: 'and does not print connection instructions for a socket that refuses'
            !outcome.console().contains('vncviewer')
    }

    def 'The readiness file from the previous session is not mistaken for this one'() {
        reportInfo """
            The sockets directory survives the container, so on every session after the first
            there is already a ready.json in it. An existence test therefore reports the sandbox
            ready before the container has done anything at all - and the later that is trusted,
            the worse it gets, because the host goes on to hand out sockets that do not exist yet.

            Two things stop it, and this scenario is about the second one. oillamp clears the
            previous session's files before starting, and it checks that the readiness file it
            finds carries *this* session's id. The first is the fix; the second is what makes the
            fix safe to be wrong about, which matters because the clearing runs through
            `podman unshare` and can fail on its own.
        """
        given: 'a lamp that has been used before, so a readiness file is already in place'
            var lamp = sandbox.lampPath()
            sandbox.oillamp.run('at', lamp.toString())
            var readyFile = lamp.resolve('.oillamp/sockets/infra/ready.json')
            var previous = Files.readString(readyFile)
            previous.contains('"session"')

        and: 'a later session whose attempt to clear that file fails'
            sandbox.machine {
                it.clockAt(Instant.parse('2026-09-22T18:30:00Z'))
                  .command('podman unshare rm', new Machine.Outcome.Finished(
                          1, '', 'rm: cannot remove: Operation not permitted', Duration.ofMillis(5)))
            }

        and: 'and whose container never gets as far as writing a readiness file of its own'
            sandbox.machine {
                it.command('podman run', new Machine.Outcome.Finished(
                        0, 'simulated-container-id', '', Duration.ofMillis(10)))
            }

        when:
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then: 'the session fails, because the only readiness file present is the old one'
            outcome.status() != ExitStatus.SUCCESS

        and: 'the previous session\'s file is still sitting there, having been believed by nobody'
            Files.readString(readyFile) == previous

        and: 'and the user is told the sandbox never reported itself ready'
            outcome.errors().any { it.toString().contains('OIL-SANDBOX-00') }
    }

    def 'Clearing what the previous session left behind is part of starting one'() {
        reportInfo """
            The other half: on an ordinary second run the old files are removed before the
            container starts, so wayvnc finds its socket path free and the host never sees the
            previous answer.
        """
        given: 'a lamp that has been used before'
            var lamp = sandbox.lampPath()
            sandbox.oillamp.run('at', lamp.toString())
            var readyFile = lamp.resolve('.oillamp/sockets/infra/ready.json')
            var previous = Files.readString(readyFile)

        when: 'it is used again, a minute later'
            sandbox.machine { it.clockAt(Instant.parse('2026-09-22T18:30:00Z')) }
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'the readiness file is this session\'s, not the one that was already there'
            Files.readString(readyFile) != previous

        and: 'because the previous session\'s files were cleared first'
            outcome.stepKinds().contains('DeleteContainerOwnedFiles')
            outcome.steps().any { it.contains('previous session') }
    }

    def 'A second session on the same lamp starts cleanly, which is where this went wrong'() {
        reportInfo """
            The whole bug in one scenario. The first session on a lamp always worked; it was the
            second that came up with a dead desktop, because it was the first one to find a
            socket file already in place.

            Running `at` twice is therefore not a redundant scenario - it is the only one that
            covers the state the user was actually in.
        """
        given:
            var lamp = sandbox.lampPath()

        when: 'the lamp is used twice in a row'
            var first = sandbox.oillamp.run('at', lamp.toString())
            var second = sandbox.oillamp.run('at', lamp.toString())

        then: 'both sessions are equally good'
            first.status() == ExitStatus.SUCCESS
            second.status() == ExitStatus.SUCCESS

        and: 'and the second checked its sockets like the first'
            second.stepKinds().contains('CheckEndpoints')
    }

    def 'The stale files are removed in the one way that works on a lamp'() {
        reportInfo """
            These files belong to a container uid, inside the subuid range the host user does not
            own. `rm` gets EPERM on them - which is the same reason §9.2 exists at all, and the
            reason the agent cannot tamper with its own recording.

            They have to be removed through `podman unshare`, and a plan that showed an ordinary
            delete here would be a plan that fails on every machine.
        """
        given:
            var lamp = sandbox.lampPath()

        when:
            var outcome = sandbox.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the plan names the sockets that outlive a container'
            outcome.steps().any { it.contains('previous session') }

        and: 'and the readiness file, which is the one that would be believed too early'
            outcome.stepDetails().any { it.contains('ready.json') }
    }
}
