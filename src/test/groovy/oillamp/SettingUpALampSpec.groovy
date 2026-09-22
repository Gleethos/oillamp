package oillamp

import dev.oillamp.ExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 *  A "lamp" is one directory on disk that holds a sandbox: its settings, its state, and the
 *  agent's persistent home. These scenarios are about what `oillamp at <dir>` puts there, and -
 *  more importantly - about what it refuses to put there.
 */
class SettingUpALampSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() {
        sandbox = new Sandbox(tmp)
        // Key generation is the one step whose *result* matters rather than its exit code:
        // a lamp is only really set up if the key files exist afterwards.
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'A dry run shows the user everything that would happen, and changes nothing'() {
        reportInfo """
            oillamp installs packages, changes /etc/subuid, writes SSH keys and creates
            directories in the user's home. That is a lot of trust to ask for, so FR-12 requires
            a mode that shows the complete plan first.

            What makes this trustworthy is that --dry-run is not a separate code path: setup is
            modelled as a list of described changes, and the dry run simply announces them
            instead of carrying them out. There is no second implementation to drift.
        """
        given: 'a directory that does not exist yet'
            var lamp = sandbox.lampPath()
            !Files.exists(lamp)

        when: 'the user asks what oillamp would do'
            var outcome = sandbox.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'it succeeds'
            outcome.status() == ExitStatus.SUCCESS

        and: 'the directory is still not there, and neither is anything else'
            !Files.exists(lamp)

        and: 'the plan covers the settings file, the identity, the keys and the agent home'
            outcome.steps().any { it.contains('oillamp.toml') }
            outcome.steps().any { it.contains('lamp.json') }
            outcome.steps().any { it.contains('client_ed25519') }
            outcome.steps().any { it.contains('host_ed25519') }
            outcome.steps().any { it.contains('agent-lamp-') && it.contains('workspace') }

        and: 'including the two directories the agent must not be able to write to'
            outcome.steps().any { it.contains('recordings') && it.contains('container uid 1001') }
            outcome.steps().any { it.contains('sockets/infra') && it.contains('container uid 1001') }

        and: 'the user is told plainly that nothing was done'
            outcome.console().contains('dry run')
    }

    def 'A dry run answers the question even when oillamp could not carry the plan out'() {
        reportInfo """
            Found by running the real binary: on a machine where sudo would need a password and
            there is no terminal to ask in, --dry-run was refusing with "cannot run sudo".

            That is answering a different question. The user asked what oillamp *would* do, and a
            dry run changes nothing, so whether sudo happens to work right now is irrelevant to
            it. Being able to inspect the plan from a script, or before deciding to grant sudo at
            all, is a large part of why FR-12 exists.
        """
        given: 'a machine that needs packages installed, where sudo cannot currently be used'
            sandbox.machine { it.withoutPodman().sudoNeedsPassword().interactive(false) }

        when: 'the user asks what oillamp would do'
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--dry-run')

        then: 'they get the plan, not a complaint about sudo'
            outcome.status() == ExitStatus.SUCCESS
            !outcome.reported('OIL-PKG-002')
            outcome.stepKinds().contains('InstallPackages')

        and: 'the plan is complete, all the way to the per-session files'
            outcome.steps().any { it.contains('agent-lamp-') }
            outcome.steps().any { it.contains('runtime.env') }
            outcome.steps().any { it.contains('known_hosts') }

        when: 'but they actually ask oillamp to do it'
            var real = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'then the missing sudo is the blocker it really is'
            real.reported('OIL-PKG-002')
    }

    def 'Setting up a new lamp gives the agent a home and keeps everything else out of it'() {
        reportInfo """
            This is the design's central idea (D-10), and the one thing that must never be got
            wrong: the lamp is two layers. The agent gets `agent-lamp-<id>/`, mounted as its home.
            Everything that *governs or observes* the agent - the network policy, the SSH keys,
            the logs, the screen recordings - sits beside it, never inside it.

            If the policy file ever ended up inside the agent's directory, the agent could edit
            its own firewall rules. So this scenario checks the separation directly rather than
            trusting that the code that creates the directories got it right.
        """
        given:
            var lamp = sandbox.lampPath()

        when: 'the user sets up a lamp'
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then: 'it succeeds'
            outcome.status() == ExitStatus.SUCCESS
            outcome.errors().isEmpty()

        and: 'the agent has a home with somewhere for code and somewhere for native libraries'
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.isDirectory(agentHome.resolve('workspace'))
            Files.isDirectory(agentHome.resolve('libs'))

        and: 'the settings file exists, and is NOT inside the agent home'
            Files.exists(lamp.resolve('oillamp.toml'))
            !Files.exists(agentHome.resolve('oillamp.toml'))

        and: 'neither are the keys, the logs or the recordings'
            Files.isDirectory(lamp.resolve('.oillamp/keys'))
            Files.isDirectory(lamp.resolve('.oillamp/logs'))
            Files.isDirectory(lamp.resolve('.oillamp/recordings'))
            !Files.exists(agentHome.resolve('.oillamp'))

        and: 'the state directory is readable only by its owner, so no other user on this machine can reach the sockets inside it'
            PosixFilePermissions.toString(Files.getPosixFilePermissions(lamp.resolve('.oillamp'))) == 'rwx------'

        and: 'the lamp got its own SSH key pair rather than borrowing the user\'s'
            Files.exists(lamp.resolve('.oillamp/keys/client_ed25519'))
            Files.exists(lamp.resolve('.oillamp/keys/host_ed25519.pub'))

        and: 'the user is never asked to trust a host key, because it is pinned in advance'
            Files.readString(lamp.resolve('.oillamp/known_hosts')).startsWith('lamp-')
    }

    def 'Running oillamp again on the same lamp keeps the agent\'s world and the user\'s settings'() {
        reportInfo """
            The point of a lamp is that the agent comes back to the work it left: its repositories,
            its tool configuration, its shell history (FR-21). And the user's edited policy must
            survive too - a tool that silently reset `oillamp.toml` on every run would be useless.

            So the second run must be almost entirely a no-op, and must say so.
        """
        given: 'a lamp that has been set up once'
            var lamp = sandbox.lampPath()
            sandbox.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()

        and: 'the user has changed a setting, and the agent has done some work'
            var settings = lamp.resolve('oillamp.toml')
            Files.writeString(settings, Files.readString(settings).replace('width  = 1920', 'width  = 2560'))
            Files.writeString(agentHome.resolve('workspace/notes.md'), 'work in progress')
            var identityBefore = Files.readString(lamp.resolve('.oillamp/lamp.json'))
            var keyBefore = Files.readString(lamp.resolve('.oillamp/keys/client_ed25519'))

        when: 'the user runs oillamp on that lamp again'
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then: 'it succeeds'
            outcome.status() == ExitStatus.SUCCESS

        and: 'the edited setting is still there'
            Files.readString(settings).contains('width  = 2560')

        and: 'and has taken effect'
            outcome.console().contains('2560x1440') || outcome.console().contains('2560')

        and: 'the agent\'s work is untouched'
            Files.readString(agentHome.resolve('workspace/notes.md')) == 'work in progress'

        and: 'the lamp keeps the same identity, so it is the same sandbox'
            var agentId = identityBefore.find(/"agentId": "(\w+)"/) { it[1] }
            Files.readString(lamp.resolve('.oillamp/lamp.json')).contains(agentId)

        and: 'and the same keys, so no host-key warning appears'
            Files.readString(lamp.resolve('.oillamp/keys/client_ed25519')) == keyBefore
    }

    def 'A directory that already holds something else is refused'() {
        reportInfo """
            `oillamp at ~/my-project` is an easy thing to type by mistake. Since a lamp takes
            ownership of its directory, doing that to a directory the user is already using would
            scatter state through it and, worse, expose part of it to the agent.

            So a non-empty directory that is not already a lamp is refused (FR-02) - but the
            refusal tells the user what is in the way and how to override it deliberately.
        """
        given: 'a directory with the user\'s own files in it'
            var directory = Files.createDirectories(tmp.resolve('home/dev/my-project'))
            Files.writeString(directory.resolve('README.md'), 'my actual project')
            Files.createDirectory(directory.resolve('src'))

        when: 'the user points oillamp at it'
            var outcome = sandbox.oillamp.run('at', directory.toString())

        then: 'oillamp refuses'
            outcome.reported('OIL-LAMP-002')
            outcome.status() != ExitStatus.SUCCESS

        and: 'nothing was created'
            !Files.exists(directory.resolve('.oillamp'))
            !Files.exists(directory.resolve('oillamp.toml'))

        and: 'the user is shown what is in the way'
            var problem = outcome.errors().first()
            problem.evidence().any { it.toString().contains('README.md') }

        and: 'and told how to say "yes, I meant it"'
            problem.fixes().any { it.command().orElse('').contains('--init') }

        when: 'the user confirms with --init'
            var confirmed = sandbox.oillamp.run('at', directory.toString(), '--init')

        then: 'oillamp sets the lamp up alongside their files'
            confirmed.status() == ExitStatus.SUCCESS
            Files.exists(directory.resolve('.oillamp/lamp.json'))
            Files.readString(directory.resolve('README.md')) == 'my actual project'
    }

    def 'The home directory itself and system directories are refused outright'() {
        reportInfo """
            FR-03. A lamp mounts part of its directory into the sandbox as the agent's home, so
            making the user's whole home directory a lamp would hand the agent everything the
            sandbox exists to protect. Making /etc a lamp is worse.

            There is deliberately no flag to override this. A flag to override it would be used
            exactly once, by someone in a hurry, on the wrong directory.
        """
        when: 'the user points oillamp at their home directory itself'
            var outcome = sandbox.oillamp.run('at', sandbox.home.toString())

        then: 'oillamp refuses and explains why, suggesting a subdirectory instead'
            outcome.reported('OIL-LAMP-003')
            outcome.errors().first().whatHappened().contains('home directory')
            outcome.errors().first().whatHappened().contains('lamps')

        when: 'or at a system directory'
            var system = sandbox.oillamp.run('at', '/etc/oillamp')

        then: 'that is refused too'
            system.reported('OIL-LAMP-003')
            system.errors().first().whatHappened().contains('/etc')
    }

    def 'A lamp on a filesystem that cannot hold Unix sockets is refused before anything is created'() {
        reportInfo """
            Every channel between the host and the sandbox - the shell, the desktop stream, the
            network proxy - is a Unix domain socket inside the lamp directory (D-04, D-08, D-12).
            Network filesystems and FAT-style filesystems cannot host those.

            Discovering that halfway through starting a container would be a baffling failure, so
            it is checked up front, where the message can name the filesystem and suggest a fix.
        """
        given: 'a lamp directory on a network share'
            sandbox.machine { it.lampFilesystemType('nfs') }

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then:
            outcome.reported('OIL-LAMP-005')
            outcome.errors().first().whatHappened().contains('nfs')
            !Files.exists(sandbox.lampPath())
    }

    def 'A lamp written by a newer oillamp is left alone rather than damaged'() {
        reportInfo """
            A lamp holds the agent's accumulated work. If a future version changes the layout,
            an older oillamp reading it could misinterpret or destroy that. So the identity file
            carries a schema version, and a version from the future is a hard stop with the only
            useful advice there is: upgrade.
        """
        given: 'a lamp created by a later version of oillamp'
            var lamp = Files.createDirectories(sandbox.lampPath())
            Files.createDirectories(lamp.resolve('.oillamp'))
            Files.writeString(lamp.resolve('.oillamp/lamp.json'), '''
                {
                  "schemaVersion": 99,
                  "agentId": "k3v7x2ab",
                  "createdAt": "2027-01-01T00:00:00Z",
                  "createdBy": "oillamp 9.0.0"
                }
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('at', lamp.toString())

        then:
            outcome.reported('OIL-LAMP-004')
            outcome.errors().first().fixes().any { it.description().contains('upgrade') }
    }
}
