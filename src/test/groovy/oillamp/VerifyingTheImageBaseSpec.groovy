package oillamp

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag

/**
 * Checks that the packages the sandbox image installs exist in Debian trixie.
 *
 * <p>A missing package would only be discovered when an image build fails halfway through on a
 * user's machine. This spec asks trixie directly.
 *
 * <p>Needs a network inside the container, so it skips where rootless podman has no networking
 * backend. Run with {@code ./gradlew spikes}.
 */
@Tag('spike')
@Requires({ Spike.containerNetworkWorks() })
class VerifyingTheImageBaseSpec extends Specification {

    /** The desktop packages in the Containerfile, and what oillamp loses if each is absent. */
    static final Map<String, String> REQUIRED = [
            'sway'        : 'the Wayland compositor the whole desktop is',
            'xwayland'    : 'X11 applications, which is how Swing apps run at all',
            'wayvnc'      : 'the VNC server the human watches through',
            'wf-recorder' : 'the screen recording',
            'grim'        : 'lamp screenshot',
            'slurp'       : 'lamp screenshot --region',
            'wtype'       : 'lamp type and lamp key',
            'foot'        : 'a terminal inside the desktop',
            'openssh-server': 'the sshd the user gets a shell through',
            'socat'       : 'bridging that sshd onto a Unix socket',
            'catatonit'   : 'reaping orphaned processes as pid 1',
            'dbus'        : 'what at-spi and GTK expect to exist',
            'at-spi2-core': 'accessibility, which some toolkits hang without',
            'xdg-utils'   : 'xdg-open, so the agent can open a URL',
    ]

    /** Packages that were once in doubt, with what would be lost without them. */
    static final Map<String, String> DOUBTED = [
            'wlrctl'      : 'lamp click / move / scroll (pointer control)',
            'firefox-esr' : 'a browser for the agent to use',
    ]

    def setupSpec() {
        Spike.ensureBaseImage()
    }

    def 'Every package the sandbox image needs exists in trixie'() {
        reportInfo """
            The image is built once and then cached by content hash, so a missing package is not a
            slow failure - it is a failure the user hits on their very first run, after a long
            download, with an apt error that names one package and explains nothing about what
            oillamp wanted it for.

            Each package is therefore checked *with the reason it is needed*, so that if one has
            been renamed or dropped from trixie, whoever reads this failure knows immediately
            whether the answer is "find the new name" or "redesign that feature".
        """
        given: 'the package list the image installs'
            var missing = [:]

        when: 'trixie is asked about each one'
            var result = Spike.run('podman', 'run', '--rm', Spike.BASE_IMAGE, 'sh', '-lc',
                    'apt-get update -qq >/dev/null 2>&1; ' +
                    REQUIRED.keySet().collect { "echo \"$it=\$(apt-cache policy $it 2>/dev/null | sed -n 's/^  Candidate: //p')\"" }.join('; '))
            result.out.readLines().each { line ->
                var (name, version) = line.tokenize('=') + ['']
                if (!version?.trim()) missing[name] = REQUIRED[name]
            }

        then: 'the query itself succeeded, or the answer below means nothing'
            result.ok

        and: 'and nothing the image needs is absent'
            missing.isEmpty() ||
                    { throw new AssertionError("trixie is missing packages the image needs:\n" +
                            missing.collect { k, v -> "  $k: without it, oillamp loses: $v" }.join('\n') +
                            "\n\nFull output:\n${result.describe()}") }()
    }

    def 'The packages the desktop needs are all in Debian trixie, or the planned fallback applies'() {
        reportInfo """
            `wlrctl` was once uncertain in trixie. Without it, `wtype` still covers the keyboard
            and `grim` and `slurp` still cover screenshots; only pointer control is lost. This
            scenario records which is the case and deliberately does *not* fail when a package is
            absent, because the sandbox still works without it.
        """
        expect: 'a recorded answer either way, so the Containerfile can be written with confidence'
            var result = Spike.run('podman', 'run', '--rm', Spike.BASE_IMAGE, 'sh', '-lc',
                    'apt-get update -qq >/dev/null 2>&1; ' +
                    DOUBTED.keySet().collect { "echo \"$it=\$(apt-cache policy $it 2>/dev/null | sed -n 's/^  Candidate: //p')\"" }.join('; '))
            result.ok

            result.out.readLines().each { line ->
                var (name, version) = line.tokenize('=') + ['']
                reportInfo(version?.trim()
                        ? "$name is available in trixie (${version.trim()})."
                        : "$name is NOT in trixie. Fallback applies: oillamp loses ${DOUBTED[name]}.")
            }
    }
}
