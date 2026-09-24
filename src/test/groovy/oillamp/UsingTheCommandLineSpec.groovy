package oillamp

import dev.oillamp.ExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 *  The first thing anyone does with a new tool is type it wrong. These scenarios are about what
 *  happens then - and about the exit codes, which are a contract for anything wrapping oillamp
 *  in a script.
 */
class UsingTheCommandLineSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() { sandbox = new Sandbox(tmp) }

    def 'Running oillamp with no arguments shows what it can do'() {
        when:
            var outcome = sandbox.oillamp.run()

        then: 'the user is shown the commands rather than an error about nothing'
            outcome.console().contains('at <dir>')
            outcome.console().contains('doctor [<dir>]')
            outcome.console().contains('config <dir>')

        and: 'and it is still a usage error, so a script notices'
            outcome.status() == ExitStatus.USAGE
            outcome.status().code() == 2
    }

    def 'A mistyped command or option is refused before anything happens'() {
        reportInfo """
            oillamp installs packages and writes to the user's home. Acting on a half-understood
            command line would be the worst possible moment to be helpful, so an unrecognised
            command or option stops everything, and the message says so explicitly.
        """
        when: 'the user mistypes the command'
            var command = sandbox.oillamp.run('doctro')

        then:
            command.status() == ExitStatus.USAGE
            command.errors().first().whatHappened().contains("'doctro'")
            command.errors().first().whyItMatters().contains('nothing on your machine changed')

        when: 'or invents an option'
            var option = sandbox.oillamp.run('doctor', '--fix-everything')

        then:
            option.status() == ExitStatus.USAGE
            option.errors().first().whatHappened().contains('--fix-everything')
    }

    def 'Asking to set up a lamp without saying where is a usage error, not a crash'() {
        when:
            var outcome = sandbox.oillamp.run('at')

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.console().contains('oillamp at <dir>')
    }

    def 'The version is reported without needing a machine or a lamp'() {
        when:
            var outcome = sandbox.oillamp.run('version')

        then:
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().contains('oillamp 0.1.0')
    }

    def 'Someone new can learn what oillamp is for, and how to use it, from oillamp itself'() {
        reportInfo """
            `help` lists the commands, which is enough once you know what they are for. `about`
            and `guide` are for the moment before that. Like `help`, they need no machine and
            no lamp, and change nothing.
        """
        when: 'the user asks what oillamp is'
            var about = sandbox.oillamp.run('about')

        then: 'they learn why it exists and what it is built from'
            about.status() == ExitStatus.SUCCESS
            about.console().contains('WHY IT EXISTS')
            about.console().contains('podman')
            about.console().contains('oillamp 0.1.0')

        when: 'and how to get going'
            var guide = sandbox.oillamp.run('guide')

        then: 'they get the steps in the order they need them, from checking the machine to deleting the lamp'
            guide.status() == ExitStatus.SUCCESS
            var steps = ['oillamp doctor', 'oillamp at ~/lamps/first', 'oillamp stop ~/lamps/first',
                         'oillamp remove ~/lamps/first --yes'].collect { guide.console().indexOf(it) }
            steps.every { it >= 0 }
            steps == steps.sort(false)

        and: 'the command list sends newcomers to both'
            var help = sandbox.oillamp.run('help')
            help.console().contains('oillamp guide')
            help.console().contains('oillamp about')
    }

    def 'A bug inside oillamp still reaches the user as something they can report'() {
        reportInfo """
            No bare stack traces on the console, ever. That has to hold even for a genuine bug,
            because the moment a user sees a stack trace is the moment they stop being able to
            tell "I did something wrong" from "this tool is broken".

            An unexpected failure therefore becomes a normal problem, with a code, the advice to
            re-run with --verbose, and the start of the trace kept as evidence rather than printed
            raw.
        """
        given: 'a machine whose id lookup returns something impossible'
            sandbox.machine { it.generatedAgentId('NOT A VALID ID') }

        when: 'the user tries to set up a lamp'
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString())

        then: 'they get a problem, not a stack trace'
            outcome.reported('OIL-INTERNAL-001')
            outcome.status() == ExitStatus.ERROR
            !outcome.console().contains('\tat dev.oillamp')

        and: 'with the advice that makes a useful bug report'
            outcome.errors().first().fixes().any { it.description().contains('--verbose') }
    }

    def 'Commands that need a running session say which lamp has none'() {
        reportInfo """
            `view`, `shell`, `stop` and `status` are questions put to a session that is already
            running. Asked of a directory that is not a lamp at all, the honest answer names the
            directory and offers the command that would make one - rather than "no session",
            which would send the user looking for a session they never started.

            They must also change nothing on the way to answering. A `status` that quietly
            created or repaired a lamp would be the last thing anyone wants from a command whose
            whole purpose is to report.
        """
        given: 'a directory that was never made into a lamp'
            var lamp = sandbox.lampPath()

        when:
            var outcome = sandbox.oillamp.run('view', lamp.toString())

        then: 'oillamp says so, and names the way out'
            !outcome.succeeded()
            outcome.errors().first().whatHappened().contains('not an oillamp lamp')

        and: 'and the directory is still not there, because asking created nothing'
            !java.nio.file.Files.exists(lamp)
    }

    def '--open without a session to play is a usage error, not a silent listing'() {
        when:
            var outcome = sandbox.oillamp.run('recordings', sandbox.lampPath().toString(), '--open')

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.console().contains('--open needs the session to play')
    }

    def '--no-color leaves no colour codes in the output'() {
        reportInfo """
            Colour codes are invisible on a terminal but end up as junk in a log file or a
            pasted bug report. `NO_COLOR=1` turned them off before; the `--no-color` option was
            listed in the help but did nothing.
        """
        when: 'the same command runs on a terminal, with and without the option'
            var coloured = sandbox.oillamp.run('doctor')
            var plain = sandbox.oillamp.run('doctor', '--no-color')

        then:
            coloured.console().contains('\u001B[')
            !plain.console().contains('\u001B[')
    }

    def 'Checking a lamp whose configuration is broken says what is wrong, and exits as a usage error'() {
        reportInfo """
            `oillamp doctor <dir>` checks the machine and then the lamp's configuration. A mistake
            in oillamp.toml is the user's to fix, so it is reported with the file and key, and the
            exit code is 2, the same as for any other usage error, so a script can tell it apart
            from a machine that cannot run sandboxes (3).
        """
        given:
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1

                [display]
                size = "huge"
                """.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('doctor', sandbox.lampPath().toString())

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().any { it.code().value().startsWith('OIL-CONFIG-') }
            outcome.console().contains('size')
    }

    def 'config path prints where the lamp\'s configuration lives'() {
        when:
            var outcome = sandbox.oillamp.run('config', sandbox.lampPath().toString(), 'path')

        then:
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().trim().endsWith(sandbox.lampPath().resolve('oillamp.toml').toString())
    }

    def 'The completion script is only the script, and completes what oillamp accepts'() {
        reportInfo """
            `eval "\$(oillamp completion bash)"` runs whatever oillamp prints, so the output must
            be the script and nothing else: no banner, no colour. And the completions must match
            the command line oillamp parses. In `oillamp config <dir> check`, the directory comes
            first, so the word after `config` is a path and the one after that is the action.
        """
        when:
            var outcome = sandbox.oillamp.run('completion', 'bash')

        then: 'nothing but the script is printed'
            outcome.status() == ExitStatus.SUCCESS
            !outcome.console().contains('🪔')
            !outcome.console().contains('\u001B[')

        and: 'bash accepts it and completes commands, options and config actions'
            var script = tmp.resolve('completion.bash')
            Files.writeString(script, outcome.console())
            complete(script, 'oillamp', 'sta').containsAll(['status'])
            complete(script, 'oillamp', 'at', '/x', '--dry').containsAll(['--dry-run'])
            !complete(script, 'oillamp', 'config', '').contains('check')
            complete(script, 'oillamp', 'config', '/x', '').containsAll(['check', 'show-effective', 'path'])
    }

    /** What bash offers for the last word, using the completion script oillamp printed. */
    private static List<String> complete(Path script, String... words) {
        var program = """
            source '${script}'
            COMP_WORDS=(${words.collect { "'" + it + "'" }.join(' ')})
            COMP_CWORD=${words.length - 1}
            _oillamp
            printf '%s\\n' "\${COMPREPLY[@]}"
            """.stripIndent()
        var process = new ProcessBuilder('bash', '-c', program).redirectErrorStream(true).start()
        var output = process.inputStream.text
        assert process.waitFor() == 0 : output
        output.readLines().findAll { !it.isBlank() }
    }
}
