package oillamp

import groovy.json.JsonSlurper
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
        ['grim', 'wtype', 'wlrctl', 'slurp', 'swaymsg', 'lamp-pointer'].each { stub(it) }
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
            // The pointer helper answers "where" with the last position it moved to.
            name == 'lamp-pointer' ? '[ "$1" = where ] && echo "700 400"' : 'true',
            // ...and "size" with the size the desktop has now.
            name == 'lamp-pointer' ? '[ "$1" = size ] && echo "${STUB_DESKTOP_SIZE:-1920x1080}"' : 'true',
            'exit 0',
        ]
        Files.writeString(script, lines.join('\n') + '\n')
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    private Map runLamp(String... arguments) { runLamp([:], arguments) }

    private Map runLamp(Map<String, String> extraEnvironment, String... arguments) {
        var home = Files.createDirectories(temporary.resolve('home'))
        var argv = ['bash', lamp.toString()] + (arguments as List<String>)
        var process = new ProcessBuilder(argv)
        process.redirectErrorStream(true)
        Map<String, String> environment = process.environment()
        environment.put('PATH', stubs.toString() + ':' + System.getenv('PATH'))
        environment.put('LAMP_POINTER', stubs.resolve('lamp-pointer').toString())
        environment.put('HOME', home.toString())
        environment.put('OILLAMP_DISPLAY_WIDTH', '1920')
        environment.put('OILLAMP_DISPLAY_HEIGHT', '1080')
        environment.put('OILLAMP_RENDERER', 'pixman')
        environment.putAll(extraEnvironment)
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
            't'                || 'wtype [-s] [150] [-k] [t]'
            'ctrl+t'           || 'wtype [-s] [150] [-M] [ctrl] [-k] [t]'
            'ctrl+shift+t'     || 'wtype [-s] [150] [-M] [ctrl] [-M] [shift] [-k] [t]'
            'ctrl+alt+shift+f' || 'wtype [-s] [150] [-M] [ctrl] [-M] [alt] [-M] [shift] [-k] [f]'
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

    def 'lamp click clicks at the position it was given, not relative to the pointer'() {
        reportInfo """
            `lamp click X Y` used to pass X and Y to `wlrctl pointer move`, which moves the pointer
            *by* that amount. So every click landed somewhere unrelated to what was asked, and
            nothing reported it. The click now goes to the pointer helper as a position on the
            screen; the desktop spike checks that an application really receives it there.
        """
        when:
            var result = runLamp('click', '100', '200')

        then: 'the pointer helper is asked for exactly one click, at that position'
            result.status == 0
            result.calls == ['lamp-pointer [click] [100] [200]']

        and: 'wlrctl is not involved'
            result.calls.every { !it.startsWith('wlrctl') }
    }

    def 'lamp scroll without a position scrolls where the pointer was last put'() {
        when:
            var result = runLamp('scroll', '3')

        then:
            result.calls == ['lamp-pointer [where]', 'lamp-pointer [scroll] [700] [400] [3]']
    }

    def 'lamp drag goes from one position to the other'() {
        when:
            var result = runLamp('drag', '10', '20', '30', '40')

        then:
            result.calls == ['lamp-pointer [drag] [10] [20] [30] [40]']
    }

    def 'lamp type passes the text through as a single argument, spaces and all'() {
        when:
            var result = runLamp('type', 'hello world  with   spaces')

        then:
            result.calls == ['wtype [-s] [150] [--] [hello world  with   spaces]']
    }

    def 'a missing tool is reported as a missing tool, not as a broken lamp'() {
        reportInfo """
            If a tool such as `wtype` is ever missing from the image, the agent must be told which
            tool is absent and that everything else still works, not left with a generic failure
            it will read as "the desktop is broken".
        """
        when: 'wtype is not on the PATH'
            Files.delete(stubs.resolve('wtype'))
            var result = runLamp('type', 'hello')

        then: 'lamp names the tool and stays out of the way'
            result.status != 0
            result.output.contains('wtype')
            result.output.contains('thin wrapper')
    }

    def 'lamp info says the size the desktop has now, and its own size when that differs'() {
        reportInfo """
            The user may watch the desktop in a panel of Genies, and have it take the panel's
            size. The agent clicks by screen positions, so `lamp info` asks the desktop for its
            size now rather than repeating the size it started with, and says which size it goes
            back to.
        """
        when: 'the desktop has its own size'
            var own = runLamp('info')
        and: 'the desktop was given the size of a panel'
            var panel = runLamp([STUB_DESKTOP_SIZE: '800x500'], 'info')

        then:
            own.output.readLines().first() == 'size       1920x1080'
            !own.output.contains('own size')
            panel.output.readLines().take(2) == [
                    'size       800x500',
                    'own size   1920x1080, which it goes back to when the user stops showing it in a panel']
    }

    def 'lamp dock add puts a button in the file the dock reads, where it stays'() {
        reportInfo """
            The agent puts buttons on the dock at the bottom of the desktop for the user to click.
            They are kept in a file in the agent's home, which outlives the session, so a button
            is still there after the lamp was restarted. The dock reads this file; lamp dock is
            the agent's way to write it without getting the JSON wrong.
        """
        when:
            runLamp('dock', 'add', 'Report', 'firefox ~/report.html', '--icon', 'firefox-esr')
            runLamp('dock', 'add', 'Notes', 'foot -e nano "my notes.txt"')
            var listed = runLamp('dock')

        then: 'the file holds both buttons, the command exactly as written'
            var file = temporary.resolve('home/.config/lamp/dock.json')
            new JsonSlurper().parse(file.toFile()) == [
                    [label: 'Report', command: 'firefox ~/report.html', icon: 'firefox-esr'],
                    [label: 'Notes', command: 'foot -e nano "my notes.txt"']]

        and: 'and lamp dock lists them'
            listed.status == 0
            listed.output.contains('Report: firefox ~/report.html  (icon firefox-esr)')
            listed.output.contains('Notes: foot -e nano "my notes.txt"')
    }

    def 'a button added under a label that is already there replaces that button'() {
        reportInfo """
            A label names a button, so adding "Report" again is how the agent changes what its
            Report button does. The button keeps its place on the dock, and the user does not
            get two buttons called Report.
        """
        when:
            runLamp('dock', 'add', 'Report', 'firefox ~/old.html', '--icon', 'firefox-esr')
            runLamp('dock', 'add', 'Notes', 'foot')
            var replaced = runLamp('dock', 'add', 'Report', 'firefox ~/new.html')

        then:
            replaced.output.contains("replaced 'Report' on the dock")
            var file = temporary.resolve('home/.config/lamp/dock.json')
            new JsonSlurper().parse(file.toFile()) == [
                    [label: 'Report', command: 'firefox ~/new.html'],
                    [label: 'Notes', command: 'foot']]
    }

    def 'lamp dock remove takes a button away, and says so when there is none by that name'() {
        reportInfo """
            Removing a button that is not there is a mistake the agent should hear about, with a
            way to see what is there instead, rather than a silent success.
        """
        given:
            runLamp('dock', 'add', 'Report', 'firefox ~/report.html')

        when:
            var removed = runLamp('dock', 'remove', 'Report')
            var missing = runLamp('dock', 'remove', 'Report')

        then:
            removed.status == 0
            Files.readString(temporary.resolve('home/.config/lamp/dock.json')).trim() == '[]'
            missing.status != 0
            missing.output.contains("there is no 'Report' on the dock")
            missing.output.contains('`lamp dock` lists what is there')
    }

    def 'lamp dock does not write over a file it cannot read'() {
        reportInfo """
            The agent may also edit the file by hand. If that left it broken, writing a new list
            over it would quietly throw away every button in it. lamp dock refuses and says which
            file to fix.
        """
        given:
            var file = temporary.resolve('home/.config/lamp/dock.json')
            Files.createDirectories(file.parent)
            Files.writeString(file, '[{"label": "Report",')

        when:
            var result = runLamp('dock', 'add', 'Notes', 'foot')

        then:
            result.status != 0
            result.output.contains("${file} cannot be read")
            Files.readString(file) == '[{"label": "Report",'
    }

    def 'lamp dock tells the agent when the user cannot see the dock'() {
        reportInfo """
            A button on a dock that is not running is a button nobody can click. The dock writes
            its process id when it starts; lamp dock checks it, so the agent can tell the user
            instead of believing the button is there.
        """
        given:
            var pidFile = temporary.resolve('lamp-dock.pid')

        when: 'no dock ever started'
            var stopped = runLamp([LAMP_DOCK_PID_FILE: pidFile.toString()], 'dock')
        and: 'a running process wrote the file'
            Files.writeString(pidFile, "${ProcessHandle.current().pid()}\n")
            var running = runLamp([LAMP_DOCK_PID_FILE: pidFile.toString()], 'dock')

        then:
            stopped.output.contains('The dock is not running, so the user does not see these buttons.')
            !running.output.contains('not running')
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
            ['grim', 'slurp', 'wtype', 'lamp-pointer'].every { result.output.contains(it) }

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

    def 'a missing or malformed value is explained, not left to bash: lamp #line'() {
        reportInfo """
            The agent reads what lamp says and tries again, so an error must say what to write
            instead. A missing value used to stop bash with "unbound variable", and a
            wait-stable time that was not a whole number with an arithmetic error.
        """
        when:
            var result = runLamp(*line.split(' '))

        then:
            result.status != 0
            result.output.contains(explanation)
            !result.output.contains('unbound variable')
            !result.output.contains('syntax error')

        and: 'nothing was captured'
            result.calls.isEmpty()

        where:
            line                   | explanation
            'screenshot --region'  | '--region takes X,Y,W,H'
            'screenshot --out'     | '--out takes the file to write'
            'wait-stable soon'     | 'wait-stable takes a whole number of seconds'
            'wait-stable 2.5'      | 'wait-stable takes a whole number of seconds'
    }
}
