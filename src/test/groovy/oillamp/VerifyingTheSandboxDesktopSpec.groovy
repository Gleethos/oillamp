package oillamp

import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Builds the real image, starts the real container, connects over SSH the way the terminal window
 * does, and drives the desktop with the real {@code lamp} script. It checks the assumptions about
 * sshd, Wayland, sway, vncviewer and wf-recorder that the rest of oillamp depends on.
 *
 * <p>Slow by nature: the first run builds an image. {@code WITH_TOOLCHAIN=false} keeps that to the
 * desktop and SSH stack, which is all these assumptions concern; adding a JDK and a browser would
 * multiply the build time without testing one extra thing.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class VerifyingTheSandboxDesktopSpec extends Specification {

    static final String IMAGE = 'localhost/oillamp-spike:core'
    static final String CONTAINER = 'oillamp-spike-desktop'

    @Shared Path lamp
    @Shared Path agentHome
    @Shared String session = '20260101-000000'

    def setupSpec() {
        lamp = Files.createTempDirectory('oillamp-desktop-spike-')
        agentHome = Files.createDirectories(lamp.resolve('agent'))
        ['session', 'sockets/agent', 'sockets/host', 'sockets/infra', 'recordings']
                .each { Files.createDirectories(lamp.resolve(it)) }
        ['workspace', 'screenshots', 'libs'].each { Files.createDirectories(agentHome.resolve(it)) }

        // The infra directories belong to the `lamp` user, as in a real lamp. wayvnc and
        // wf-recorder run as `lamp` and create their socket and recording here; they cannot write
        // to a directory owned by the agent.
        // Leaving this out was the first thing that broke when this spec ran unattended, and the
        // symptom was simply no ready.json, with nothing in the log naming a permission.
        ['sockets/infra', 'recordings'].each {
            var chowned = Spike.run('podman', 'unshare', 'chown', '1001:1001',
                                    lamp.resolve(it).toString())
            assert chowned.ok, "could not hand $it to the infra user:\n${chowned.describe()}"
        }

        Files.writeString(lamp.resolve('session/runtime.env'), """\
            OILLAMP_AGENT_ID='spike001'
            OILLAMP_LAMP_NAME='spike'
            OILLAMP_SESSION='${session}'
            OILLAMP_DISPLAY_WIDTH='1280'
            OILLAMP_DISPLAY_HEIGHT='720'
            OILLAMP_DISPLAY_SCALE='1.0'
            OILLAMP_WINDOWS='floating'
            OILLAMP_RENDERER='pixman'
            OILLAMP_RECORDING_ENABLED='true'
            OILLAMP_RECORDING_CODEC='libx264'
            OILLAMP_VNC_MAX_FPS='30'
            OILLAMP_PROXY_PORT='3128'
            OILLAMP_FORWARDS=''
            """.stripIndent())

        var keys = Files.createDirectories(lamp.resolve('keys'))
        run('ssh-keygen', '-t', 'ed25519', '-N', '', '-C', 'spike-client',
            '-f', keys.resolve('client').toString())
        run('ssh-keygen', '-t', 'ed25519', '-N', '', '-C', 'spike-host',
            '-f', lamp.resolve('session/ssh_host_ed25519_key').toString())
        Files.copy(keys.resolve('client.pub'), lamp.resolve('session/authorized_keys'))

        Files.writeString(lamp.resolve('ssh_config'), """\
            Host spike
              User agent
              HostName spike
              IdentityFile ${keys.resolve('client')}
              IdentitiesOnly yes
              StrictHostKeyChecking no
              UserKnownHostsFile /dev/null
              LogLevel ERROR
            """.stripIndent())

        var built = Spike.run(Duration.ofMinutes(25), 'podman', 'build',
                '--build-arg', 'WITH_TOOLCHAIN=false', '-t', IMAGE,
                Path.of('src/main/resources/image').toAbsolutePath().toString())
        assert built.ok, "the image did not build:\n${built.describe()}"
    }

    def cleanupSpec() {
        Spike.run('podman', 'rm', '-f', CONTAINER)
        if (lamp) Spike.removeTree(lamp.toString())
    }

    private static Spike.Result run(String... argv) { Spike.run(argv) }

    /** SSH in and run a login shell, as the terminal window oillamp opens does. */
    private Spike.Result inSandbox(String script) {
        Spike.run(Duration.ofMinutes(2), 'ssh', '-F', lamp.resolve('ssh_config').toString(),
                '-o', "ProxyCommand=socat - UNIX-CONNECT:${lamp.resolve('sockets/agent/ssh.sock')}",
                '-o', 'BatchMode=yes', 'spike', "bash -l -c ${escape(script)}")
    }

    private static String escape(String script) { "'" + script.replace("'", "'\\''") + "'" }

    def 'The sandbox starts and reports itself ready'() {
        reportInfo """
            The entrypoint running in full: it
            starts sway, wayvnc, the recorder and the socket bridges as the right users, waits for
            each to prove itself, and only then writes ready.json.

            Two bugs found the first time this ran, both invisible to any simulation:

            1. `setpriv` changes the user but not the environment, so every dropped process
               inherited root's HOME=/root (mode 0700, owned by root). wayvnc reported "Failed to
               load config. Permission denied" and fontconfig reported "No writable cache
               directories"; neither mentioned HOME.
            2. `\${gpu_fallback:+…}` tests for a non-empty string, and "false" is one, so a run
               that never attempted the GPU announced that it had fallen back from it.
        """
        when: 'the container is started with the same security flags oillamp uses'
            var started = Spike.run('podman', 'run', '-d', '--name', CONTAINER,
                    '--network=none', '--read-only', '--user', '0:0',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '--tmpfs', '/run:rw,mode=755', '--tmpfs', '/tmp:rw',
                    '-v', "${lamp.resolve('session')}:/oillamp/session:ro".toString(),
                    '-v', "${lamp.resolve('sockets/host')}:/oillamp/sockets/host:ro".toString(),
                    '-v', "${lamp.resolve('sockets/agent')}:/oillamp/sockets/agent".toString(),
                    '-v', "${lamp.resolve('sockets/infra')}:/oillamp/sockets/infra".toString(),
                    '-v', "${lamp.resolve('recordings')}:/oillamp/recordings".toString(),
                    '-v', "${agentHome}:/home/agent".toString(),
                    IMAGE)

        then: 'it is running'
            started.ok

        and: 'it announces readiness rather than leaving the host to guess'
            waitForFile(lamp.resolve('sockets/infra/ready.json'), 60)
            var ready = Files.readString(lamp.resolve('sockets/infra/ready.json'))
            ready.contains('"renderer":"pixman"')
            ready.contains('"gpu_fallback":false')
            ready.contains('"width":1280')

        and: 'and the log says what it did, without claiming a GPU fallback that never happened'
            var logs = Spike.run('podman', 'logs', CONTAINER)
            logs.mentions('compositor up (pixman)')
            !logs.mentions('fell back')

        and: 'the infra files belong to a host user that is neither you nor the agent'
            var owner = Spike.run('stat', '-c', '%u',
                    lamp.resolve('sockets/infra/ready.json').toString()).out.trim()
            owner.toInteger() > 65535
    }

    def 'A shell over the Unix socket lands as the agent user'() {
        reportInfo """
            The user's shell is SSH over a Unix socket, so that the container can have no network.
            It was not certain that a non-root `sshd -i` still works with trixie's OpenSSH, which split out
            a separate `sshd-session` binary. The fallback was to replace it with dropbear.

            It works. No fallback needed.
        """
        when:
            var result = inSandbox('whoami; id -u')

        then: 'the login succeeded with the per-lamp key alone'
            result.ok

        and: 'and dropped to the agent, not to root'
            result.mentions('agent')
            result.out.readLines()*.trim().contains('1000')
    }

    def 'Clients accept an absolute path in WAYLAND_DISPLAY'() {
        reportInfo """
            The compositor runs as `lamp` with its socket at /run/lamp/wayland-1, and the agent has
            its own XDG_RUNTIME_DIR, so the two cannot agree on a relative socket name. The design
            resolves this with an absolute path in WAYLAND_DISPLAY, and it was not certain that libwayland
            would accept one. The fallback was a symlink and a relative name.

            grim connecting is the proof: it is a Wayland client and it captured the screen.
        """
        when:
            var result = inSandbox('echo "$WAYLAND_DISPLAY"; grim /tmp/probe.png && echo captured')

        then:
            result.mentions('/run/lamp/wayland-1')
            result.mentions('captured')
    }

    def 'Sway honours the requested mode, and the desktop really renders windows'() {
        reportInfo """
            The assumption behind the whole product: sway headless with a custom mode, Xwayland
            available, and applications that actually appear. An empty desktop of the right size
            would prove only half of it, so this launches a terminal and captures it.

            The first screenshot ever taken in the sandbox also found a defect: every GUI app
            warned "'C' is not a UTF-8 locale", because sshd does not forward a locale and the
            image's ENV does not reach a login shell. The login profile now sets one.
        """
        when: 'the empty desktop is captured, then an application is launched and captured again'
            var result = inSandbox('''
                lamp screenshot --out /home/agent/screenshots/empty.png
                nohup foot -T spike-window sh -c 'echo hello-from-the-sandbox; exec sleep 120' \\
                    >/dev/null 2>&1 &
                sleep 4
                lamp wait-stable 10 || true
                lamp screenshot --out /home/agent/screenshots/window.png
            '''.stripIndent())

        then:
            result.ok

        and: 'the capture is a real image of the requested size'
            var shot = agentHome.resolve('screenshots/window.png')
            Files.exists(shot)
            var described = Spike.run('file', shot.toString())
            described.mentions('PNG image data, 1280 x 720')

        and: 'the desktop before the window was already drawn: it has the wallpaper on it'
            var empty = agentHome.resolve('screenshots/empty.png')
            Files.exists(empty)

        and: 'and launching the application visibly changed the screen'
            // Comparing the two captures pixel by pixel answers exactly "did an application
            // appear?", which file sizes or background colours would not, now the desktop has a
            // wallpaper.
            differingPixels(empty, shot) > 10_000

        and: 'the locale is set, so applications do not fall back to C'
            inSandbox('echo "$LANG"').mentions('UTF-8')
    }

    def 'The dock runs as the agent, shows a button the agent adds, and a click runs its command'() {
        reportInfo """
            The dock is the bar at the bottom of the desktop. The agent puts buttons on it for the
            user to click. This needs the real entrypoint to start the dock as the agent, GTK to
            draw on sway's layer for panels, and a click arriving the way the user's arrives,
            through the VNC server.

            The button's label is long on purpose: the bar is centred, so a wide button is sure
            to lie under the point clicked, whatever the font. This image has no Firefox, so the
            dock's only own button is Terminal.
        """
        when: 'the bottom of the desktop is captured, a button added, and captured again'
            var result = inSandbox('''
                ps -o user= -p "$(cat /run/agent/lamp-dock.pid)"
                lamp screenshot --region 0,620,1280,100 --out /home/agent/screenshots/dock-before.png
                lamp dock add "A button the spike added, wide enough to click" \\
                    "touch /home/agent/clicked-from-the-dock"
                sleep 3
                lamp screenshot --region 0,620,1280,100 --out /home/agent/screenshots/dock-after.png
                lamp click 760 680
                sleep 3
            '''.stripIndent())

        then: 'the dock runs as the agent, not as the user that owns the recording'
            result.ok
            result.out.readLines()*.trim().first() == 'agent'

        and: 'the dock was running, so lamp dock did not warn'
            !result.mentions('not running')

        and: 'the button appeared'
            differingPixels(agentHome.resolve('screenshots/dock-before.png'),
                            agentHome.resolve('screenshots/dock-after.png')) > 1_000

        and: 'and clicking it ran its command, as the agent'
            Files.exists(agentHome.resolve('clicked-from-the-dock'))
    }

    def 'An X11 application started by the agent reaches the display'() {
        reportInfo """
            Java Swing, and every other X11 application, draws through Xwayland, the X11 server
            that sway starts. Xwayland runs as the infra user `lamp` and at first accepts only
            `lamp`, so an agent's Swing application failed with "Authorization required". Nothing
            caught this for a long time, because every earlier check here used native Wayland
            applications.

            The entrypoint now keeps Xwayland running and allows the agent user on it. This asks
            the display a question as the agent, the way any X11 application would.
        """
        when: 'the agent opens X11 display :0'
            var result = inSandbox('echo "DISPLAY=$DISPLAY"; xdpyinfo | head -2')

        then: 'it is the default display, and it answers'
            result.ok
            result.mentions('DISPLAY=:0')
            result.mentions('name of display')
            !result.mentions('Authorization required')
    }

    def 'The agent can click and type in an X11 application, using only lamp'() {
        reportInfo """
            This is what an agent does to test a Java Swing application: start it, click on it,
            type into it, and look at the result. Swing draws through X11, so it is checked here
            with `xev`, a standard X11 program that prints every mouse and keyboard event it
            receives. The scenario uses only `lamp` and then asks the program what arrived.

            Two bugs made this fail while every command reported success: `lamp click` moved the
            pointer by the given amount instead of to the given position, and its click reached
            no window at all; and the first key of every `lamp type` was dropped by X11
            applications. The first letter of the text is therefore part of what is checked.
        """
        when: 'an X11 application is started, clicked once and typed into'
            // Other windows may be open (the scenario before this one leaves a terminal). So the
            // position to click is taken from the application's own window, the way an agent
            // would find it on a screenshot.
            var result = inSandbox('''
                rm -f /tmp/events.log
                nohup stdbuf -oL xev -event button -event keyboard > /tmp/events.log 2>&1 &
                sleep 2
                geometry=$(xwininfo -name 'Event Tester')
                left=$(echo "$geometry" | awk '/Absolute upper-left X/ {print $4}')
                top=$(echo "$geometry" | awk '/Absolute upper-left Y/ {print $4}')
                width=$(echo "$geometry" | awk '/Width:/ {print $2}')
                height=$(echo "$geometry" | awk '/Height:/ {print $2}')
                x=$((left + width / 2)); y=$((top + height / 2))
                echo "clicking at $x,$y"
                lamp click $x $y
                lamp type "Hello, oillamp"
                sleep 1
                cat /tmp/events.log
            '''.stripIndent())
            var lines = result.out.readLines()
            var target = position(lines.find { it.startsWith('clicking at ') })

        then: 'it received exactly one button press, where lamp clicked'
            var presses = lines.findIndexValues { it.startsWith('ButtonPress') }
            presses.size() == 1
            var arrived = position(lines[(int) presses.first() + 1])
            // The compositor turns the position into a fraction of the screen and back, and that
            // floating-point round trip can land one pixel short: 371 arrives as 370.
            Math.abs(arrived[0] - target[0]) <= 1
            Math.abs(arrived[1] - target[1]) <= 1

        and: 'and every character that was typed, in order, the first one included'
            typed(lines) == 'Hello, oillamp'

        cleanup:
            inSandbox('pkill -x xev || true')
    }

    def 'A window moves by its title bar and resizes by its edge, as on most desktops'() {
        reportInfo """
            Windows float (`display.windows = "floating"`, the default). A window opens at the
            size its application asks for, and the human in the viewer, or the agent with
            `lamp drag`, moves it by its title bar and resizes it by its edge. The desktop used to
            tile: one window filled the whole screen and could be neither moved nor resized.

            X11 applications, such as Java Swing, get their title bar and border from the
            compositor, so this checks one: `xev` at 400x300. The title bar is just above the
            window's content, and the border, 4 pixels wide, just to the right of it.
        """
        when: 'the window is dragged by its title bar, then by its right edge'
            var result = inSandbox('''
                nohup xev -geometry 400x300 > /dev/null 2>&1 &
                sleep 2
                where() { xwininfo -name 'Event Tester' | awk '/Absolute upper-left X/ {x=$4}
                    /Absolute upper-left Y/ {y=$4} /Width:/ {w=$2} /Height:/ {h=$2}
                    END {print x, y, w, h}'; }
                read -r x y w h < <(where); echo "opened $x $y $w $h"
                lamp drag $((x + w / 2)) $((y - 8)) $((x + w / 2 - 200)) $((y - 8 - 100))
                sleep 1
                read -r x y w h < <(where); echo "moved $x $y $w $h"
                lamp drag $((x + w + 2)) $((y + h / 2)) $((x + w + 152)) $((y + h / 2))
                sleep 1
                read -r x y w h < <(where); echo "resized $x $y $w $h"
            '''.stripIndent())
            var opened  = geometry(result.out, 'opened')
            var moved   = geometry(result.out, 'moved')
            var resized = geometry(result.out, 'resized')

        then: 'it opened at the size it asked for, not filling the screen'
            opened[2] == 400 && opened[3] == 300

        and: 'dragging the title bar moved it by the distance dragged, at the same size'
            Math.abs(moved[0] - (opened[0] - 200)) <= 2
            Math.abs(moved[1] - (opened[1] - 100)) <= 2
            moved[2] == 400 && moved[3] == 300

        and: 'dragging the right edge made it wider, and left it where it was'
            Math.abs(resized[2] - 550) <= 4
            resized[3] == 300
            resized[0] == moved[0]

        cleanup:
            inSandbox('pkill -x xev || true')
    }

    /** The four numbers after a label, such as "moved 240 110 400 300": x, y, width, height. */
    private static List<Integer> geometry(String output, String label) {
        var line = output.readLines().find { it.startsWith(label + ' ') }
        assert line, "no '$label' line in:\n$output"
        line.split(' ')[1..4].collect { it as int }
    }

    /** The first "X,Y" in a line, such as "clicking at 960,371" or xev's "root:(960,370)". */
    private static List<Integer> position(String line) {
        var matcher = line =~ /(?:at |root:\()(\d+),(\d+)/
        assert matcher.find(), "no position in: $line"
        [matcher.group(1) as int, matcher.group(2) as int]
    }

    /** The characters an X11 program received, as xev reports them for each key press. */
    private static String typed(List<String> xevLines) {
        var out = new StringBuilder()
        for (int i = 0; i < xevLines.size(); i++) {
            if (!xevLines[i].startsWith('KeyPress')) continue
            var lookup = xevLines.subList(i + 1, Math.min(i + 6, xevLines.size()))
                    .find { it.contains('XLookupString gives') }
            var matcher = lookup =~ /gives 1 bytes: \([0-9a-f]+\) "(.)"/
            if (matcher.find()) out.append(matcher.group(1))
        }
        out.toString()
    }

    def 'The viewer connects straight to the wayvnc Unix socket'() {
        reportInfo """
            It was not certain that TigerVNC's vncviewer accepts a Unix socket path as its server
            argument. The fallback was an extra socat bridge onto a random loopback TCP port with
            a VNC password: more moving parts, and a listening TCP socket that did not need to
            exist.

            It is not needed: vncviewer connects to the socket directly and negotiates RFB 3.8.
            The viewer is run with a timeout because it is a GUI application that would otherwise
            never exit; what is asserted is that it got as far as speaking the protocol.
        """
        when:
            var viewer = Spike.run(Duration.ofSeconds(15), 'timeout', '8', 'vncviewer',
                    lamp.resolve('sockets/infra/vnc.sock').toString())

        then: 'it reached the socket and spoke RFB'
            viewer.mentions('Connected to socket')
            viewer.mentions('RFB protocol version 3.8')
    }

    def 'A recorded desktop refuses to change size, so the recording stays whole'() {
        reportInfo """
            A viewer can ask wayvnc for another desktop size, and wayvnc then changes the size of
            the screen. wf-recorder does not survive that: it lost the screen with "invalid buffer
            dimensions", stayed alive, and wrote its last picture thousands of times a second at
            four cores' worth of CPU, while the session reported itself healthy. The agent can
            reach the VNC socket too. So while the desktop is recorded, wayvnc refuses.
        """
        when: 'a viewer asks for 800x500, as Genies does for its panel'
            var answer = askForSize(800, 500)

        then: 'wayvnc says no: 1 means the server does not allow it'
            answer == 1

        and: 'the desktop keeps its size'
            inSandbox('lamp info').out.readLines().first() == 'size       1280x720'

        and: 'the recorder never lost the screen'
            !Spike.run('podman', 'logs', CONTAINER).mentions('invalid buffer dimensions')
    }

    def 'The recording is finalised and playable after the sandbox stops'() {
        reportInfo """
            oillamp promises the human a recording of everything the agent did. A file that exists but
            cannot be played is the worst possible outcome, because it is discovered when someone needs to
            watch it. The fallback, had this failed, was to split recordings into segments every few minutes if a killed
            recorder left an unplayable file.

            It is not needed here: the .mkv is a complete, playable h264 stream with a duration.
        """
        when: 'the sandbox is asked to stop the way the supervisor will ask'
            Spike.run(Duration.ofMinutes(2), 'podman', 'stop', '-t', '30', CONTAINER)

        then: 'a recording exists'
            var recording = Files.list(lamp.resolve('recordings')).toList().find {
                it.toString().endsWith('.mkv')
            }
            recording != null
            Files.size(recording) > 0

        and: 'and it is a real video, not a truncated file'
            var probe = Spike.run('ffprobe', '-v', 'error',
                    '-show_entries', 'format=duration', '-show_entries', 'stream=codec_name,width,height',
                    '-of', 'default=noprint_wrappers=1', recording.toString())
            probe.ok
            probe.mentions('codec_name=h264')
            probe.mentions('width=1280')
            probe.text =~ /duration=[1-9]/
    }

    def 'A second session on the same lamp comes up with a working desktop'() {
        reportInfo """
            The regression this spec exists to prevent, and the one bug here that a user found
            before the suite did. The first session on a lamp always worked. The second came up
            with a working shell and a dead desktop: the viewer was refused, while oillamp had
            already printed a green tick and gone away.

            /oillamp/sockets is a bind mount, so it outlives the container, and `vnc.sock` from
            the previous session was still sitting there. wayvnc has no equivalent of socat's
            `unlink-early` - it binds its path or it exits - so it exited, and the ssh listener,
            which does have that option, came up fine. That asymmetry is the whole bug.

            What made it reach the user rather than a log nobody reads is that every check on
            both sides of the mount tested whether the path existed, which the leftover file
            satisfied perfectly. The entrypoint wrote ready.json for a dead VNC server, and the
            host believed it.

            Note where this scenario sits: right after the recording scenario, which stops the container. The
            sockets directory is therefore in exactly the state that broke - full of the previous
            session's files - and no setup is needed to arrange it.
        """
        given: 'the previous session left its socket and its readiness file behind'
            var vncSocket = lamp.resolve('sockets/infra/vnc.sock')
            var readyFile = lamp.resolve('sockets/infra/ready.json')
            Files.exists(vncSocket)
            Files.exists(readyFile)
            Spike.run('podman', 'rm', '-f', CONTAINER)

        when: 'the lamp is used again, exactly as before'
            var restarted = Spike.run('podman', 'run', '-d', '--name', CONTAINER,
                    '--network=none', '--read-only', '--user', '0:0',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '--tmpfs', '/run:rw,mode=755', '--tmpfs', '/tmp:rw',
                    '-v', "${lamp.resolve('session')}:/oillamp/session:ro".toString(),
                    '-v', "${lamp.resolve('sockets/host')}:/oillamp/sockets/host:ro".toString(),
                    '-v', "${lamp.resolve('sockets/agent')}:/oillamp/sockets/agent".toString(),
                    '-v', "${lamp.resolve('sockets/infra')}:/oillamp/sockets/infra".toString(),
                    '-v', "${lamp.resolve('recordings')}:/oillamp/recordings".toString(),
                    '-v', "${agentHome}:/home/agent".toString(),
                    IMAGE)

        then: 'it starts'
            restarted.ok

        and: 'and reports itself ready, as it did the first time'
            waitForFile(readyFile, 60)

        and: 'without the bind failure that used to be the only trace of this'
            var logs = Spike.run('podman', 'logs', CONTAINER)
            !logs.mentions('Failed to listen on socket')
            !logs.mentions('exited during startup')

        and: """the desktop actually answers - the assertion that matters, because every
                weaker one passed while this was broken"""
            var viewer = Spike.run(Duration.ofSeconds(15), 'timeout', '8', 'vncviewer',
                    vncSocket.toString())
            viewer.mentions('Connected to socket')
            viewer.mentions('RFB protocol version 3.8')

        and: 'and so does the shell, which is what made the failure look partial'
            inSandbox('echo second-session-ok').mentions('second-session-ok')
    }

    def 'Unrecorded, the desktop takes the size a viewer asks for, and its windows follow'() {
        reportInfo """
            Genies can show the desktop in a panel next to the chat, at the panel's size. It asks
            wayvnc, which changes the size of sway's screen. sway sizes a fullscreen window to the
            screen by itself. A floating window it leaves where it was, which on a smaller screen
            can be out of reach, so keep-windows-on-screen moves it back, and when the screen grows
            again, returns it to where it was. `lamp info` tells the agent the size it has now.

            The window here is xev, an X11 program, opened near the bottom right of 1280x720, and
            foot, fullscreen.
        """
        given: 'the same lamp, without the recording'
            restart(['OILLAMP_RECORDING_ENABLED': 'false'])
            inSandbox('''
                nohup xev -geometry 400x300+840+380 > /dev/null 2>&1 &
                nohup foot --fullscreen > /dev/null 2>&1 &
                sleep 3
            '''.stripIndent())
            var before = swayWindow('Event Tester')

        when: 'a viewer asks for 640x400'
            var answer = askForSize(640, 400)
            Thread.sleep(2000)
            var info = inSandbox('lamp info').out.readLines()
            var floating = swayWindow('Event Tester')
            var fullscreen = swayWindow { it.app_id == 'foot' }

        then: 'the desktop has the size'
            answer == 0
            info.take(2) == ['size       640x400',
                             'own size   1280x720, which it goes back to when the user stops showing it in a panel']

        and: 'the fullscreen window has the new size'
            fullscreen.rect.width == 640 && fullscreen.rect.height == 400

        and: 'the floating window is on the screen, title bar and all, at the size it had'
            var box = outerBox(floating)
            box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= 640 && box[1] + box[3] <= 400
            box[2] == outerBox(before)[2]

        when: 'the viewer gives the desktop its own size back'
            askForSize(1280, 720)
            Thread.sleep(2000)

        then: 'the floating window is back where it was'
            outerBox(swayWindow('Event Tester')) == outerBox(before)
            swayWindow { it.app_id == 'foot' }.rect.width == 1280

        cleanup:
            inSandbox('pkill -x xev; pkill -x foot; true')
    }

    def 'lamp show reaches the session on the host'() {
        reportInfo """
            `lamp show` sends one line of JSON to /oillamp/sockets/host/desktop.sock. That
            directory is the host's, mounted read-only into the sandbox, and a socket in it is
            served by the session on the host. Connecting to it needs no write access, which is
            what this checks for real: the session's side is in ShowingTheUserTheDesktopSpec.
        """
        given: 'the host listening on the desktop socket, as the session does'
            var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
            server.bind(java.net.UnixDomainSocketAddress.of(lamp.resolve('sockets/host/desktop.sock')))
            String asked = null
            var host = Thread.start {
                server.accept().withCloseable { client ->
                    asked = new BufferedReader(new InputStreamReader(java.nio.channels.Channels.newInputStream(client))).readLine()
                    client.write(java.nio.ByteBuffer.wrap('{"ok":true,"text":"The user was asked."}\n'.bytes))
                }
            }

        when:
            var shown = inSandbox('lamp show "the chart you asked for"')
            host.join(10_000)

        then:
            shown.ok
            shown.mentions('The user was asked.')
            new groovy.json.JsonSlurper().parseText(asked) == [op: 'show', what: 'the chart you asked for']

        cleanup:
            server?.close()
            Files.deleteIfExists(lamp.resolve('sockets/host/desktop.sock'))
    }

    // ─── the desktop's size, from outside ──────────────────────────────────────────────────

    /** Starts the sandbox again, with these settings changed in runtime.env. */
    private void restart(Map<String, String> settings) {
        Spike.run('podman', 'rm', '-f', CONTAINER)
        var env = lamp.resolve('session/runtime.env')
        var text = Files.readString(env)
        settings.each { key, value -> text = text.replaceAll("(?m)^" + key + "=.*\$", key + "='" + value + "'") }
        Files.writeString(env, text)
        // The previous session's ready.json would answer for this one.
        Spike.run('podman', 'unshare', 'rm', '-f', lamp.resolve('sockets/infra/ready.json').toString())
        var started = Spike.run('podman', 'run', '-d', '--name', CONTAINER,
                '--network=none', '--read-only', '--user', '0:0',
                '--userns=keep-id:uid=1000,gid=1000',
                '--tmpfs', '/run:rw,mode=755', '--tmpfs', '/tmp:rw',
                '-v', "${lamp.resolve('session')}:/oillamp/session:ro".toString(),
                '-v', "${lamp.resolve('sockets/host')}:/oillamp/sockets/host:ro".toString(),
                '-v', "${lamp.resolve('sockets/agent')}:/oillamp/sockets/agent".toString(),
                '-v', "${lamp.resolve('sockets/infra')}:/oillamp/sockets/infra".toString(),
                '-v', "${lamp.resolve('recordings')}:/oillamp/recordings".toString(),
                '-v', "${agentHome}:/home/agent".toString(),
                IMAGE)
        assert started.ok, started.describe()
        waitForFile(lamp.resolve('sockets/infra/ready.json'), 60)
    }

    /**
     * Asks the desktop's VNC server for another size, as Genies does (the ExtendedDesktopSize
     * extension of RFB), and returns how that went: 0 the desktop has the size, 1 not allowed.
     *
     * <p>wayvnc answers 4, "passed on", and then reports the new size once sway has made it, as it
     * reports any change of size. It does that only while a viewer is connected, so this stays
     * until then. Afterwards it asks for one more picture: a viewer that was told no must still be
     * served, or Genies would lose the desktop it shows.
     *
     * <p>Each message goes out in one write, as Genies sends it. wayvnc 0.9.1 misreads a request
     * for another size that arrives in pieces: it dropped the viewer ("uninterpretable qemu
     * message"), and on a recorded desktop the whole desktop crashed.
     */
    private int askForSize(int width, int height) {
        int answer = -1
        boolean servedAfterwards = false
        var asking = Thread.start {
            java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(lamp.resolve('sockets/infra/vnc.sock'))).withCloseable { channel ->
                var input = new DataInputStream(java.nio.channels.Channels.newInputStream(channel))
                var output = new DataOutputStream(new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel)))
                input.readFully(new byte[12]); output.write('RFB 003.008\n'.bytes); output.flush()
                input.readFully(new byte[input.readUnsignedByte()]); output.writeByte(1); output.flush()   // no password
                assert input.readInt() == 0
                output.writeByte(1); output.flush()                                       // shared
                input.readUnsignedShort(); input.readUnsignedShort()
                var format = new byte[16]; input.readFully(format)
                int bytesPerPixel = format[0].intdiv(8)
                input.readFully(new byte[input.readInt()])                                // the name
                output.writeByte(2); output.writeByte(0); output.writeShort(2)            // encodings:
                output.writeInt(0); output.writeInt(-308)                                 // raw, sizes
                var update = { boolean onlyChanges ->
                    output.writeByte(3); output.writeByte(onlyChanges ? 1 : 0); output.writeLong(0x0000_0000_0001_0001L)
                }
                update(true)
                output.flush()
                output.writeByte(251); output.writeByte(0)                                // set the size
                output.writeShort(width); output.writeShort(height)
                output.writeByte(1); output.writeByte(0)                                  // one screen
                output.writeInt(0); output.writeShort(0); output.writeShort(0)
                output.writeShort(width); output.writeShort(height); output.writeInt(0)
                output.flush()
                boolean waitingForSway = false
                while (!servedAfterwards) {
                    boolean answeredBefore = answer >= 0
                    assert input.readUnsignedByte() == 0, 'the VNC server sent something other than a picture'
                    input.readByte()
                    int rectangles = input.readUnsignedShort()
                    boolean picture = false
                    for (int i = 0; i < rectangles; i++) {
                        int x = input.readUnsignedShort(), y = input.readUnsignedShort()
                        int w = input.readUnsignedShort(), h = input.readUnsignedShort()
                        int encoding = input.readInt()
                        if (encoding == -308) {
                            input.readFully(new byte[3 + 16 * input.readUnsignedByte()])
                            if (x == 1 && y == 4) waitingForSway = true       // passed on to sway
                            else if (x == 1) answer = y                       // done, or not allowed
                            else if (waitingForSway && w == width && h == height) answer = 0
                        } else {
                            input.readFully(new byte[w * h * bytesPerPixel])
                            picture = true
                        }
                    }
                    if (answeredBefore && picture) servedAfterwards = true
                    update(answer < 0)
                    output.flush()
                }
            }
        }
        asking.join(15_000)
        assert answer >= 0, 'the VNC server did not answer the request for another size'
        assert servedAfterwards, 'the VNC server stopped serving the viewer after it answered'
        answer
    }

    /** A window in sway's tree, read as the infra user, which owns sway's control socket. */
    private Map swayWindow(String name) { swayWindow { it.name == name } }

    private Map swayWindow(Closure<Boolean> which) {
        var tree = Spike.run('podman', 'exec', '-u', 'lamp', CONTAINER, 'sh', '-c',
                'swaymsg -s /run/lamp/sway-ipc.*.sock -t get_tree')
        assert tree.ok, tree.describe()
        var found = []
        var walk
        walk = { Map node ->
            if (node.type in ['con', 'floating_con'] && (node.name || node.app_id) && which(node)) found << node
            (node.nodes + node.floating_nodes).each { walk(it as Map) }
        }
        walk(new groovy.json.JsonSlurper().parseText(tree.out) as Map)
        assert found.size() == 1, "expected one such window in sway's tree, found ${found.size()}"
        found.first()
    }

    /** A floating window's whole box, title bar included: x, y, width, height. */
    private static List<Integer> outerBox(Map window) {
        [window.rect.x, window.rect.y - window.deco_rect.height, window.rect.width, window.rect.height + window.deco_rect.height]
    }

    /**
     * How many pixels differ between two captures of the same desktop.
     *
     * <p>This asks directly whether an application appeared, rather than inferring it from how well
     * a PNG compressed. Both images come from the
     * same desktop seconds apart, so the only thing that can differ is what was launched.
     */
    private static int differingPixels(Path before, Path after) {
        var a = javax.imageio.ImageIO.read(before.toFile())
        var b = javax.imageio.ImageIO.read(after.toFile())
        assert a != null && b != null, "could not read $before or $after as images"
        assert a.width == b.width && a.height == b.height,
                "captures of the same desktop should be the same size"
        var count = 0
        for (int y = 0; y < a.height; y++)
            for (int x = 0; x < a.width; x++)
                if ((a.getRGB(x, y) & 0xffffff) != (b.getRGB(x, y) & 0xffffff)) count++
        count
    }

    private static void waitForFile(Path path, int seconds) {
        for (int i = 0; i < seconds * 4 && !Files.exists(path); i++) Thread.sleep(250)
        assert Files.exists(path), "$path never appeared within ${seconds}s"
    }
}
