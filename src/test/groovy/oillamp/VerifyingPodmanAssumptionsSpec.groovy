package oillamp

import spock.lang.Tag
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

import java.nio.file.Files
import java.nio.file.Path

/**
 * Spikes S8, S12 and S13 of design spec §33 — the podman assumptions the container design rests on.
 *
 * <p>These run against real podman on a real machine. They are tagged {@code spike} and excluded
 * from {@code test}; run them with {@code ./gradlew spikes}.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.podmanAvailable() })
class VerifyingPodmanAssumptionsSpec extends Specification {

    @Shared Path scratch

    def setupSpec() {
        Spike.ensureBaseImage()
    }

    def setup() {
        scratch = Files.createTempDirectory('oillamp-spike-')
    }

    def cleanup() {
        // Not Files.deleteIfExists: some of these directories contain files owned by a subuid.
        Spike.removeTree(scratch.toString())
    }

    def 'S8: rootless podman runs here despite the Ubuntu restriction on unprivileged user namespaces'() {
        reportInfo """
            Ubuntu 23.10 and newer refuse unprivileged user namespaces to programs without a
            matching AppArmor profile, and rootless podman needs them. The spec assumes Ubuntu
            ships a profile that permits podman. If that were wrong, oillamp would not work at all
            on its primary target platform, and OIL-PODMAN-004 would be the most important message
            in the product rather than a rare one.

            This scenario is the cheapest possible check: if a container runs, the assumption holds.
        """
        when: 'a container is asked to do the simplest possible thing'
            var result = Spike.inBaseImage('echo alive')

        then: 'it runs, which means user namespaces were granted'
            result.ok
            result.mentions('alive')

        and: 'and podman reports itself as rootless, not quietly running as root'
            Spike.run('podman', 'info', '--format', '{{.Host.Security.Rootless}}').mentions('true')
    }

    def 'S13: the container sees the human as its own user, and the infrastructure as a different one'() {
        reportInfo """
            This is the single most load-bearing assumption in the whole design, so it is worth
            setting out from the beginning.

            On Linux every file is owned by a *number*, and every running process has one. Names
            like "dnepp" or "root" are a convenience layered on top; the kernel only ever compares
            numbers. A container can be given its own, private set of those numbers, together with
            a translation table saying which private number corresponds to which real one. That
            table is what decides, in the end, which files the programs inside a container may
            touch.

            oillamp arranges that table so it holds two properties at once. Both are needed, and
            neither is obvious enough to take on trust:

            1. Files that really belong to the person running oillamp must appear, inside the
               container, to belong to the container's own user number 1000 - the account the AI
               agent works as. That is what lets the agent write freely in its own home directory
               while those files remain ordinary, editable files belonging to the human outside.
               Without it the only alternatives are making everything world-writable, or leaving
               the human unable to open what the agent produced.

            2. It must also be possible to hand a directory to container user number 1001 - the
               account that runs the compositor, the screen recorder and the network bridges -
               such that the agent cannot write to it. That is what makes a screen recording
               trustworthy: the agent can see that it is being recorded and cannot do anything
               about it.

            If the second property did not hold, the boundary that lets a human trust the sandbox
            would simply not exist, and the honest response would have been to remove the second
            user from the design rather than pretend. That is why this is checked against real
            podman, before any of the code that depends on it was written.

            Note the scenario reads the host uid rather than assuming 1000: the machine this was
            first run on had the user at 1001, which would have made a hardcoded assertion pass or
            fail for the wrong reason.
        """
        given: 'a state directory, part of it handed to the infra user the way oillamp will'
            var state = Files.createDirectories(scratch.resolve('state'))
            Files.writeString(state.resolve('agent-owned.txt'), 'hello\n')
            var infra = Files.createDirectories(state.resolve('infra'))
            Spike.run('podman', 'unshare', 'chown', '1001:1001', infra.toString())

        when: 'a container runs with the mapping oillamp will use'
            var result = Spike.run('podman', 'run', '--rm', '--network=none',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$state:/probe".toString(),
                    Spike.BASE_IMAGE,
                    'sh', '-lc', 'id -u; stat -c "%u:%g %n" /probe /probe/agent-owned.txt /probe/infra')

        then: 'the container user is 1000'
            result.ok
            result.out.readLines().first().trim() == '1000'

        and: 'files the host user owns are the container user\'s own, with no chmod anywhere'
            result.mentions('1000:1000 /probe')
            result.mentions('1000:1000 /probe/agent-owned.txt')

        and: 'and what was given to the infra user is uid 1001 inside — a user the agent is not'
            result.mentions('1001:1001 /probe/infra')

        and: 'while on the host that same directory is a subuid the user does not own'
            var hostOwner = Spike.run('stat', '-c', '%u', infra.toString()).out.trim()
            hostOwner != Spike.run('id', '-u').out.trim()
            hostOwner.toInteger() > 65535
    }

    def 'S12: a read-only container can still write to its bind-mounted home'() {
        reportInfo """
            The container is run with `--read-only`, so that everything outside the agent's
            home is discarded and cannot be quietly modified. That is only useful if bind mounts
            onto pre-created mount points stay writable — otherwise the agent cannot work at all.
        """
        given: 'a workspace directory on the host'
            var work = Files.createDirectories(scratch.resolve('work'))

        when: 'a read-only container writes into the bind mount'
            var result = Spike.run('podman', 'run', '--rm', '--network=none', '--read-only',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$work:/work".toString(),
                    Spike.BASE_IMAGE,
                    'sh', '-lc', 'touch /work/written && echo wrote-to-mount; ' +
                                 'touch /nope 2>/dev/null && echo WROTE-TO-ROOTFS || echo rootfs-is-read-only')

        then: 'the bind mount accepted the write'
            result.ok
            result.mentions('wrote-to-mount')

        and: 'the root filesystem refused it'
            result.mentions('rootfs-is-read-only')
            !result.mentions('WROTE-TO-ROOTFS')

        and: 'and the file is on the host, owned by the user rather than by a subuid'
            Files.exists(work.resolve('written'))
            Spike.run('stat', '-c', '%u', work.resolve('written').toString()).out.trim() ==
                    Spike.run('id', '-u').out.trim()
    }

    def 'S12: a Unix socket the container creates is connectable from the host'() {
        reportInfo """
            This is the direction SSH uses (D-08): sshd listens inside the container on a socket in
            a bind-mounted directory, and the host's ssh client connects to it. It is what keeps
            `--network=none` possible while still giving the user a shell.

            The container borrows the host's socat — see Spike.borrowedSocat for why.
        """
        given: 'a directory both sides can see'
            var sockets = Files.createDirectories(scratch.resolve('sockets'))

        when: 'the container listens on a socket there'
            Spike.run('podman', 'run', '-d', '--name', 'oillamp-spike-listen', '--network=none',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$sockets:/sock".toString(), '-v', '/usr:/hostusr:ro',
                    Spike.BASE_IMAGE,
                    *Spike.borrowedSocat('UNIX-LISTEN:/sock/agent.sock,fork',
                                         'SYSTEM:echo HELLO-FROM-CONTAINER'))
            waitForFile(sockets.resolve('agent.sock'))

        then: 'the socket appears on the host'
            Files.exists(sockets.resolve('agent.sock'))

        and: 'and the host can talk to whatever is behind it'
            Spike.run('socat', '-T5', '-', "UNIX-CONNECT:${sockets.resolve('agent.sock')}".toString())
                 .mentions('HELLO-FROM-CONTAINER')

        cleanup:
            Spike.run('podman', 'rm', '-f', 'oillamp-spike-listen')
    }

    def 'S12: a Unix socket the host creates is connectable from the container'() {
        reportInfo """
            The other direction, and the one the whole network design depends on: the agent
            has no route and no DNS, and reaches the egress proxy only through a socket the host
            supervisor is listening on. If this did not work, `--network=none` would have to go,
            and with it the guarantee that nothing leaves the sandbox unseen.
        """
        given: 'the host listening on a socket in a shared directory'
            var sockets = Files.createDirectories(scratch.resolve('sockets'))
            var socket = sockets.resolve('host.sock')
            var listener = new ProcessBuilder('socat', "UNIX-LISTEN:$socket,fork".toString(),
                                              'SYSTEM:echo HELLO-FROM-HOST')
                    .redirectErrorStream(true).start()
            waitForFile(socket)

        when: 'the container connects to it'
            var result = Spike.run('podman', 'run', '--rm', '--network=none',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$sockets:/sock".toString(), '-v', '/usr:/hostusr:ro',
                    Spike.BASE_IMAGE,
                    *Spike.borrowedSocat('-T5', '-', 'UNIX-CONNECT:/sock/host.sock'))

        then: 'the host answers'
            result.mentions('HELLO-FROM-HOST')

        cleanup:
            listener.destroyForcibly()
    }

    private static void waitForFile(Path path, int attempts = 50) {
        for (int i = 0; i < attempts && !Files.exists(path); i++) Thread.sleep(100)
    }
}
