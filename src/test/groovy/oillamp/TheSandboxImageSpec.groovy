package oillamp

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Static checks of the files the sandbox image is built from ({@code src/main/resources/image}).
 *
 * <p>These files are shipped as resources and only ever executed inside a container, which means
 * a typo in one of them surfaces as a container that dies during startup with a message nobody
 * sees. Building the image to find that out takes minutes; these scenarios take milliseconds and
 * catch the whole class of error that is worth catching without a container: a script that does
 * not parse, a file the Containerfile copies but nobody wrote, a reference to something a design
 * decision removed.
 *
 * <p>They cannot check that the image works; the spike tests do that.
 */
class TheSandboxImageSpec extends Specification {

    static final Path IMAGE = Path.of('src/main/resources/image')

    def 'every shell script in the image parses'() {
        reportInfo """
            `bash -n` is the cheapest possible check and catches the mistake that actually happens
            when editing a 150-line entrypoint: an unclosed quote or a missing `fi` twenty lines
            from where it was typed. Inside a container that appears as pid 1 exiting immediately,
            which the host reports as "the sandbox did not become ready" - true, and useless.
        """
        expect:
            script.toFile().exists()
            var check = Spike.run('bash', '-n', script.toString())
            assert check.ok, "shell syntax error in $script:\n${check.describe()}"

        where:
            script << [
                IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'),
                IMAGE.resolve('rootfs/usr/local/bin/lamp'),
                IMAGE.resolve('build/install-node.sh'),
                IMAGE.resolve('build/install-agent-tools.sh'),
                IMAGE.resolve('build/install-sdkman.sh'),
                IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'),
            ]
    }

    def 'everything the Containerfile copies actually exists'() {
        given: 'the Containerfile'
            var containerfile = Files.readString(IMAGE.resolve('Containerfile'))

        expect: 'the two trees it copies are present'
            Files.isDirectory(IMAGE.resolve('rootfs'))
            Files.isDirectory(IMAGE.resolve('build'))
            containerfile.contains('COPY rootfs/ /')
            containerfile.contains('COPY build/ /tmp/oillamp-build/')

        and: 'and the files it then runs or chmods are in them'
            Files.isExecutable(IMAGE.resolve('build/install-node.sh'))
            Files.isExecutable(IMAGE.resolve('build/install-agent-tools.sh'))
            Files.isExecutable(IMAGE.resolve('build/install-sdkman.sh'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/bin/lamp'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/lib/oillamp/lamp-dock'))
    }

    def 'the image carries no trace of the Java desktop helper that was replaced by a shell script'() {
        reportInfo """
            An earlier design had a Java desktop helper (`oillamp-rfb`, `lamp-helper`). It was
            replaced by the `lamp` shell script: the agent can drive the desktop with the ordinary
            Wayland tools, and a script it can read is better than a program it cannot. This makes
            sure no trace of the old helper was copied into the image files.
        """
        expect: 'no file in the image mentions either, text or otherwise'
            allImageFiles.every { file ->
                // Bytes rather than readString: the image carries a PNG, and decoding that as
                // text throws. Searching the raw bytes also catches a reference embedded in
                // something that is not a text file at all.
                !containsAscii(file, 'lamp-helper') && !containsAscii(file, 'oillamp-rfb')
            }
    }

    /** True when the file's bytes contain this ASCII sequence, whatever kind of file it is. */
    private static boolean containsAscii(Path file, String needle) {
        var haystack = Files.readAllBytes(file)
        var target = needle.getBytes('US-ASCII')
        for (int start = 0; start <= haystack.length - target.length; start++) {
            var matched = true
            for (int i = 0; i < target.length; i++)
                if (haystack[start + i] != target[i]) { matched = false; break }
            if (matched) return true
        }
        false
    }

    def 'the compositor config binds no key to running a command'() {
        reportInfo """
            The sharpest edge in the whole image. sway runs as `lamp`, the user that owns the
            recording and the VNC server; the agent can type into the desktop. A single
            `bindsym … exec …` would therefore hand the agent a way to run commands as `lamp` and
            quietly end the recording - defeating the one guarantee the human is relying on.

            The config says this in a comment. A comment is not enforcement.
        """
        when:
            var config = Files.readString(IMAGE.resolve('rootfs/etc/oillamp/sway/config'))
            var bindings = config.readLines()
                    .findAll { it.trim().startsWith('bindsym') }

        then: 'there are bindings, or this scenario is checking nothing'
            !bindings.isEmpty()

        and: 'and not one of them runs anything'
            bindings.every { line ->
                !(line =~ /\b(exec|exit|reload|kill)\b/)
            }
    }

    def 'the dock runs as the agent, so its buttons run nothing the agent could not run itself'() {
        reportInfo """
            Every button on the dock runs a command the agent wrote, whenever the user clicks it.
            Started as the agent, that is no more than the agent can already do. Started as
            `lamp`, the user that owns the recording, one click would let the agent's text end
            the recording. So the entrypoint must start it as the agent and never as `lamp`.
        """
        when:
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var dockLines = entrypoint.readLines().findAll { it.contains('lamp-dock') || it.contains(' dock ') }

        then:
            entrypoint.contains('drop agent 077 dock ')
            dockLines.every { !it.contains('drop lamp') }
    }

    def 'a dock that cannot be installed costs only the dock'() {
        reportInfo """
            The dock is a convenience on top of the desktop, ssh and the recording. If Debian ever
            stops shipping one of its GTK packages, the image must still build, and the dock
            reports that it cannot run instead of being started again every few seconds.
        """
        when:
            var containerfile = Files.readString(IMAGE.resolve('Containerfile'))
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var dock = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/lamp-dock'))

        then: 'the packages are installed on their own, and a failure only says so'
            containerfile.contains('gir1.2-gtklayershell-0.1 \\\n      || echo "GTK for Python unavailable')

        and: 'the dock says it cannot run, and the entrypoint then stops starting it'
            dock.contains('CANNOT_RUN = 78')
            entrypoint.contains('[ "$status" = 78 ] && exit 0')
    }

    def 'the agent is allowed on the X11 display, so Swing applications can open windows'() {
        reportInfo """
            X11 applications such as Java Swing draw through Xwayland, which sway starts as the
            infra user `lamp`. Xwayland only accepts its own user, so the agent was refused with
            "Authorization required" until the entrypoint started granting it access.

            Three things have to hold together. sway must start Xwayland at once and keep it
            running, because a new Xwayland forgets the grant. The entrypoint must grant it. And
            the image must contain `xhost`, the program that grants it.
        """
        when:
            var sway = Files.readString(IMAGE.resolve('rootfs/etc/oillamp/sway/config'))
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var containerfile = Files.readString(IMAGE.resolve('Containerfile'))

        then: 'Xwayland starts with sway and stays running'
            sway.readLines().any { it.trim() == 'xwayland force' }

        and: 'the entrypoint lets the agent user connect'
            entrypoint.contains('xhost +si:localuser:agent')
            entrypoint.contains('install -d -o root -g root -m 1777 /tmp/.X11-unix')

        and: 'the image contains xhost'
            containerfile.contains('x11-xserver-utils')
    }

    def 'sshd is configured so that a shell cannot become a tunnel out'() {
        reportInfo """
            Every outbound connection from the sandbox goes through the egress proxy. SSH into the sandbox is a hole
            straight through that if forwarding is left on: `ssh -L` would reach anything the
            container can, and the proxy would never see it. These five options are not hardening
            extras, they are what makes the network policy mean anything.
        """
        when:
            var sshd = Files.readString(IMAGE.resolve('rootfs/etc/oillamp/sshd_config'))

        then:
            forbidden.each { option ->
                assert sshd.contains("$option no"), "sshd_config must set `$option no`"
            }

        and: 'and password login is off, since the only credential is the per-lamp key'
            sshd.contains('PasswordAuthentication no')
            sshd.contains('PubkeyAuthentication yes')

        where:
            forbidden = ['AllowTcpForwarding', 'AllowStreamLocalForwarding',
                         'AllowAgentForwarding', 'X11Forwarding', 'PermitTunnel']
    }

    def 'the agent shell is told where the proxy is, in every form a tool might read'() {
        reportInfo """
            There is no DNS in the sandbox. A tool that does not honour proxy variables
            simply fails, so the ones that do must find them already set - and they disagree about
            capitalisation, which is why both spellings are exported rather than one.
        """
        when:
            var profile = Files.readString(IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'))

        then:
            ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'NO_PROXY', 'no_proxy']
                    .every { profile.contains("export $it") || profile.contains("$it=") }

        and: 'and the JVM, which reads none of them, is told separately'
            profile.contains('JAVA_TOOL_OPTIONS')
            profile.contains('-Dhttps.proxyHost=127.0.0.1')
    }

    def 'Java in the agent shell finds native libraries in ~/libs and in the system directories'() {
        reportInfo """
            The agent guide promises that System.loadLibrary finds libraries in ~/libs with no
            extra flags. It must not stop finding the ones installed with the system: the shell
            once set -Djava.library.path=~/libs, which replaced Java's list instead of adding to
            it. This loads the real profile into bash and asks a real JVM for its library path.
        """
        given:
            var home = Files.createTempDirectory('oillamp-agent-home')
            var probe = home.resolve('LibraryPath.java')
            Files.writeString(probe, 'class LibraryPath { public static void main(String[] a) { ' +
                    'System.out.println(System.getProperty("java.library.path")); } }')
            var java = Path.of(System.getProperty('java.home'), 'bin', 'java').toString()

        when: 'a shell reads the profile and runs Java'
            var process = new ProcessBuilder('bash', '-c',
                    '. "$PROFILE" && exec "$JAVA" "$HOME/LibraryPath.java"')
            process.environment().putAll(HOME: home.toString(), JAVA: java, LD_LIBRARY_PATH: '',
                    PROFILE: IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh').toString())
            var started = process.redirectErrorStream(false).start()
            var paths = started.inputStream.text.trim().split(':') as List
            started.waitFor()

        then: 'the agent\'s own directory comes first, and the system directories are still there'
            paths.first() == home.resolve('libs').toString()
            paths.contains('/usr/lib')
    }

    def 'the agent harnesses are installed into the image, so they are there from the first session'() {
        reportInfo """
            The harnesses are installed when the image is built, so the agent has them the moment
            it logs in, without waiting for downloads. (They could also be installed later through
            the egress proxy.) That is why the build installs them and why the default is not
            empty.

            The friendly names a user writes in oillamp.toml are not package names, and the
            mapping between them is the part that rots: a project renames, a scope changes, and
            the sandbox quietly ships without a harness. So the mapping is pinned here.
        """
        when:
            var script = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))

        then: 'the two harnesses map to the packages that actually publish them'
            script.contains('@earendil-works/pi-coding-agent')
            script.contains('opencode-ai')

        and: 'and the image asks for both by default, so a plain build is usable'
            Files.readString(IMAGE.resolve('Containerfile')).contains('ARG AGENT_TOOLS="opencode pi"')
    }

    def 'nothing about the agent tooling can fail the image build'() {
        reportInfo """
            The user was explicit about this, and they are right: a harness is a convenience, while
            the desktop, the shell, the recording and ssh are the product. Losing all of them
            because a registry was briefly unreachable would be a bad trade made automatically.

            So the installer reports failures and exits 0 regardless, and the Containerfile layer
            that runs it cannot fail either.
        """
        when:
            var script = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))

        then: 'the script ends by succeeding whatever happened inside it'
            script.contains('exit 0')
            script.readLines().any { it.startsWith('main "$@" ||') }

        and: 'and each individual tool is attempted independently, not as one all-or-nothing step'
            script.contains('WARNING: could not install')
    }

    def 'the pi extension is put where a bind-mounted home cannot hide it'() {
        reportInfo """
            pi reads its extensions from its agent directory, which lives in the agent's home -
            and that home is a bind mount from the lamp, so anything the image writes there at
            build time is invisible the moment the container starts. This is the same class of
            mistake as the sockets directory: a path that exists at build time and means something
            different at run time.

            So the extension is built into /usr/local/share and copied into the home once per
            session, and - like everything else about the tooling - it may not stop the sandbox.
        """
        when:
            var installer = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))
            var entrypoint = Files.readString(
                    IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))

        then: 'the build installs it outside the home, using pi own relocation variable'
            installer.contains('PI_CODING_AGENT_DIR')
            installer.contains('/usr/local/share/oillamp/pi')
            installer.contains('git:github.com/edenai/pi-edenai')

        and: 'and the entrypoint copies the whole agent directory across, not just an extensions dir'
            entrypoint.contains('seed_pi_agent_dir')
            entrypoint.contains('target="$HOME/.pi/agent"')

        and: 'never over anything the agent already has'
            entrypoint.contains('[ -e "$target/$name" ] && continue')

        and: 'and cannot take the session down with it'
            entrypoint.contains('seed_pi_agent_dir || true')
    }

    def 'both harnesses reach Eden AI through oillamp\'s relay, holding no key, and are offered only EU models'() {
        reportInfo """
            The harnesses never hold the model key. They send their requests to oillamp's relay
            inside the sandbox, 127.0.0.1:3129, and oillamp adds the key on the host. The address
            is the same in every sandbox; where it leads is decided outside.

            pi's Eden AI extension reads its endpoint from EDENAI_BASE_URL and its key from
            EDENAI_API_KEY, which must not be empty, so the profile sets a placeholder. With
            EDENAI_EU_ONLY it offers only the models served in the EU; oillamp decides on the host
            whether that applies (on for Eden AI, off for a model server of the user's own) and
            says so in OILLAMP_MODEL_EU_ONLY, and it is on unless oillamp said otherwise. The
            profile sets all three after reading runtime.env.

            opencode has Eden AI built in, but pointed at the global endpoint and with a model
            list mostly made of models the EU endpoint does not serve. The build writes a
            configuration file that points it at the relay instead, and the profile names it in
            OPENCODE_CONFIG. The model list in that file comes from the EU endpoint's own catalog,
            which needs no key, and only EU models are kept from it.

            Inside the container, a bridge run by the infrastructure user connects the relay's
            port to the socket on which oillamp listens.
        """
        when:
            var profile = Files.readString(IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'))
            var installer = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))
            var writer = Files.readString(IMAGE.resolve('build/write-opencode-config.mjs'))

        then: 'pi is told the relay and a placeholder key, after the session\'s own variables are read'
            profile.contains('export EDENAI_BASE_URL=http://127.0.0.1:${OILLAMP_MODEL_PORT:-3129}/v3')
            profile.contains('export EDENAI_API_KEY=held-by-oillamp-on-the-host')

        and: 'EU only unless oillamp said otherwise, as the profile\'s own line decides it in bash'
            var euLine = profile.lines().filter { it.contains('OILLAMP_MODEL_EU_ONLY') && !it.startsWith('#') }.findFirst().orElseThrow()
            euOnlyWith(euLine, null) == '1'
            euOnlyWith(euLine, '1') == '1'
            euOnlyWith(euLine, '0') == ''
            profile.indexOf('EDENAI_BASE_URL=') > profile.indexOf('runtime.env && set +a')
            profile.indexOf('EDENAI_API_KEY=held') > profile.indexOf('runtime.env && set +a')

        and: 'the relay\'s port leads to oillamp\'s socket on the host'
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            entrypoint.contains('drop lamp 077 model socat "TCP-LISTEN:${OILLAMP_MODEL_PORT},bind=127.0.0.1,reuseaddr,fork"')
            entrypoint.contains('UNIX-CONNECT:/oillamp/sockets/host/model.sock')

        and: 'opencode is given its configuration, which the build writes'
            profile.contains('export OPENCODE_CONFIG=/usr/local/share/oillamp/opencode/opencode.json')
            installer.contains('OPENCODE_CONFIG_FILE=/usr/local/share/oillamp/opencode/opencode.json')
            installer.contains('write-opencode-config.mjs')

        and: 'that configuration sends opencode to the relay, and takes its models from the EU catalog'
            writer.contains('const RELAY_BASE_URL = "http://127.0.0.1:3129/v3"')
            writer.contains('baseURL: RELAY_BASE_URL')
            writer.contains('fetch(`${EU_BASE_URL}/models`')
            writer.contains('region?.code?.toLowerCase() === "eu"')
            writer.contains('provider.whitelist')

        and: 'and if it cannot be written, the build goes on'
            installer.contains('could not configure opencode')
    }

    def 'sdkman is installed where it can be written to, which is not where it is built'() {
        reportInfo """
            SDKMAN is how a JVM project gets the JDK, Groovy or Gradle it actually asks for, and
            it is the only way to get one in here: there is no sudo, no apt and a read-only root
            filesystem, so the usual answers are all unavailable.

            That read-only filesystem is also why SDKMAN cannot simply live in /usr/local and be
            used from there. It writes while it works - candidates, caches, a lock file - so the
            copy that gets used has to be somewhere writable. The agent's home is both writable
            and persistent, which additionally means a JDK installed in one session is still
            there in the next.

            The copy is made as `agent`, not as root. /home/agent is a bind mount from the lamp,
            and uid 1000 inside the container is the human outside it, while root inside is a
            subuid they would find hard to delete afterwards.
        """
        when:
            var installer = Files.readString(IMAGE.resolve('build/install-sdkman.sh'))
            var entrypoint = Files.readString(
                    IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var profile = Files.readString(IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'))

        then: 'the build puts it outside the home, where a bind mount cannot hide it'
            installer.contains('SDKMAN_DIR=/usr/local/share/oillamp/sdkman')

        and: 'and does not let the installer edit a shell profile of its own choosing'
            installer.contains('rcupdate=false')

        and: 'prompts are turned off, because an agent over ssh cannot answer one'
            installer.contains('sdkman_auto_answer=true')

        and: 'and nothing about it can fail the build'
            installer.contains('exit 0')
            installer.readLines().any { it.startsWith('main ||') }

        then: 'the entrypoint copies it into the home as the agent, once'
            entrypoint.contains('seed_sdkman')
            entrypoint.contains('setpriv --reuid=agent --regid=agent')
            entrypoint.contains('cp -a "$source" /home/agent/.sdkman')

        and: 'leaving an existing one alone, candidates and all'
            entrypoint.contains('[ -e /home/agent/.sdkman ] && return 0')

        and: 'and cannot take the session down with it'
            entrypoint.contains('seed_sdkman || true')

        then: 'the login shell sources the copy in the home, not the one in the image'
            profile.contains('export SDKMAN_DIR="$HOME/.sdkman"')
            profile.contains('. "$SDKMAN_DIR/bin/sdkman-init.sh"')

        and: 'guarded, because sdkman-init.sh is bash and /bin/sh here is not'
            profile.contains('[ -n "${BASH_VERSION:-}" ]')
    }

    private static List<Path> getAllImageFiles() {
        Files.walk(IMAGE).filter { Files.isRegularFile(it) }.toList()
    }

    /** What EDENAI_EU_ONLY is after running `line` in bash with OILLAMP_MODEL_EU_ONLY set to `value`. */
    private static String euOnlyWith(String line, String value) {
        var bash = new ProcessBuilder('bash', '-c', line + '\nprintf %s "${EDENAI_EU_ONLY:-}"')
        bash.environment().remove('EDENAI_EU_ONLY')
        if (value != null) bash.environment().put('OILLAMP_MODEL_EU_ONLY', value)
        else bash.environment().remove('OILLAMP_MODEL_EU_ONLY')
        var process = bash.start()
        var out = process.inputStream.text
        process.waitFor()
        out
    }
}
