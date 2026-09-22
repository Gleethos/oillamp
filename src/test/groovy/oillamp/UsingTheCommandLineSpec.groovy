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

    def 'A bug inside oillamp still reaches the user as something they can report'() {
        reportInfo """
            NFR-03: no bare stack traces on the console. That has to hold even for a genuine bug,
            because the moment a user sees a stack trace is the moment they stop being able to
            tell "I did something wrong" from "this tool is broken".

            An unexpected failure therefore becomes a normal problem, with a code, the advice to
            re-run with --debug, and the trace kept as evidence rather than printed raw.
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
            outcome.errors().first().fixes().any { it.description().contains('--debug') }
    }

    def 'Commands that need a running session say so plainly'() {
        reportInfo """
            `view`, `shell` and `stop` are listed in the help but only mean something once a
            session exists. Until the session milestone lands, "not implemented yet" is a far
            better answer than "unknown command", which would make the user doubt the help text.
        """
        when:
            var outcome = sandbox.oillamp.run('view', sandbox.lampPath().toString())

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.errors().first().whatHappened().contains('running session')
    }
}
