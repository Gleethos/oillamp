package oillamp

import dev.oillamp.Machine

import java.time.Duration

/**
 * Runs real commands on this real machine, for the verification spikes of design spec §33.
 *
 * <p>Note what this deliberately is not: it is not a second execution path invented for tests. It
 * goes through {@link Machine#real()}, the same seam oillamp itself runs everything through. If
 * {@code RealMachine} mangles an argument, loses an environment variable or mishandles a timeout,
 * these spikes find out at the same time as the assumption they were written to check.
 *
 * <p>The spikes answer questions the simulation structurally cannot. {@code Machine.simulated()}
 * replays what we already believe about podman and sway; that makes it perfect for testing
 * oillamp's reasoning and worthless for testing whether the belief is true. §33 exists because
 * several of those beliefs are load-bearing and unconfirmed.
 */
class Spike {

    static final String BASE_IMAGE = 'docker.io/library/debian:trixie'

    private static final Machine MACHINE = Machine.real()

    /** True when this machine can run the spikes at all. Scenarios use it to skip, not to fail. */
    static boolean podmanAvailable() {
        try {
            return run('podman', '--version').ok
        } catch (Throwable ignored) {
            return false
        }
    }

    static Result run(String... argv) {
        run(Duration.ofMinutes(5), argv)
    }

    static Result run(Duration timeout, String... argv) {
        var outcome = MACHINE.run(Machine.Command.of(argv).withTimeout(timeout))
        var commandLine = argv.join(' ')
        if (outcome instanceof Machine.Outcome.Finished f)
            return new Result(commandLine, f.exitCode(), f.standardOutput(), f.standardError())
        if (outcome instanceof Machine.Outcome.NotFound n)
            return new Result(commandLine, 127, '', "executable not found: ${n.executable()}")
        if (outcome instanceof Machine.Outcome.TimedOut t)
            return new Result(commandLine, 124, t.standardOutputSoFar(), "timed out after ${t.after()}")
        throw new IllegalStateException("unknown outcome: $outcome")
    }

    /** Runs a command inside a throwaway container on the base image. */
    static Result inBaseImage(String script) {
        run('podman', 'run', '--rm', '--network=none', BASE_IMAGE, 'sh', '-lc', script)
    }

    /**
     * The argv that runs the host's own {@code socat} inside a container that has none.
     *
     * <p>The base image is bare and installing into it needs a network, which is precisely one of
     * the things still to be confirmed. Rather than block the socket spikes behind the network
     * spike, the container borrows the host's binary: mount {@code /usr} read-only, then invoke
     * the host's dynamic loader explicitly with the host's library path, so no library from the
     * image is consulted and the glibc difference between Ubuntu and trixie never arises.
     *
     * <p>This is a spike technique, not a pattern for the product. The real image installs socat
     * normally. It is written down because "why is /usr mounted into this container" is otherwise
     * an excellent question with no answer.
     */
    static List<String> borrowedSocat(String... arguments) {
        [LOADER, '--library-path', HOST_LIBRARY_PATH, '/hostusr/bin/socat', *arguments]
    }

    static final String HOST_LIBRARY_PATH = '/hostusr/lib/x86_64-linux-gnu'
    static final String LOADER = "$HOST_LIBRARY_PATH/ld-linux-x86-64.so.2"

    /**
     * Removes a directory tree that may contain files chowned into the subuid range.
     *
     * <p>Plain {@code rm -rf} fails on those with "Operation not permitted" — the host user does
     * not own uid 166536 and cannot unlink its files. Found the hard way while writing these
     * spikes, and it is the same problem a user will have removing a lamp directory by hand.
     */
    static void removeTree(String path) {
        run('podman', 'unshare', 'rm', '-rf', path)
    }

    /**
     * True when a container can reach the network at all.
     *
     * <p>Rootless podman needs a userspace networking backend — slirp4netns or pasta — and Ubuntu
     * 24.04 installs neither with podman. Without one, every networked container dies with
     * "could not find slirp4netns, the network namespace can't be configured". The spikes that
     * need a network skip rather than fail on such a host, because "this assumption is still
     * unverified" and "this assumption is false" are different answers and must not look alike.
     */
    static boolean containerNetworkWorks() {
        if (!podmanAvailable()) return false
        run(Duration.ofMinutes(2), 'podman', 'run', '--rm', BASE_IMAGE, 'true').ok
    }

    /** Pulls the base image once, so a slow first pull is not blamed on the scenario that hit it. */
    static void ensureBaseImage() {
        if (run('podman', 'image', 'exists', BASE_IMAGE).ok) return
        var pulled = run(Duration.ofMinutes(15), 'podman', 'pull', BASE_IMAGE)
        assert pulled.ok, "could not pull $BASE_IMAGE:\n${pulled.describe()}"
    }

    /**
     * What a command did. Carries stderr and the command line as well as the exit code, because a
     * spike that fails has to say enough for a human to act on without re-running it by hand.
     */
    static class Result {
        final String commandLine
        final int exitCode
        final String out
        final String err

        Result(String commandLine, int exitCode, String out, String err) {
            this.commandLine = commandLine
            this.exitCode = exitCode
            this.out = out
            this.err = err
        }

        boolean isOk() { exitCode == 0 }

        String getText() { (out + '\n' + err).trim() }

        boolean mentions(String needle) { text.toLowerCase().contains(needle.toLowerCase()) }

        String describe() {
            """\
            \$ $commandLine
            exit $exitCode
            ${out ? "stdout:\n${out.readLines().collect { '  ' + it }.join('\n')}" : '(no stdout)'}
            ${err ? "stderr:\n${err.readLines().collect { '  ' + it }.join('\n')}" : ''}""".stripIndent()
        }

        @Override
        String toString() { describe() }
    }
}
