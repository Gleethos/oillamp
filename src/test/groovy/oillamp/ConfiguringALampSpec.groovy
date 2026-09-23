package oillamp

import dev.oillamp.ExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 *  `oillamp.toml` is where a user says how big the desktop is, whether the screen is recorded,
 *  and - the part that matters most - where the agent is allowed to connect. It is the sandbox's
 *  policy, so a mistake in it has to be caught and explained rather than quietly ignored.
 */
class ConfiguringALampSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'Three mistakes in one file produce three located problems, in one run'() {
        reportInfo """
            This is the behaviour a configuration file lives or dies by. A user with three
            mistakes should be told about all three, each with the file, the key path, the value
            they wrote and what was expected - so they fix the file once.

            The alternative, stopping at the first mistake, turns a two-minute edit into a
            fix-run-fix-run loop. oillamp collects problems rather than throwing on the first,
            precisely so this scenario can hold.
        """
        given: 'a settings file with an unknown key, a malformed network range, and two forwards claiming the same port'
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [display]
                width   = 1920
                heigth  = 1080          # typo

                [[network.rules]]
                label  = "block the intranet"
                action = "deny"
                cidrs  = ["10.0.0/8"]   # not a valid network

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"

                [[network.forwards]]
                name   = "metrics"
                port   = 8000           # already taken by "llm"
                target = "metrics.corp.example.com:9090"
            '''.stripIndent())

        when: 'the user asks oillamp to check the configuration'
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then: 'the exit code says the problem is the configuration, not the machine'
            outcome.status() == ExitStatus.USAGE

        and: 'all three are reported'
            outcome.errors().size() == 3
            outcome.reported('OIL-CONFIG-002')      // unknown key
            outcome.reported('OIL-CONFIG-004')      // bad value

        and: 'the typo is located exactly, and the right key is suggested'
            outcome.console().contains('display.heigth')
            outcome.console().contains("did you mean 'height'?")

        and: 'the malformed network range is located down to the array element'
            outcome.console().contains('network.rules[0].cidrs')
            outcome.console().contains('10.0.0/8')

        and: 'and the port clash names the forward that already had the port'
            outcome.console().contains('network.forwards[1]')
            outcome.console().contains('already used by "llm"')
    }

    def 'An unknown key is an error, not something quietly ignored'() {
        reportInfo """
            This looks strict, and it is deliberate. The most dangerous typo in this file is one
            in a network rule: a rule key that oillamp silently ignores is a rule that does not
            apply, which is a hole in the sandbox that nothing announces.

            Since oillamp cannot tell a harmless typo from a dangerous one, every unknown key is
            refused - with the nearest known key suggested, because it is almost always a typo.
        """
        given:
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [netwrok]
                default = "deny"
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-002')
            outcome.console().contains("did you mean 'network'?")
    }

    def 'A lamp configuration must say which schema it was written for'() {
        reportInfo """
            The version marker is what lets a future oillamp migrate an old file instead of
            guessing at it. Requiring it costs the user one line and buys the project the
            ability to change this format later without breaking anyone's lamp.
        """
        given: 'a configuration with no schema_version'
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                [display]
                width = 1920
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('schema_version')
    }

    def 'A forward may not take the port the sandbox uses for its own proxy'() {
        reportInfo """
            Inside the sandbox, 127.0.0.1:3128 is the policy-controlled egress proxy. A forward
            bound to the same port would either fail to start or - worse - shadow the proxy, so
            that traffic the user believes is being checked against their rules silently is not.

            Better to refuse the configuration and say which port to move.
        """
        given:
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 3128
                target = "llm.corp.example.com:8000"
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('egress proxy')
    }

    def 'Pointing the language model at a forward that does not exist is caught'() {
        reportInfo """
            `llm.forward` names one of the `[[network.forwards]]` entries. A typo there would
            otherwise surface much later, as an agent tool that cannot reach any model and a user
            with no idea why. The message lists the forwards that do exist.
        """
        given:
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"

                [llm]
                forward = "language-model"
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('llm.forward')
            outcome.console().contains('"llm"')
    }

    def 'The commented file oillamp writes means exactly what oillamp does'() {
        reportInfo """
            A new lamp gets a fully commented `oillamp.toml`, which is the documentation most
            users will actually read. If it drifted from oillamp's built-in defaults it would be
            worse than no documentation at all - confidently wrong.

            So: create a lamp, then read back what the untouched file produces, and check it
            against the defaults oillamp reports for itself.
        """
        given: 'a freshly created lamp, with its shipped configuration file untouched'
            var lamp = sandbox.lampPath()
            sandbox.oillamp.run('at', lamp.toString())

        when: 'the user asks what the effective settings are'
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'it reads back the values the file documents'
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().contains('1920x1080')
            outcome.console().contains('gpu auto')
            outcome.console().contains('clipboard to-agent')
            outcome.console().contains('memory 16g')

        and: 'including the shipped policy: the open web, minus one rule that blocks the intranet'
            outcome.console().contains('default allow')
            outcome.console().contains('1 rule')

        and: 'the file itself is not readable by anyone but the user, since it is the sandbox policy'
            java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(lamp.resolve('oillamp.toml'))) == 'rw-------'
    }

    def 'A company-wide setting applies to every lamp, and a lamp can still override it'() {
        reportInfo """
            There is an optional file at `~/.config/oillamp/config.toml` that applies to every
            lamp this user creates. It exists so that a company can set something like the address
            of its language-model service once, instead of in every lamp separately.

            Three sources of settings are merged, and later ones win over earlier ones: oillamp's
            own built-in defaults first, then this user-wide file, then the individual lamp's
            `oillamp.toml`.

            Tables merge key by key, but arrays replace wholesale - which is not an arbitrary
            choice. If a lamp's `network.rules` were merged with the global list, the effective
            policy would be a union nobody wrote down and nobody could reason about. A lamp that
            lists rules gets exactly those rules.
        """
        given: 'a company-wide file setting the desktop size and a default policy'
            sandbox.givenGlobalConfig('''
                [display]
                width  = 2560
                height = 1440

                [[network.rules]]
                label  = "company mirror"
                action = "allow"
                hosts  = ["nexus.corp.example.com"]
            '''.stripIndent())

        and: 'a lamp that overrides only the width, and lists its own rules'
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [display]
                width = 3840

                [[network.rules]]
                label  = "no intranet"
                action = "deny"
                cidrs  = ["10.0.0.0/8"]
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the lamp\'s width wins, but the height from the company file still applies'
            outcome.console().contains('3840x1440')

        and: 'the lamp\'s rules REPLACE the company rules rather than being added to them'
            outcome.console().contains('1 rule')
    }

    def 'A file that is not valid TOML is reported as such, with the line that broke it'() {
        reportInfo """
            Before any of oillamp's own validation can run, the file has to parse. A syntax error
            gets its own problem code so the user is not left wondering whether they mistyped a
            key name or a bracket.
        """
        given:
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [display
                width = 1920
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-001')
            outcome.status() == ExitStatus.USAGE
    }

    def 'How long oillamp waits for the sandbox to start is set in the configuration'() {
        reportInfo """
            `timeouts.container_ready_seconds` is how long oillamp waits for the desktop and the
            shell to come up before it gives up. A slow machine needs more. The setting used to
            be accepted and then ignored.

            Straight after the image is built, the first start is slower, because nothing is
            cached yet, so oillamp waits twice as long then.
        """
        given:
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [timeouts]
                container_ready_seconds = 90
            '''.stripIndent())

        when: 'the image has to be built first'
            var afterABuild = sandbox.oillamp.run('at', lamp.toString(), '--dry-run')

        then:
            afterABuild.steps().any { it.contains('ready (up to 180s)') }

        when: 'the image is already there'
            sandbox.machine { it.commandSucceeding('podman image exists', '') }
            var withTheImage = sandbox.oillamp.run('at', lamp.toString(), '--dry-run')

        then:
            withTheImage.steps().any { it.contains('ready (up to 90s)') }
    }

    def 'Windows float by default, and a lamp can have them tiled instead'() {
        reportInfo """
            `display.windows` decides how the sandbox desktop arranges windows. "floating", the
            default, works like most desktops: a window opens at its own size, moves by its title
            bar and resizes by its edges. "tiling" splits the screen between the windows, so none
            covers another. The setting reaches the sandbox in `runtime.env`, and the sandbox
            accepts only these two words, because it turns them into compositor configuration.
        """
        given: 'a lamp that asks for tiling'
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [display]
                windows = "tiling"
            '''.stripIndent())

        when:
            sandbox.oillamp.run('at', lamp.toString())
            var runtimeEnv = java.nio.file.Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: 'the sandbox is told to tile'
            runtimeEnv.contains("OILLAMP_WINDOWS='tiling'")

        when: 'the lamp says nothing about windows'
            sandbox.givenConfig(lamp, 'schema_version = 1\n')
            var effective = sandbox.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'they float'
            effective.console().contains('floating windows')

        when: 'the lamp asks for something else'
            sandbox.givenConfig(lamp, '''
                schema_version = 1

                [display]
                windows = "stacking"
            '''.stripIndent())
            var checked = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then: 'it is refused, with the two values that work'
            checked.status() == ExitStatus.USAGE
            checked.reported('OIL-CONFIG-004')
            checked.console().contains('"floating" or "tiling"')
    }

    def 'Checking a configuration judges the same settings a session would use'() {
        reportInfo """
            A session merges the company-wide file `~/.config/oillamp/config.toml` with the lamp's
            own `oillamp.toml`. `oillamp config <dir> check` used to read only the lamp's file, so
            it could call a configuration valid that `oillamp at` then refused.
        """
        given: 'a valid lamp file, and a company-wide file with a mistake in it'
            var lamp = sandbox.lampPath()
            sandbox.givenConfig(lamp, 'schema_version = 1\n')
            sandbox.givenGlobalConfig('''
                [display]
                width = 10
            '''.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('config', lamp.toString(), 'check')

        then: 'the mistake is found, and located in the company-wide file'
            outcome.status() == ExitStatus.USAGE
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('.config/oillamp/config.toml')
    }
}
