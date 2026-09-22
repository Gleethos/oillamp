package oillamp

import dev.oillamp.ExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Path

/**
 *  `oillamp doctor` is the command a user reaches for when something is wrong, or before they
 *  trust the tool with anything. Its whole value is the quality of what it tells them.
 */
class CheckingTheMachineSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    def setup() { sandbox = new Sandbox(tmp) }

    def 'A prepared machine is told, item by item, that it can run sandboxes'() {
        reportInfo """
            The happy path still has to *say* something. A user running `doctor` on a machine that
            is fine should see what was actually checked - the distribution, the session type, the
            podman version and how it runs - not a bare "OK" they have no reason to believe.
        """
        given: 'a stock Ubuntu desktop with everything oillamp needs'
            var oillamp = sandbox.oillamp

        when: 'the user asks oillamp to check the machine'
            var outcome = oillamp.run('doctor')

        then: 'it succeeds'
            outcome.status() == ExitStatus.SUCCESS

        and: 'nothing is reported as wrong'
            outcome.errors().isEmpty()

        and: 'the user can see which distribution and session it found'
            outcome.console().contains('Ubuntu 24.04')
            outcome.console().contains('Wayland (GNOME)')

        and: 'and that podman is there, rootless, with the runtime it will use'
            outcome.console().contains('podman 5.4.2')
            outcome.console().contains('rootless')
            outcome.console().contains('crun')
    }

    def 'A machine without podman is told exactly what to install'() {
        reportInfo """
            FR-60 lets oillamp install prerequisites itself, but `doctor` never changes anything.
            So on a machine that is missing packages, the only useful thing doctor can do is name
            them and hand over a command that can be pasted.

            The *second* remedy is the one this scenario guards. Doctor suppresses installing
            internally, and when that was a plain boolean it was indistinguishable from the user
            passing --no-install - so doctor told people to "drop --no-install", naming a flag they
            had never passed and implying oillamp could not install packages at all. That is the
            opposite of what FR-60 promises, and it misled a real reader. Doctor must instead point
            at `oillamp at`, which does install.

            The exit code matters as much as the text: 3 means "prerequisites missing", distinct
            from a generic failure, so a script wrapping oillamp can tell the two apart.
        """
        given: 'a machine where podman and socat were never installed'
            sandbox.machine { it.withoutPodman().withoutPackages('socat') }

        when: 'the user checks the machine'
            var outcome = sandbox.oillamp.run('doctor')

        then: 'oillamp reports the prerequisites as missing, with its own problem code'
            outcome.reported('OIL-PKG-001')

        and: 'it exits with the code that specifically means "prerequisites missing"'
            outcome.status() == ExitStatus.PREREQUISITES_MISSING

        and: 'both missing packages are named'
            var problem = outcome.errors().find { it.code().value() == 'OIL-PKG-001' }
            problem.whatHappened().contains('podman')
            problem.whatHappened().contains('socat')

        and: 'the user is given a command they can paste'
            problem.fixes().any { it.command().orElse('').startsWith('sudo apt-get install -y') }
            problem.fixes().any { it.command().orElse('').contains('podman') }

        and: 'and pointed at the command that installs them, not at a flag they never passed'
            problem.fixes().any { it.description().contains('oillamp at') }
            problem.fixes().every { !it.description().contains('--no-install') }
    }

    def 'Every missing prerequisite is reported in one run, not one per attempt'() {
        reportInfo """
            This is the difference between a tool people trust and one they dread. A machine with
            three problems should produce three problems, so the user fixes them once. Discovering
            them one run at a time - fix, re-run, discover the next - is how a five minute setup
            becomes an afternoon.

            Section 27.1 of the design builds this in: independent checks are *combined* rather
            than short-circuited.

            Run with --no-install, because that is when oillamp has to *report* rather than fix:
            with installing allowed, the first two of these become steps in a plan instead.
        """
        given: 'a machine missing packages AND missing a subordinate id range AND with no terminal'
            sandbox.machine {
                it.withoutPodman()
                  .withoutSubordinateIds()
                  .withoutTerminals()
            }

        when: 'the user asks oillamp to set up without letting it change the machine'
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--no-install')

        then: 'all three are reported together, in one run'
            outcome.reported('OIL-PKG-001')
            outcome.reported('OIL-HOST-010')
            outcome.reported('OIL-TERM-001')

        and: 'and the exit code says the machine is not ready, rather than something generic'
            outcome.status() == ExitStatus.PREREQUISITES_MISSING
    }

    def 'With installing allowed, missing prerequisites become a plan instead of a complaint'() {
        reportInfo """
            The counterpart to the scenario above, and the actual promise of FR-01: on a stock
            machine the user types one command and oillamp fixes what it can. It still has to say
            what it is about to change and why, which is what --dry-run shows here without
            touching anything (FR-12, NFR-04).
        """
        given: 'a machine with no podman and no subordinate id range, but a terminal and sudo'
            sandbox.machine { it.withoutPodman().withoutSubordinateIds() }

        when: 'the user asks what oillamp would do'
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--dry-run')

        then: 'installing the packages and adding an id range are planned, not reported as errors'
            outcome.stepKinds().contains('InstallPackages')
            outcome.stepKinds().contains('AddSubIds')
            !outcome.reported('OIL-PKG-001')

        and: 'the plan says which packages, and which id range it picked'
            outcome.steps().any { it.contains('podman') }
            outcome.steps().any { it.contains('100000-165535') }

        and: 'podman is told to re-read the id map afterwards, or it would keep the old one'
            outcome.stepKinds().contains('PodmanMigrate')
    }

    def 'crun is installed alongside podman, because Ubuntu would otherwise use runc'() {
        reportInfo """
            Found on the first real-hardware run, which no simulation would have caught: Ubuntu
            24.04 ships podman 4.9.3 with **runc**, not crun. GPU passthrough needs crun, because
            only it supports the `--keep-groups` that carries the host's render group into the
            container (section 15.3).

            The failure mode is what makes this worth a scenario rather than a comment. Nothing
            breaks. The desktop simply renders in software, quietly, on the *default* setting
            (`display.gpu = auto`) of the *primary target platform*. A user would have no reason
            to suspect anything and no obvious thing to search for.

            So crun is a required package, not an optional extra, and this scenario exists to
            stop someone removing it later as "podman already works without it" - which is true,
            and is exactly the trap.
        """
        given: 'a stock Ubuntu where podman is present but crun is not'
            sandbox.machine { it.withoutPackages('crun') }

        when: 'the user asks what oillamp would do'
            var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--dry-run')

        then: 'crun is installed rather than left to chance'
            outcome.stepKinds().contains('InstallPackages')
            outcome.steps().any { it.contains('crun') }

        and: 'and the user is told why a runtime they did not ask for is being installed'
            outcome.stepDetails().any { it.contains('runc') && it.contains('render group') }
    }

    def 'A machine blocked by AppArmor gets the remedy for that, not a generic failure'() {
        reportInfo """
            Ubuntu 23.10 and newer refuse unprivileged user namespaces to programs without a
            matching AppArmor profile. Rootless podman needs them, so oillamp simply does not
            work - and the raw error ("cannot clone: Operation not permitted") tells a user
            nothing about why or what to do.

            This is the single most likely reason oillamp fails to start on a current Ubuntu, so
            it gets its own problem code and its own ordered remedies. Note the third remedy is
            deliberately last and explains the trade-off: turning the sysctl off weakens a
            system-wide protection, not just oillamp's sandbox, so oillamp will never do it by
            itself (section 11.2).
        """
        given: 'a modern Ubuntu whose AppArmor policy blocks unprivileged user namespaces'
            sandbox.machine { it.userNamespacesBlockedByAppArmor() }

        when: 'the user checks the machine'
            var outcome = sandbox.oillamp.run('doctor')

        then: 'oillamp names the actual cause rather than the symptom'
            outcome.reported('OIL-PODMAN-004')
            var problem = outcome.errors().find { it.code().value() == 'OIL-PODMAN-004' }
            problem.title().contains('AppArmor')

        and: 'it shows the user the command it ran and what the kernel setting says'
            outcome.console().contains('$ podman unshare true')
            outcome.console().contains('apparmor_restrict_unprivileged_userns: 1')

        and: 'the safe remedies come first'
            var fixes = problem.fixes().collect { it.description() }
            fixes.first().contains('Ubuntu-packaged podman')

        and: 'and disabling the protection is offered last, with the consequence spelled out'
            fixes.last().contains('weakens')
            problem.fixes().last().command().get().contains('apparmor_restrict_unprivileged_userns=0')
    }

    def 'Checking the machine works without a display, because that is what it is for'() {
        reportInfo """
            `oillamp at` opens two windows, so it refuses to run without a graphical session.
            `doctor` must not: "I am on a plain SSH login and oillamp will not start" is exactly
            the situation a user runs doctor to diagnose. Refusing to diagnose it would be absurd.
        """
        given: 'a machine reached over SSH, with no desktop session'
            sandbox.machine { it.noGraphicalSession() }

        when: 'the user checks the machine'
            var outcome = sandbox.oillamp.run('doctor')

        then: 'doctor still runs and reports the machine as usable'
            outcome.status() == ExitStatus.SUCCESS

        when: 'but they try to actually start a session'
            var session = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--dry-run')

        then: 'that is refused, because there is nowhere to put the terminal and the viewer'
            session.reported('OIL-HOST-003')
    }

    def 'An operating system that is not Linux is refused immediately'() {
        reportInfo """
            The sandbox is a rootless podman container built on Linux user namespaces. There is
            no partial version of this on another platform, so oillamp says so plainly instead of
            failing later with something about missing binaries.
        """
        given: 'oillamp running somewhere that is not Linux'
            sandbox.machine { it.notLinux('Mac OS X') }

        when: 'the user checks the machine'
            var outcome = sandbox.oillamp.run('doctor')

        then:
            outcome.reported('OIL-HOST-001')
            outcome.status() != ExitStatus.SUCCESS

        and: 'the message names the platform it actually found'
            outcome.errors().first().whatHappened().contains('Mac OS X')
    }

    def 'A Linux without apt is told what to install by hand rather than being abandoned'() {
        reportInfo """
            oillamp only knows how to install packages with apt (D-17), but nothing else about it
            is Debian-specific. On Fedora or Arch the right answer is therefore not "unsupported"
            - it is "here is the list, install it yourself and I will work". The problem text
            carries the package list for exactly that reason.
        """
        given: 'a Fedora machine'
            sandbox.machine { it.fedora('42') }

        when: 'the user checks the machine'
            var outcome = sandbox.oillamp.run('doctor')

        then:
            outcome.reported('OIL-HOST-002')

        and: 'the packages oillamp needs are listed, so the user can install them themselves'
            var problem = outcome.errors().find { it.code().value() == 'OIL-HOST-002' }
            problem.evidence().any { it.toString().contains('podman') }
            problem.evidence().any { it.toString().contains('tigervnc-viewer') }
    }
}
