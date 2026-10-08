package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Problem
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
    @Subject ScenarioHost host

    def setup() { host = new ScenarioHost(tmp) }

    def 'Running oillamp with no arguments shows what it can do'() {
        when:
            var outcome = host.oillamp.run()

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
            var command = host.oillamp.run('doctro')

        then:
            command.status() == ExitStatus.USAGE
            command.errors().first().whatHappened().contains("'doctro'")
            command.errors().first().whyItMatters().contains('nothing on your machine changed')

        when: 'or invents an option'
            var option = host.oillamp.run('doctor', '--fix-everything')

        then:
            option.status() == ExitStatus.USAGE
            option.errors().first().whatHappened().contains('--fix-everything')
    }

    def 'An option or argument the command does not take is refused, not ignored: oillamp #line'() {
        reportInfo """
            Each option means something to only some commands, and the others used to ignore
            it. That is worst when the option is a safety: `oillamp stop <dir> --dry-run` stopped
            the session, because only `at`, `remove` and `recordings` know what a dry run is.
            It also hides a misunderstanding, as with `oillamp at <dir> --view-only`, which opened
            an ordinary viewer. So an option the command does not take, or an argument too many,
            is a usage error, and the message shows what the command does take.
        """
        given:
            var lamp = host.lampPath().toString()

        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', lamp).split(' '))

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)
            outcome.console().contains(usage)

        where:
            line                               | complaint                        | usage
            'stop LAMP --dry-run'              | "`oillamp stop` does not take --dry-run" | 'oillamp stop <dir>'
            'status LAMP --yes'                | "`oillamp status` does not take --yes"   | 'oillamp status <dir>'
            'at LAMP --view-only'              | "`oillamp at` does not take --view-only" | 'oillamp at <dir> [--init]'
            'view LAMP --prune'                | "`oillamp view` does not take --prune"   | 'oillamp view <dir> [--view-only]'
            'list --open 20260101-120000'      | "`oillamp list` does not take --open"    | 'oillamp list'
            'list LAMP'                        | "`oillamp list` takes no arguments"      | 'oillamp list'
            'version extra'                    | "`oillamp version` takes no arguments"   | 'oillamp version'
            'help extra'                       | "`oillamp help` takes no arguments"      | 'oillamp help'
            'about extra'                      | "`oillamp about` takes no arguments"     | 'oillamp about'
            'guide extra'                      | "`oillamp guide` takes no arguments"     | 'oillamp guide'
            'genies extra'                     | "`oillamp genies` takes no arguments"    | 'oillamp genies'
            'genies --yes'                     | "`oillamp genies` does not take --yes"   | 'oillamp genies'
            'config LAMP path extra'           | "'extra'"                                | 'oillamp config <dir> (check | show-effective | path)'
            'completion bash zsh'              | "'zsh'"                                  | 'oillamp completion bash'
            'restore LAMP a1b2c3d4 extra'      | "'extra'"                                | 'oillamp restore <dir> <snapshot>'
            'ask LAMP hello extra'             | "'extra'"                                | 'oillamp ask <dir> [--in'
            'conversations LAMP c1 extra'      | "'extra'"                                | 'oillamp conversations <dir>'
            'cancel LAMP run-1 extra'          | "'extra'"                                | 'oillamp cancel <dir>'
            'schedule LAMP remove job-1 extra' | "'extra'"                                | 'oillamp schedule <dir>'
            'stop LAMP -y'                     | "`oillamp stop` does not take --yes"     | 'oillamp stop <dir>'
            'history LAMP -m note'             | "`oillamp history` does not take --message" | 'oillamp history <dir>'
            'stop LAMP --message=note'         | "`oillamp stop` does not take --message" | 'oillamp stop <dir>'
            'shell LAMP --embedded'            | "`oillamp shell` does not take --embedded" | 'oillamp shell <dir>'
            'list --embedded'                  | "`oillamp list` does not take --embedded"  | 'oillamp list'
            'stop LAMP --init'                 | "`oillamp stop` does not take --init"    | 'oillamp stop <dir>'
            'status LAMP --no-viewer'          | "`oillamp status` does not take --no-viewer" | 'oillamp status <dir>'
            'status LAMP --no-windows'         | "`oillamp status` does not take --no-windows" | 'oillamp status <dir>'
            'view LAMP --model-service https://api.eu.edenai.run' | "`oillamp view` does not take --model-service" | 'oillamp view <dir>'
            'view LAMP --model-key-env EDENAI_API_KEY' | "`oillamp view` does not take --model-key-env" | 'oillamp view <dir>'
            'stop LAMP --enable-scheduling'    | "`oillamp stop` does not take --enable-scheduling" | 'oillamp stop <dir>'
            'history LAMP --in c1'             | "`oillamp history` does not take --in"   | 'oillamp history <dir>'
            'history LAMP --after e1'          | "`oillamp history` does not take --after" | 'oillamp history <dir>'
            'history LAMP --instead-of e1'     | "`oillamp history` does not take --instead-of" | 'oillamp history <dir>'
            'stop LAMP --no-wait'              | "`oillamp stop` does not take --no-wait" | 'oillamp stop <dir>'
            'save LAMP --cron @daily'          | "`oillamp save` does not take --cron"    | 'oillamp save <dir>'
            'save LAMP --at in-2h'             | "`oillamp save` does not take --at"      | 'oillamp save <dir>'
            'save LAMP --expires in-3d'        | "`oillamp save` does not take --expires" | 'oillamp save <dir>'
            'status LAMP --prune'              | "`oillamp status` does not take --prune" | 'oillamp status <dir>'
    }

    def 'The options a command does take are still accepted: oillamp #line'() {
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() != ExitStatus.USAGE || !outcome.errors().any { it.whatHappened().contains('does not take') }

        where:
            line << ['--verbose doctor', 'doctor --dry-run', 'at LAMP --dry-run --init --no-viewer --no-install',
                     'at LAMP --dry-run --no-windows',
                     'remove LAMP --dry-run', 'remove LAMP -y', 'recordings LAMP --prune --dry-run', '--no-color list',
                     'config LAMP check', 'completion bash',
                     'at LAMP --dry-run --model-service https://api.eu.edenai.run --model-key-env EDENAI_API_KEY',
                     'at LAMP --dry-run --enable-scheduling', 'doctor --no-install',
                     'config LAMP check --dry-run --no-install', 'view LAMP --view-only',
                     'recordings LAMP --open 20260101-120000', 'remove LAMP --yes --embedded',
                     'save LAMP --message note --embedded', 'history LAMP --embedded',
                     'restore LAMP a1b2c3d4 --embedded', 'stop LAMP --embedded', 'status LAMP --embedded',
                     'follow LAMP --embedded', 'cancel LAMP --embedded', 'conversations LAMP --embedded',
                     'schedule LAMP --embedded', 'schedule LAMP add --at in-2h --expires in-3d hello',
                     'ask LAMP --no-wait --embedded hello']
    }

    def 'Asking to set up a lamp without saying where is a usage error, not a crash'() {
        when:
            var outcome = host.oillamp.run('at')

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.console().contains('oillamp at <dir>')
    }

    def 'The version is reported without needing a machine or a lamp'() {
        when:
            var outcome = host.oillamp.run('version')

        then:
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().contains('oillamp 0.3.0')

        and: 'and the Java it runs on, which a bug report needs as well'
            outcome.console().contains('java ' + Runtime.version())
    }

    def 'Someone new can learn what oillamp is for, and how to use it, from oillamp itself'() {
        reportInfo """
            `help` lists the commands, which is enough once you know what they are for. `about`
            and `guide` are for the moment before that. Like `help`, they need no machine and
            no lamp, and change nothing.
        """
        when: 'the user asks what oillamp is'
            var about = host.oillamp.run('about')

        then: 'they learn why it exists and what it is built from'
            about.status() == ExitStatus.SUCCESS
            about.console().contains('WHY IT EXISTS')
            about.console().contains('podman')
            about.console().contains('oillamp 0.3.0')

        when: 'and how to get going'
            var guide = host.oillamp.run('guide')

        then: 'they get the steps in the order they need them, from checking the machine to deleting the lamp'
            guide.status() == ExitStatus.SUCCESS
            var steps = ['oillamp doctor', 'oillamp at ~/lamps/first', 'oillamp stop ~/lamps/first',
                         'oillamp remove ~/lamps/first --yes'].collect { guide.console().indexOf(it) }
            steps.every { it >= 0 }
            steps == steps.sort(false)

        and: 'it says how to run a session where no window can open, since --no-viewer is not that'
            guide.console().contains('oillamp at ~/lamps/first --no-windows')
            guide.console().contains('--no-viewer is not the same')

        and: 'it names the keys for the desktop\'s windows, since sway draws no buttons on them'
            ['Ctrl+Super+F', 'Ctrl+Super+Q', 'Ctrl+Super+M', 'Ctrl+Super+N'].every { guide.console().contains(it) }

        and: 'the command list sends newcomers to both'
            var help = host.oillamp.run('help')
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
            host.machine { it.generatedAgentId('NOT A VALID ID') }

        when: 'the user tries to set up a lamp'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

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
            var lamp = host.lampPath()

        when:
            var outcome = host.oillamp.run('view', lamp.toString())

        then: 'oillamp says so, and names the way out'
            !outcome.succeeded()
            outcome.errors().first().whatHappened().contains('not an oillamp lamp')

        and: 'and the directory is still not there, because asking created nothing'
            !java.nio.file.Files.exists(lamp)
    }

    def '--open without a session to play is a usage error, not a silent listing'() {
        when:
            var outcome = host.oillamp.run('recordings', host.lampPath().toString(), '--open')

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
            var coloured = host.oillamp.run('doctor')
            var plain = host.oillamp.run('doctor', '--no-color')

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
            host.givenConfig(host.lampPath(), """
                schema_version = 1

                [display]
                size = "huge"
                """.stripIndent())

        when:
            var outcome = host.oillamp.run('doctor', host.lampPath().toString())

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().any { it.code().value().startsWith('OIL-CONFIG-') }
            outcome.console().contains('size')
    }

    def 'config path prints where the lamp\'s configuration lives'() {
        when:
            var outcome = host.oillamp.run('config', host.lampPath().toString(), 'path')

        then:
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().trim().endsWith(host.lampPath().resolve('oillamp.toml').toString())
    }

    def 'The completion script is only the script, and completes what oillamp accepts'() {
        reportInfo """
            `eval "\$(oillamp completion bash)"` runs whatever oillamp prints, so the output must
            be the script and nothing else: no banner, no colour. And the completions must match
            the command line oillamp parses. In `oillamp config <dir> check`, the directory comes
            first, so the word after `config` is a path and the one after that is the action.
        """
        when:
            var outcome = host.oillamp.run('completion', 'bash')

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

        and: 'it offers each command only the options that command takes'
            complete(script, 'oillamp', 'stop', '/x', '--d') == ['--debug']
            complete(script, 'oillamp', 'view', '/x', '--v').containsAll(['--view-only', '--verbose'])
            !complete(script, 'oillamp', 'at', '/x', '--').contains('--yes')
            complete(script, 'oillamp', 'completion', '') == ['bash']
            complete(script, 'oillamp', 'list', '').isEmpty()
            complete(script, 'oillamp', 'gen') == ['genies']
            complete(script, 'oillamp', 'genies', '').isEmpty()
    }

    def 'An option\'s value can follow it, or be joined to it with =: oillamp #line'() {
        reportInfo """
            `--model-service https://x` and `--model-service=https://x` mean the same, as they
            do for most command-line tools, and that holds for every option that takes a value.
            Each pair of lines here is refused for the same reason and quotes the same value,
            which shows that both spellings handed oillamp the same thing. A value joined with =
            is quoted without the =.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        where:
            line                                                   | complaint
            'at LAMP --model-service ftp://models.example'         | '--model-service "ftp://models.example": expected a scheme'
            'at LAMP --model-service=ftp://models.example'         | '--model-service "ftp://models.example": expected a scheme'
            'at LAMP --model-key-env not-a-name'                   | '--model-key-env "not-a-name": expected the name of an environment variable'
            'at LAMP --model-key-env=not-a-name'                   | '--model-key-env "not-a-name": expected the name of an environment variable'
            'ask LAMP --after e1 hello'                            | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --after=e1 hello'                            | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --instead-of e1 hello'                       | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --instead-of=e1 hello'                       | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --in c1 --after e1 --instead-of e2 hello'    | 'either after an entry or instead of a question, not both'
            'ask LAMP --in=c1 --after=e1 --instead-of=e2 hello'    | 'either after an entry or instead of a question, not both'
            'schedule LAMP list --cron @daily'                     | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP list --cron=@daily'                     | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP pause --at in-2h'                       | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP pause --at=in-2h'                       | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP resume --expires in-3d'                 | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP resume --expires=in-3d'                 | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
    }

    def 'An option given last, with no value after it, says what value it needs: oillamp #line'() {
        reportInfo """
            An option that takes a value and comes last on the line was usually cut short, or
            the value was lost to quoting in the shell. Running without it could do something
            other than what was meant, so oillamp stops, and shows an example of the value.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)
            outcome.console().contains(usage)

        where:
            line                           | complaint                                                         | usage
            'save LAMP --message'          | '--message needs the text to save with, for example --message "before the upgrade"' | 'oillamp save <dir>'
            'save LAMP -m'                 | '--message needs the text to save with'                           | 'oillamp save <dir>'
            'ask LAMP hello --in'          | '--in needs a conversation, as `oillamp conversations` lists them' | 'oillamp ask <dir>'
            'ask LAMP hello --after'       | '--after needs an entry, as `oillamp conversations <dir> <conversation>` shows them' | 'oillamp ask <dir>'
            'ask LAMP hello --instead-of'  | '--instead-of needs an entry'                                     | 'oillamp ask <dir>'
            'schedule LAMP add hi --cron'  | '--cron needs a value, for example --cron "0 9 * * 1-5"'          | 'oillamp schedule <dir>'
            'schedule LAMP add hi --at'    | '--at needs a value, for example --at "2026-10-01 09:00"'         | 'oillamp schedule <dir>'
            'schedule LAMP add hi --expires' | '--expires needs a value, for example --expires "in 14d"'       | 'oillamp schedule <dir>'
            'at LAMP --model-service'      | '--model-service needs a value, for example --model-service https://api.eu.edenai.run' | 'oillamp at <dir>'
            'at LAMP --model-key-env'      | '--model-key-env needs a value, for example --model-key-env MY_MODEL_KEY' | 'oillamp at <dir>'
    }

    def 'A model service or key variable that cannot be right is refused before anything starts: oillamp #line'() {
        reportInfo """
            `--model-service` and `--model-key-env` override the lamp's [model] settings for one
            session. The key is sent to that service, so an address that is not https, or a
            variable name the shell could never have set, is refused at once, in the same words
            as the same mistake in oillamp.toml would be.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        and: 'nothing was set up'
            !Files.exists(host.lampPath())

        where:
            line                                                | complaint
            'at LAMP --model-service http://models.example'     | 'it must be https://'
            'at LAMP --model-service https://models.example?x'  | 'expected a scheme, a host'
            'at LAMP --model-key-env 1PASSWORD'                 | 'expected the name of an environment variable, such as EDENAI_API_KEY'
            'at LAMP --model-key-env $EDENAI_API_KEY'           | 'expected the name of an environment variable'
    }

    def 'After --, every argument is taken as written, even one that looks like an option'() {
        reportInfo """
            A prompt may well start with a dash, such as a list of things to do. Without `--`,
            oillamp would take `-` followed by anything as an option it does not know. After
            `--`, it takes everything as it is written. The lamp here has no session, so the
            question is not asked; what matters is that oillamp got as far as looking for one,
            rather than refusing the line.
        """
        when: 'a prompt that starts with a dash comes after --'
            var literal = host.oillamp.run('ask', host.lampPath().toString(), '--', '--list the open tickets')

        then: 'it is not mistaken for an option'
            literal.status() != ExitStatus.USAGE
            !literal.errors().any { it.whatHappened().contains('is not an option oillamp knows') }

        when: 'the same prompt without --'
            var option = host.oillamp.run('ask', host.lampPath().toString(), '--list the open tickets')

        then: 'it is taken for an option, and refused'
            option.status() == ExitStatus.USAGE
            option.errors().first().whatHappened().contains("'--list the open tickets' is not an option oillamp knows")
    }

    def '-v is --verbose, and --debug turns it on too'() {
        reportInfo """
            `--verbose` shows each step oillamp takes as it goes, not only the outcome. `-v` is
            its short form. `--debug` does nothing more than `--verbose` yet, but it promises at
            least as much, so it turns verbose output on as well.
        """
        given:
            var lamp = host.lampPath().toString()

        when: 'the same dry run, plain and with each of the three'
            var quiet = host.oillamp.run('at', lamp, '--dry-run')
            var verbose = host.oillamp.run('--verbose', 'at', lamp, '--dry-run')
            var shortForm = host.oillamp.run('-v', 'at', lamp, '--dry-run')
            var debug = host.oillamp.run('--debug', 'at', lamp, '--dry-run')

        then: 'verbose output shows more than the plain one'
            verbose.console().length() > quiet.console().length()

        and: 'and the other two show the same'
            shortForm.console() == verbose.console()
            debug.console() == verbose.console()
    }

    def 'A command that works on a lamp says so when no lamp is given: oillamp #command'() {
        reportInfo """
            Every one of these commands acts on one lamp directory, and none can guess which.
            Without one, the answer is a usage error that names the missing directory, rather
            than a crash or a guess at the current directory.
        """
        when:
            var outcome = host.oillamp.run(command)

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("`oillamp ${command}` needs the path of a lamp directory")
            outcome.console().contains("oillamp ${command} <dir>")

        where:
            command << ['at', 'view', 'shell', 'stop', 'status', 'follow', 'recordings', 'config', 'remove',
                        'save', 'history', 'restore', 'schedule', 'ask', 'conversations', 'cancel']
    }

    def 'A command that works on one lamp refuses several, rather than acting on the first: oillamp #command'() {
        reportInfo """
            A shell pattern such as `test*` easily expands to more than one directory. A
            command that quietly acted on the first and ignored the rest would look as if it
            had done them all, so it refuses, and lists what it was given. `remove` is the one
            command that takes several lamps on purpose.
        """
        given:
            var first = host.lampPath('one').toString()
            var second = host.lampPath('two').toString()

        when:
            var outcome = host.oillamp.run(command, first, second)

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened()
                    .contains("`oillamp ${command}` works on one lamp, but was given 2 directories (${first}, ${second}); run it once for each")

        where:
            command << ['at', 'view', 'shell', 'stop', 'status', 'follow', 'recordings', 'doctor', 'save', 'history']
    }

    def 'The first line names the lamp the command works on'() {
        reportInfo """
            Commands that set up or change a lamp start with a line naming oillamp, its version
            and the lamp, so that a terminal with several sessions in it, or a pasted log, shows
            which lamp each part is about. `remove` says how many lamps it was given instead.
        """
        given:
            var lamp = host.lampPath().toString()

        expect:
            host.oillamp.run('doctor', lamp).console().contains("oillamp 0.3.0 — ${lamp}")
            host.oillamp.run('at', lamp, '--dry-run').console().contains("oillamp 0.3.0 — ${lamp}")
            host.oillamp.run('save', lamp).console().contains("oillamp 0.3.0 — ${lamp}")
            host.oillamp.run('restore', lamp, 'a1b2c3d4').console().contains("oillamp 0.3.0 — ${lamp}")
            host.oillamp.run('remove', lamp, host.lampPath('other').toString()).console().contains('oillamp 0.3.0 — 2 lamps')
    }

    def 'config checks the lamp when no action is given, and refuses an action it does not know'() {
        reportInfo """
            `oillamp config <dir>` on its own checks the configuration, which is what someone
            who just edited oillamp.toml wants. `show-effective` prints every setting as a
            session would use it, which a check does not. An action oillamp does not know is a
            usage error, not a silent check.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')

        when:
            var bare = host.oillamp.run('config', lamp.toString())
            var check = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')
            var unknown = host.oillamp.run('config', lamp.toString(), 'fix')

        then: 'with no action, it checks'
            bare.status() == ExitStatus.SUCCESS
            bare.console() == check.console()

        and: 'show-effective shows more than the check does'
            shown.status() == ExitStatus.SUCCESS
            shown.console() != check.console()
            shown.events().any { it instanceof dev.lamp.LampEvent.Answer }
            !check.events().any { it instanceof dev.lamp.LampEvent.Answer }

        and: 'an unknown action is refused, with the actions there are'
            unknown.status() == ExitStatus.USAGE
            unknown.errors().first().whatHappened().contains("'fix' is not a config action")
            unknown.console().contains('oillamp config <dir> (check | show-effective | path)')
    }

    def 'The completion script is for bash, also when no shell is named'() {
        reportInfo """
            oillamp ships a completion script for bash only. So `oillamp completion` with no
            shell gives that script, and naming another shell is refused: a bash script handed
            to zsh would fail in ways that are hard to trace back to oillamp.
        """
        when:
            var unnamed = host.oillamp.run('completion')
            var zsh = host.oillamp.run('completion', 'zsh')

        then: 'with no shell named, it is the bash script'
            unnamed.status() == ExitStatus.SUCCESS
            unnamed.console() == host.oillamp.run('completion', 'bash').console()

        and: 'another shell is refused, rather than given a script it cannot run'
            zsh.status() == ExitStatus.USAGE
            zsh.errors().first().whatHappened().contains("oillamp only ships a completion script for bash, not 'zsh'")
    }

    def '`help` lists the commands, and is not an error'() {
        reportInfo """
            Running oillamp with nothing lists the commands too, but as a usage error, since
            nothing was asked. `oillamp help` asked for the list, so it succeeds.
        """
        when:
            var outcome = host.oillamp.run('help')

        then:
            outcome.status() == ExitStatus.SUCCESS
            outcome.console().contains('at <dir>')
            outcome.console().contains('doctor [<dir>]')
            outcome.console().contains('genies')
    }

    def '`oillamp genies` opens Genies on the Java oillamp runs on, and says so when it cannot'() {
        reportInfo """
            Genies, the desktop app for chatting with agents, is built on oillamp and comes in
            the same file. `oillamp genies` starts it as a program of its own, on the Java runtime
            and classpath oillamp itself runs on, and waits in the terminal until its window is
            closed. If that program cannot be started, oillamp says what it tried to start. Here
            it cannot: a simulated machine has only the programs it was given.
        """
        when:
            var outcome = host.oillamp.run('genies')

        then:
            outcome.status() == ExitStatus.ERROR
            var problem = outcome.errors().first()
            problem.code().value() == 'OIL-GENIES-001'
            var tried = (problem.evidence().first() as Problem.Evidence.Command).argv()
            tried.toList() == [Path.of(System.getProperty('java.home'), 'bin', 'java').toString(),
                               '-cp', System.getProperty('java.class.path'), 'dev.gui.Genies']
    }

    def 'Asking for the image command says why there is none'() {
        reportInfo """
            An `image` command was planned and then dropped, since oillamp rebuilds the sandbox
            image by itself whenever anything in it changes. Someone who read about it, or
            expects one from other container tools, is told that, not just "unknown command".
        """
        when:
            var outcome = host.oillamp.run('image')

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("there is no 'image' command: oillamp rebuilds the sandbox image by itself")
    }

    def 'ask needs a prompt, and schedule needs what its action acts on: oillamp #line'() {
        reportInfo """
            These lines name a lamp but leave out the one thing the command needs to act: the
            question to ask, the snapshot to go back to, the prompt for a new job, or the job to
            change. Each is refused, and says what is missing, before oillamp looks for a lamp
            or a session.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        where:
            line                    | complaint
            'ask LAMP'              | '`oillamp ask` needs something to ask the agent, in quotes'
            'restore LAMP'          | '`oillamp restore` needs the snapshot to go back to'
            'schedule LAMP add'     | '`oillamp schedule <dir> add` needs the prompt the agent is woken with, in quotes'
            'schedule LAMP remove'  | '`oillamp schedule <dir> remove` needs the job, such as job-3'
            'schedule LAMP enable'  | '`oillamp schedule <dir> enable` needs the job, such as job-3'
            'schedule LAMP disable' | '`oillamp schedule <dir> disable` needs the job, such as job-3'
            'schedule LAMP pause x' | "`oillamp schedule <dir> pause` takes nothing more, but was given 'x'"
    }

    def 'An action schedule does not know is refused before oillamp looks for the lamp'() {
        reportInfo """
            `oillamp schedule <dir>` lists, adds, removes, enables, disables, pauses and resumes.
            Any other word is a usage error, and it is said before oillamp looks for the lamp,
            so a typo is reported as a typo and not as a lamp that is missing.
        """
        when:
            var outcome = host.oillamp.run('schedule', host.lampPath().toString(), 'tidy')

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("'tidy' is not something `oillamp schedule` does")
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
