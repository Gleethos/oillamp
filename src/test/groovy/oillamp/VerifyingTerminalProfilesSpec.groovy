package oillamp

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag

import java.time.Duration

/**
 * Checks the terminal table in {@code TerminalEmulatorUtil.java}, and why a session is tied to the SSH
 * connection rather than to the terminal program.
 *
 * <p>Runs on the host, not in a container. It can only check the terminals installed on this
 * machine and reports which it could not check.
 */
@Tag('spike')
class VerifyingTerminalProfilesSpec extends Specification {

    /** The option each terminal in {@code TerminalEmulatorUtil.java} is given to set the window title. */
    static final Map<String, String> TITLE_OPTION = [
            'ptyxis'        : null,          // uses --new-window, no title option
            'gnome-terminal': '--title',
            'kgx'           : '--title',
            'konsole'       : '-p',
            'kitty'         : '--title',
            'foot'          : '--title',
            'alacritty'     : '--title',
            'wezterm'       : null,          // `wezterm start --`, no title option
            'xterm'         : '-T',
    ]

    def 'The terminals installed here accept the arguments oillamp gives them'() {
        reportInfo """
            The profile table is nine argument templates, each one a guess until something runs it.
            A wrong template is not a subtle bug: the terminal window simply never opens, and the
            session dies at AwaitingTerminal with OIL-TERM-002 telling the user their terminal
            failed - which is true and unhelpful.

            This checks the installed terminals by reading their own `--help`, rather than by
            launching windows, so it can run unattended. It reports which terminals it could not
            check at all, because a green result that only covered one row would be misleading.
        """
        given: 'the terminals from the table that exist on this machine'
            var installed = TITLE_OPTION.keySet().findAll { Spike.run('command', '-v', it).ok ||
                                                            Spike.run('which', it).ok }

        expect: 'at least one, or this scenario proves nothing and should say so'
            !installed.isEmpty()

        and: 'each one still has the option oillamp passes it'
            installed.each { terminal ->
                var option = TITLE_OPTION[terminal]
                if (option == null) {
                    reportInfo("$terminal: no title option in the table, nothing to check.")
                    return
                }
                var help = Spike.run(Duration.ofSeconds(30), terminal, '--help-all')
                if (!help.ok) help = Spike.run(Duration.ofSeconds(30), terminal, '--help')
                assert help.text.contains(option),
                        "oillamp passes `$option` to $terminal, but its help does not mention it:\n" +
                        help.describe()
                reportInfo("$terminal: `$option` accepted.")
            }

        and: 'and the table records which rows nobody has verified yet'
            var unchecked = TITLE_OPTION.keySet() - installed
            reportInfo(unchecked.isEmpty()
                    ? 'Every terminal in the table was verified on this machine.'
                    : "Not installed here, so still unverified: ${unchecked.join(', ')}.")
            true
    }

    @Requires({ Spike.run('command', '-v', 'gnome-terminal').ok || Spike.run('which', 'gnome-terminal').ok })
    def 'A terminal emulator returns long before its child exits, so its process id proves nothing'() {
        reportInfo """
            oillamp decides that a session has ended by watching the SSH connection it relays to
            the terminal window, and deliberately *not* by watching the terminal program it
            started. The stated reason is that many terminal emulators do not stay running: they
            hand the new window to a separate, already-running server process and exit at once, so
            the process id oillamp holds means nothing a moment later.

            The whole design of how a session ends depends on this. This measures it: gnome-terminal is asked to run a child that sleeps, and
            the launch returns in a fraction of that time.

            If this scenario ever *fails* - if a terminal really did stay running until its child
            exited - watching the connection would still be right for the other emulators, but the
            simpler design that was rejected would deserve a second look. That is worth knowing, so the check stays.

            Note `--wait` exists and would make gnome-terminal block. oillamp deliberately does not
            use it: relying on it would make the design correct for gnome-terminal and wrong for
            every terminal without an equivalent flag.
        """
        given: 'a child that takes clearly longer than the terminal needs to start'
            var childSeconds = 3

        when: 'the terminal is launched with it, as oillamp would'
            var started = System.nanoTime()
            Spike.run(Duration.ofSeconds(30), 'gnome-terminal', '--title=oillamp-spike',
                      '--', 'sh', '-c', "sleep $childSeconds")
            var elapsed = Duration.ofNanos(System.nanoTime() - started)

        then: 'it came back well before the child was done'
            elapsed.toMillis() < childSeconds * 1000
            reportInfo("gnome-terminal returned after ${elapsed.toMillis()} ms " +
                       "while its child ran for ${childSeconds}s, so its PID says nothing about the session.")
    }
}
