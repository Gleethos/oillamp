package oillamp

import groovy.json.JsonOutput
import spock.lang.Requires
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What happens to floating windows when the desktop changes size.
 *
 * <p>A viewer, such as Genies showing the desktop in a panel, may ask for the desktop to take the
 * panel's size. sway lays out fullscreen and tiled windows again by itself, but leaves floating
 * windows where they were, which on a smaller desktop can be out of reach. The helper
 * {@code keep-windows-on-screen} brings them back. These scenarios run the real helper against a
 * stand-in {@code swaymsg}: it hands out one prepared window tree per change of the desktop, and
 * writes down every command the helper sends. No container is needed.
 *
 * <p>The desktop spike checks the same with real sway and a real window.
 */
@Requires({ Files.isExecutable(Path.of('/usr/bin/python3')) })
class KeepingWindowsOnTheDesktopSpec extends Specification {

    static final Path HELPER = Path.of('src/main/resources/image/rootfs/usr/local/lib/oillamp/keep-windows-on-screen').toAbsolutePath()
    static final int TITLE = 25

    Path directory

    def setup() {
        directory = Files.createTempDirectory('keep-windows-')
        // The stand-in swaymsg. Asked to subscribe, it reports one change of the desktop per tree,
        // each only once the helper has read the tree before it. Asked for the tree, it gives the
        // next one. Given anything else, it writes it down as a command, with the tree it came after.
        var swaymsg = directory.resolve('swaymsg')
        Files.writeString(swaymsg, """\
            #!/usr/bin/env bash
            dir=${directory}
            read_count() { cat "\$dir/read" 2>/dev/null || echo 0; }
            if [ "\$1 \$2" = "-t subscribe" ]; then
                trees=\$(ls "\$dir"/tree-*.json | wc -l)
                for ((i = 1; i <= trees; i++)); do
                    until [ "\$(read_count)" -ge \$((i - 1)) ]; do sleep 0.05; done
                    sleep 0.2
                    echo '{"change":"unspecified"}'
                done
                until [ "\$(read_count)" -ge "\$trees" ]; do sleep 0.05; done
                sleep 0.5
                exit 0
            elif [ "\$1 \$2" = "-t get_tree" ]; then
                n=\$((\$(read_count) + 1))
                cat "\$dir/tree-\$n.json"
                echo "\$n" > "\$dir/read"
            else
                echo "after tree \$(read_count): \$*" >> "\$dir/commands"
            fi
            """.stripIndent())
        swaymsg.toFile().setExecutable(true)
    }

    def cleanup() {
        directory.toFile().deleteDir()
    }

    def 'A floating window the smaller desktop cuts off is moved back onto it, at the size it has'() {
        reportInfo """
            The desktop shrinks from 1920x1080 to 800x500. A window at 1000,300, 600 wide and 400
            high with its title bar, now begins beyond the right edge. It still fits, so it keeps
            its size and only moves, as little as it must: to the right and bottom edges.
        """
        given:
            desktop 1, 800, 500, window(5, 1000, 300, 600, 400)

        when:
            var commands = arrange()

        then:
            commands == ['after tree 1: [con_id=5] move absolute position 200 100']
    }

    def 'A window larger than the desktop is made as large as the desktop'() {
        reportInfo """
            A window 1200x900 cannot fit on a desktop of 800x500 by moving. It is made 800x500,
            which is the whole desktop, title bar included, and moved to its corner.
        """
        given:
            desktop 1, 800, 500, window(5, 100, 100, 1200, 900)

        when:
            var commands = arrange()

        then:
            commands == ['after tree 1: [con_id=5] resize set width 800 px height 500 px; [con_id=5] move absolute position 0 0']
    }

    def 'Windows that fit, fullscreen windows, and the windows of no screen are left alone'() {
        reportInfo """
            Only what is out of reach is touched. A window that fits stays put, a fullscreen
            window is already sized by sway, and sway's scratchpad is not a screen.
        """
        given:
            tree 1, output('HEADLESS-1', 800, 500, [window(5, 10, 10, 300, 200),
                                                    window(6, 0, 0, 1920, 1080) + [fullscreen_mode: 1]]),
                    output('__i3', 0, 0, [window(7, 5000, 5000, 300, 200)])

        expect:
            arrange() == []
    }

    def 'When the desktop grows again, a window goes back to where it was'() {
        reportInfo """
            The user closes the panel, and the desktop goes back to its own size. A window that
            was moved to fit the panel goes back to where it was before, and to the size it had.
        """
        given: 'a window moved and shrunk to fit a small desktop'
            desktop 1, 800, 500, window(5, 900, 300, 1000, 700)
        and: 'the desktop back at its own size, the window where it was put'
            desktop 2, 1920, 1080, window(5, 0, 0, 800, 500)

        when:
            var commands = arrange()

        then:
            commands == ['after tree 1: [con_id=5] resize set width 800 px height 500 px; [con_id=5] move absolute position 0 0',
                         'after tree 2: [con_id=5] resize set width 1000 px height 700 px; [con_id=5] move absolute position 900 300']
    }

    def 'A window someone moved in the meantime stays where they put it'() {
        reportInfo """
            While the desktop was small, the user dragged the window somewhere else. That is now
            where it belongs: when the desktop grows, it is not taken back to where it once was.
        """
        given:
            desktop 1, 800, 500, window(5, 1000, 300, 600, 400)
            desktop 2, 800, 500, window(5, 40, 40, 600, 400)
            desktop 3, 1920, 1080, window(5, 40, 40, 600, 400)

        expect:
            arrange() == ['after tree 1: [con_id=5] move absolute position 200 100']
    }

    def 'No text of a window ever becomes part of a command'() {
        reportInfo """
            The helper runs as the infra user, who owns sway, and sway runs whatever command it
            is given. The agent names its windows, so a window title must never reach a command.
            Commands are made of the numbers sway gave: a window's id and its position.
        """
        given:
            desktop 1, 800, 500, window(5, 1000, 300, 600, 400) + [name: 'x"]; exec rm -rf ~', app_id: 'evil; exit']

        when:
            var commands = arrange()

        then:
            commands == ['after tree 1: [con_id=5] move absolute position 200 100']
    }

    // ─── the stand-in sway ─────────────────────────────────────────────────────────────────

    /** The tree sway gives for the `n`th change of the desktop: one screen of this size. */
    private void desktop(int n, int width, int height, Map... windows) {
        tree(n, output('HEADLESS-1', width, height, windows as List))
    }

    private void tree(int n, Map... outputs) {
        Files.writeString(directory.resolve("tree-${n}.json"), JsonOutput.toJson([type: 'root', nodes: outputs as List]))
    }

    private static Map output(String name, int width, int height, List windows) {
        [type: 'output', name: name, rect: [x: 0, y: 0, width: width, height: height],
         nodes: [[type: 'workspace', name: '1', floating_nodes: windows]]]
    }

    /** A floating window whose whole box, title bar included, is at x,y and this large. */
    private static Map window(int id, int x, int y, int width, int height) {
        [id: id, type: 'floating_con', name: 'a window', fullscreen_mode: 0,
         rect: [x: x, y: y + TITLE, width: width, height: height - TITLE],
         deco_rect: [x: x, y: y, width: width, height: TITLE]]
    }

    /** Runs the helper until the stand-in has reported every change; the commands it sent. */
    private List<String> arrange() {
        var process = new ProcessBuilder('python3', HELPER.toString()).redirectErrorStream(true)
        process.environment().put('PATH', "${directory}:${System.getenv('PATH')}".toString())
        var running = process.start()
        assert running.waitFor(30, TimeUnit.SECONDS), 'the helper did not end with the stand-in'
        var output = new String(running.inputStream.readAllBytes())
        assert running.exitValue() == 0, output
        var log = directory.resolve('commands')
        Files.exists(log) ? Files.readAllLines(log) : []
    }
}
