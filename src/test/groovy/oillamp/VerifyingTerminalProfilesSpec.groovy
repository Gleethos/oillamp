package oillamp

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag

import java.time.Duration

/**
 * Spike S9 of design spec §33 — the terminal profile table of §17.4, and the premise of D-09.
 *
 * <p>Runs on the host, not in a container, so it needs no image and no network. It can only check
 * the terminals this machine actually has; the table is data (§17.4) and the rest stay unverified
 * until someone runs this on a machine that has them. That is stated rather than hidden, because
 * a table where nine of ten rows are guesses should look like one.
 */
@Tag('spike')
class VerifyingTerminalProfilesSpec extends Specification {

    /** §17.4, as the option each profile uses to set the window title. */
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

    def 'S9: the terminals installed here accept the arguments §17.4 says they do'() {
        reportInfo """
            The profile table is nine argument templates, each one a guess until something runs it.
            A wrong template is not a subtle bug: the terminal window simply never opens, and the
            session dies at AwaitingTerminal with OIL-TERM-002 telling the user their terminal
            failed - which is true and unhelpful.

            This checks the installed terminals by reading their own `--help`, rather than by
            launching windows, so it can run unattended. It reports which terminals it could not
            check at all, because a green result that only covered one row would be misleading.
        """
        given: 'the terminals from §17.4 that exist on this machine'
            var installed = TITLE_OPTION.keySet().findAll { Spike.run('command', '-v', it).ok ||
                                                            Spike.run('which', it).ok }

        expect: 'at least one, or this scenario proves nothing and should say so'
            !installed.isEmpty()

        and: 'each one still has the option §17.4 passes it'
            installed.each { terminal ->
                var option = TITLE_OPTION[terminal]
                if (option == null) {
                    reportInfo("$terminal: no title option in the table, nothing to check.")
                    return
                }
                var help = Spike.run(Duration.ofSeconds(30), terminal, '--help-all')
                if (!help.ok) help = Spike.run(Duration.ofSeconds(30), terminal, '--help')
                assert help.text.contains(option),
                        "§17.4 passes `$option` to $terminal, but its help does not mention it:\n" +
                        help.describe()
                reportInfo("$terminal: `$option` accepted.")
            }

        and: 'and the table records which rows nobody has verified yet'
            var unchecked = TITLE_OPTION.keySet() - installed
            reportInfo(unchecked.isEmpty()
                    ? 'Every profile in §17.4 was verified on this machine.'
                    : "Not installed here, so still unverified: ${unchecked.join(', ')}.")
            true
    }

    @Requires({ Spike.run('command', '-v', 'gnome-terminal').ok || Spike.run('which', 'gnome-terminal').ok })
    def 'S9: a terminal emulator returns long before its child exits, which is why D-09 exists'() {
        reportInfo """
            D-09 says session end is detected by relaying the SSH connection, *not* by watching the
            terminal process, "because many terminal emulators hand the window to a server process
            and exit immediately, so their PID is meaningless".

            That is the load-bearing sentence behind the entire supervision loop, and until now it
            was a belief. This measures it: gnome-terminal is asked to run a child that sleeps, and
            the launch returns in a fraction of that time.

            If this scenario ever *fails* - if a terminal did block until its child exited - D-09
            would still be right for the other emulators, but the simpler design it rejected would
            deserve a second look. That is worth knowing, so the check stays.

            Note `--wait` exists and would make gnome-terminal block. oillamp deliberately does not
            use it: relying on it would make the design correct for gnome-terminal and wrong for
            every terminal without an equivalent flag.
        """
        given: 'a child that takes clearly longer than the terminal needs to start'
            var childSeconds = 3

        when: 'the terminal is launched with it, the way §17.4 would'
            var started = System.nanoTime()
            Spike.run(Duration.ofSeconds(30), 'gnome-terminal', '--title=oillamp-spike',
                      '--', 'sh', '-c', "sleep $childSeconds")
            var elapsed = Duration.ofNanos(System.nanoTime() - started)

        then: 'it came back well before the child was done'
            elapsed.toMillis() < childSeconds * 1000
            reportInfo("gnome-terminal returned after ${elapsed.toMillis()} ms " +
                       "while its child ran for ${childSeconds}s — its PID says nothing about the session.")
    }
}
