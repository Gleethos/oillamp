package oillamp

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * The image resources — design spec §15.2 and Appendices A–E.
 *
 * <p>These files are shipped as resources and only ever executed inside a container, which means
 * a typo in one of them surfaces as a container that dies during startup with a message nobody
 * sees. Building the image to find that out takes minutes; these scenarios take milliseconds and
 * catch the whole class of error that is worth catching without a container: a script that does
 * not parse, a file the Containerfile copies but nobody wrote, a reference to something a design
 * decision removed.
 *
 * <p>They do not and cannot check that the image *works* — that is what the spikes and M3's own
 * integration scenarios are for.
 */
class TheSandboxImageSpec extends Specification {

    static final Path IMAGE = Path.of('src/main/resources/image')

    def 'every shell script in the image parses'() {
        given: """
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
            Files.exists(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/bin/lamp'))
    }

    def 'the image carries no trace of the Java desktop helper that D-27 removed'() {
        given: """
            D-27 dropped oillamp-rfb and the lamp-helper jar: an agent can drive the desktop with
            the ordinary Wayland tools, and a helper it cannot read is worse than a script it can.
            Appendix A still showed the jar being built into the image when these resources were
            written, so this scenario exists to make sure the sketch was not copied faithfully.
        """
        expect:
            allImageFiles.every { file ->
                var text = Files.readString(file)
                !text.contains('lamp-helper') && !text.contains('oillamp-rfb')
            }
    }

    def 'the compositor config binds no key to running a command'() {
        given: """
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

    def 'sshd is configured so that a shell cannot become a tunnel out'() {
        given: """
            §14 puts every outbound byte through the egress proxy. SSH into the sandbox is a hole
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
        given: """
            There is no DNS in the sandbox (§14). A tool that does not honour proxy variables
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

        and: 'and ~/libs is on both library paths, which the agent guide promises'
            profile.contains('LD_LIBRARY_PATH')
            profile.contains('-Djava.library.path=$HOME/libs')
    }

    private static List<Path> getAllImageFiles() {
        Files.walk(IMAGE).filter { Files.isRegularFile(it) }.toList()
    }
}
