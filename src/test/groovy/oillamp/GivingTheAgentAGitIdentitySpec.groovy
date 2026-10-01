package oillamp

import dev.lamp.ExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 *  The name and email on the agent's commits.
 *
 *  <p>This came from real use. The sandbox had no git identity, so git refused the agent's first
 *  commit, and the agent fixed that the obvious way: `git config user.name "opencode agent"`,
 *  inside the repository. The repository lives in the lamp and is shared with the host, so from
 *  then on the user's own IDE on the host committed as "opencode agent" too.
 *
 *  <p>So oillamp gives the sandbox an identity each session, its own by default, and
 *  {@code [git]} in {@code oillamp.toml} can choose the user's, another one, or none. It is written to
 *  {@code .oillamp/session/gitconfig}, which the image's {@code /etc/gitconfig} includes. That is
 *  git's lowest level: the agent's {@code ~/.gitconfig} and a repository's own setting still win.
 */
class GivingTheAgentAGitIdentitySpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'By default the agent commits as a genie of its lamp, and nothing about the user enters the sandbox'() {
        reportInfo """
            The user's name and address are the user's, and the sandbox is where untrusted text
            is read. So by default the agent gets oillamp's own identity, genie agent, with the
            lamp's id in the email: a commit then says both that an agent made it and which
            lamp. The console says whose name the commits will carry.
        """
        given: 'a user whose git knows who they are'
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())
            var lampId = Files.list(host.lampPath()).map { it.fileName.toString() }
                              .filter { it.startsWith('agent-lamp-') }.findFirst().orElseThrow() - 'agent-lamp-'

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'the sandbox is given the genie\'s identity'
            gitValue('user.name') == 'genie agent'
            gitValue('user.email') == "genie@${lampId}".toString()

        and: 'not the user\'s'
            !Files.readString(sessionGitConfig()).contains('Ada')
            !Files.readString(sessionGitConfig()).contains('ada@example.com')

        and: 'the user is told'
            outcome.console().contains("the agent's commits will carry genie agent <genie@${lampId}>")
    }

    def 'A lamp can let the agent commit as the user, with the identity from their own git configuration'() {
        reportInfo """
            Some users want the agent's commits in their history under their own name, as those
            of any other tool they run are. `identity = "host"` reads user.name and user.email
            from the user's global git configuration when a session starts.
        """
        given: 'a user whose git knows who they are, and asks for it'
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "host"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.status() == ExitStatus.SUCCESS

        and: 'the sandbox is given that identity'
            gitValue('user.name') == 'Ada Lovelace'
            gitValue('user.email') == 'ada@example.com'

        and: 'the user is told'
            outcome.console().contains("the agent's commits will carry Ada Lovelace <ada@example.com>")
    }

    def 'A lamp can give the agent an identity of its own'() {
        reportInfo """
            Some people want to see at a glance which commits an agent made, or use a separate
            address for them. `identity = "custom"` uses the name and email from [git] instead of
            the user's. The values go into a git configuration file, so a quote or a backslash in
            a name must reach git unchanged; real git reads the file back here to check that.
        """
        given:
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "custom"
                name     = "Ada's \\\\agent\\\\ \\"helper\\""
                email    = "agent@example.com"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.status() == ExitStatus.SUCCESS
            gitValue('user.name') == 'Ada\'s \\agent\\ "helper"'
            gitValue('user.email') == 'agent@example.com'
    }

    def 'A lamp can give the agent no identity at all'() {
        reportInfo """
            `identity = "none"` restores the old behaviour: git in the sandbox refuses to commit
            until an identity is set there. That suits a user who wants no commits from the agent
            at all, or wants it to choose its own in ~/.gitconfig.
        """
        given:
            host.machine { it.gitIdentity('Ada Lovelace', 'ada@example.com') }
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "none"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.status() == ExitStatus.SUCCESS
            !Files.readString(sessionGitConfig()).contains('[user]')
            outcome.console().contains('the agent has no git identity')
    }

    def 'A user who asks for their own identity but has none is told the agent cannot commit, and the session still starts'() {
        reportInfo """
            Without git, or without an identity in it, there is nothing to copy. That is no reason
            to stop a session, but it is the reason the agent's first commit will fail, so the
            console says so and how to fix it, instead of leaving the user to find out from the
            agent.
        """
        given: 'a machine without git, and a lamp asking for the user\'s identity'
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "host"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.status() == ExitStatus.SUCCESS
            !Files.readString(sessionGitConfig()).contains('[user]')
            outcome.console().contains('your git configuration has no user.name and user.email')
    }

    def 'A custom identity without a name or an email is refused, before anything starts'() {
        reportInfo """
            `identity = "custom"` with an empty email would give git a half identity, and a commit
            would fail inside the sandbox for a reason set on the host. Configuration mistakes are
            reported where they are made, with the file and the key.
        """
        given:
            host.givenConfig(host.lampPath(), '''
                schema_version = 1
                [git]
                identity = "custom"
                name     = "Agent"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.status() != ExitStatus.SUCCESS
            outcome.console().contains('git.identity')
            outcome.console().contains('needs both git.name and git.email')
    }

    def 'The image makes every git in the sandbox read the session\'s identity'() {
        reportInfo """
            The session file only matters if git reads it. The image's /etc/gitconfig includes
            it from /oillamp/session, where the lamp's session directory is mounted. /etc/gitconfig
            is read by every git process whatever its environment, so a harness or an IDE started
            without a login shell gets the identity too. Real git follows that include here, with
            the path pointed at a written session file.
        """
        given: 'a session file, as oillamp writes it'
            host.oillamp.run('at', host.lampPath().toString())

        and: 'the image\'s /etc/gitconfig, pointed at it instead of at the mount'
            var system = Files.readString(Path.of('src/main/resources/image/rootfs/etc/gitconfig'))
            system.contains('path = /oillamp/session/gitconfig')
            var copy = tmp.resolve('gitconfig')
            Files.writeString(copy, system.replace('/oillamp/session/gitconfig', sessionGitConfig().toString()))

        expect:
            Spike.run('git', 'config', '--file', copy.toString(), '--includes', '--get', 'user.name')
                 .out.trim() == 'genie agent'
    }

    private Path sessionGitConfig() { host.lampPath().resolve('.oillamp/session/gitconfig') }

    /** A value from the session's file, as real git reads it. */
    private String gitValue(String key) {
        Spike.run('git', 'config', '--file', sessionGitConfig().toString(), '--get', key).out.trim()
    }
}
