package oillamp

import dev.lamp.ExitStatus
import dev.lamp.LampEvent
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  A session started by another application, such as a game that hosts an agent, rather than by
 *  a person at a terminal.
 *
 *  <p>The application starts {@code oillamp at <dir> --embedded} as a child process and keeps
 *  its standard input open for as long as it wants the sandbox. It has its own ways into the
 *  sandbox, so oillamp opens no windows. The simulated machine plays the application: it closes
 *  oillamp's standard input after a while, as a real one does when it is done or when it dies.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class EmbeddingASessionSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'An embedded session opens no windows, and ends when the application lets go of it'() {
        reportInfo """
            A window popping up on the player's desktop would be a surprise, and a terminal that
            nobody asked for would be closed, so neither opens. The session is up as soon as the
            sandbox answers.

            It ends when oillamp's standard input closes. That happens when the application is
            done, and also when it crashes, because the operating system closes the pipe for it.
            So the sandbox never outlives the application that wanted it.
        """
        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--embedded')

        then: 'the session ran and ended cleanly'
            outcome.status() == ExitStatus.SUCCESS

        and: 'no window was opened'
            outcome.events().findAll { it instanceof LampEvent.WindowOpened }.isEmpty()

        and: 'it was running without anyone connecting a terminal'
            outcome.events().any { it instanceof LampEvent.SessionStateChanged
                                   && it.status().state() == 'running' }

        and: 'it told the application how to run commands in the sandbox: over ssh, without a terminal'
            var opened = outcome.events().find { it instanceof LampEvent.SessionOpened }
            opened.command().first() == 'ssh'
            opened.command().toList().contains('-T')
            opened.command().any { it.contains('UNIX-CONNECT:') && it.endsWith('/ssh.sock') }

        and: 'it ended because the application let go, and left nothing behind'
            outcome.events().any { it instanceof LampEvent.Summary
                                   && it.lines().toList().any { line ->
                                          line.contains('asked to stop by the application') } }
            !Files.exists(sandbox.lampPath().resolve('.oillamp/session.json'))
    }

    def 'An embedded oillamp reports on standard output in JSON, one event per line, and nothing else'() {
        reportInfo """
            The application reads standard output to show the player what the sandbox is doing.
            A banner, a coloured line or a progress spinner in between would be a line it cannot
            read, so in embedded mode every line is an event, and every event is a line.
        """
        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--embedded')

        then: 'every line of output reads back as an event'
            var lines = outcome.console().readLines()
            var read = lines.collect { LampEvent.fromJson(it) }
            read.every { it.present }

        and: 'and together they are exactly what the session reported, in order'
            read.collect { it.get() } == outcome.events().toList()
    }

    def 'An embedded session is not given up for want of a terminal'() {
        reportInfo """
            A normal session that no terminal connects to is ended after
            `timeouts.terminal_connect_seconds`, because nobody is in it. An embedded session never
            has that terminal, so the timeout must not apply, or every embedded session would end
            after a minute.
        """
        given: 'a short terminal timeout, and an application that keeps the session longer'
            sandbox.machine { it.clockRuns().applicationLeavesAfter(Duration.ofSeconds(3)) }
            sandbox.givenConfig(sandbox.lampPath(), """
                schema_version = 1
                [timeouts]
                terminal_connect_seconds = 1
            """.stripIndent())

        when:
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--embedded')

        then:
            outcome.status() == ExitStatus.SUCCESS
            !outcome.reported('OIL-TERM-002')
    }
}
