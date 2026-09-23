package oillamp

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The `lamp` command the agent uses to drive the desktop.
 *
 * <p>`lamp` is a shell script, so it cannot be unit-tested the way the Java can. It can still be
 * <em>run</em>: these scenarios put stubs for grim, wtype and wlrctl on the PATH, invoke the real
 * script, and assert on the argv it produced. That catches the failures that actually happen to
 * shell scripts (a wrongly built argument list, a quoting bug, an option in the wrong order) without
 * needing a compositor or a container.
 *
 * <p>Not tagged {@code spike}: there is no third-party assumption here and nothing to install, so
 * this belongs in the fast suite that always runs.
 */
class TheLampCommandSpec extends Specification {

    @TempDir Path temporary

    Path lamp
    Path stubs
    Path calls

    def setup() {
        lamp = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()
        stubs = Files.createDirectories(temporary.resolve('bin'))
        calls = temporary.resolve('calls.txt')
        ['grim', 'wtype', 'wlrctl', 'slurp', 'swaymsg'].each { stub(it) }
    }

    /**
     * A stub that records exactly how it was called, and nothing else.
     *
     * <p>Built by joining explicit lines rather than from an indented block: the shebang must be
     * the first two characters of the file or the kernel will not honour it, and that is too
     * important to leave to whichever {@code stripIndent} happens to be dispatched to.
     */
    private void stub(String name) {
        var script = stubs.resolve(name)
        var lines = [
            '#!/usr/bin/env bash',
            "printf '%s' '$name' >> '$calls'",
            'for argument in "$@"; do printf \' [%s]\' "$argument" >> ' + "'$calls'; done",
            "printf '\\n' >> '$calls'",
            // grim is asked to produce a file; make one so the caller's checks are meaningful.
            name == 'grim' ? ': > "${@: -1}"' : 'true',
            'exit 0',
        ]
        Files.writeString(script, lines.join('\n') + '\n')
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    private Map runLamp(String... arguments) {
        var home = Files.createDirectories(temporary.resolve('home'))
        var argv = ['bash', lamp.toString()] + (arguments as List<String>)
        var process = new ProcessBuilder(argv)
        process.redirectErrorStream(true)
        Map<String, String> environment = process.environment()
        environment.put('PATH', stubs.toString() + ':' + System.getenv('PATH'))
        environment.put('HOME', home.toString())
        environment.put('OILLAMP_DISPLAY_WIDTH', '1920')
        environment.put('OILLAMP_DISPLAY_HEIGHT', '1080')
        environment.put('OILLAMP_RENDERER', 'pixman')
        var started = process.start()
        var output = started.inputStream.text
        started.waitFor()
        [status: started.exitValue(), output: output,
         calls : Files.exists(calls) ? Files.readString(calls).trim().readLines() : []]
    }

    def 'lamp key turns a combination into wtype modifiers and a final key'() {
        reportInfo """
            This is the one piece of real logic in the script, and the one most likely to be wrong:
            wtype takes every modifier as its own -M and only the last key as -k, so `ctrl+shift+t`
            has to become `-M ctrl -M shift -k t`. Getting it backwards produces no error, just a
            keystroke that does nothing - the worst kind of failure for an agent, which will
            conclude the application ignored it and try something else.
        """

        when:
            var result = runLamp('key', combination)

        then:
            result.status == 0
            result.calls == [expected]

        where:
            combination        || expected
            't'                || 'wtype [-k] [t]'
            'ctrl+t'           || 'wtype [-M] [ctrl] [-k] [t]'
            'ctrl+shift+t'     || 'wtype [-M] [ctrl] [-M] [shift] [-k] [t]'
            'ctrl+alt+shift+f' || 'wtype [-M] [ctrl] [-M] [alt] [-M] [shift] [-k] [f]'
    }

    def 'lamp screenshot --region converts the documented X,Y,W,H into grim geometry'() {
        reportInfo """
            The agent guide documents `--region X,Y,W,H` because that is what an agent will guess.
            grim wants `X,Y WxH`. The translation is one line and it is worth a scenario, because
            if it were wrong grim would either fail or - worse - silently capture the wrong part
            of the screen, and the agent would reason about a picture of something else.
        """
        when:
            var result = runLamp('screenshot', '--region', '10,20,800,600', '--out',
                                 temporary.resolve('shot.png').toString())

        then:
            result.status == 0
            result.calls.first().startsWith('grim [-g] [10,20 800x600]')

        and: 'and it prints the path, because the agent needs to know where the file went'
            result.output.trim().endsWith('shot.png')
    }

    def 'lamp screenshot with no arguments writes into the screenshots directory and says where'() {
        when:
            var result = runLamp('screenshot')

        then:
            result.status == 0
            result.output.trim().contains('/screenshots/screenshot-')
            result.output.trim().endsWith('.png')
    }

    def 'lamp click moves the pointer before clicking, because wlrctl clicks where it is'() {
        when:
            var result = runLamp('click', '100', '200')

        then:
            result.calls == ['wlrctl [pointer] [move] [100] [200]', 'wlrctl [pointer] [click] [left]']
    }

    def 'lamp drag presses, moves, then releases'() {
        when:
            var result = runLamp('drag', '10', '20', '30', '40')

        then:
            result.calls == ['wlrctl [pointer] [move] [10] [20]',
                             'wlrctl [pointer] [click] [left] [state:press]',
                             'wlrctl [pointer] [move] [30] [40]',
                             'wlrctl [pointer] [click] [left] [state:release]']
    }

    def 'lamp type passes the text through as a single argument, spaces and all'() {
        when:
            var result = runLamp('type', 'hello world  with   spaces')

        then:
            result.calls == ['wtype [--] [hello world  with   spaces]']
    }

    def 'a missing tool is reported as a missing tool, not as a broken lamp'() {
        reportInfo """
            If a tool such as `wlrctl` is ever missing from the image, the agent must be told which
            tool is absent and that everything else still works, not left with a generic failure
            it will read as "the desktop is broken".
        """
        when: 'wlrctl is not on the PATH'
            Files.delete(stubs.resolve('wlrctl'))
            var result = runLamp('click', '1', '2')

        then: 'lamp names the tool and stays out of the way'
            result.status != 0
            result.output.contains('wlrctl')
            result.output.contains('thin wrapper')
    }

    def 'lamp help tells the agent how to bypass lamp entirely'() {
        reportInfo """
            `lamp` is a thin script, and an agent may need something it does not do. That only
            helps if the agent knows what is available, so the help names the underlying tools
            rather than presenting lamp as the only way.
        """
        when:
            var result = runLamp('help')

        then:
            result.status == 0
            ['grim', 'slurp', 'wtype', 'wlrctl'].every { result.output.contains(it) }

        and: 'and points at the script itself, which is readable and one file'
            result.output.contains('cat $(command -v lamp)')
    }

    def 'an unknown command fails loudly rather than doing nothing'() {
        when:
            var result = runLamp('teleport')

        then:
            result.status != 0
            result.output.contains('teleport')
            result.output.contains('lamp help')
    }
}
