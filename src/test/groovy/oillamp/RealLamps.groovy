package oillamp

import dev.lamp.Lamp
import dev.oillamp.Machine
import dev.oillamp.OilLamp
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Helpers for the spikes that run whole lamps through {@link Lamp}, on this machine, with real
 * podman: the same engine, image, container, proxy and SSH relay a user gets.
 *
 * <p>Everything here looks at a lamp from the outside, the way the application or the user would:
 * through {@code Lamp}, through oillamp's own commands, through podman, or through the files in the
 * lamp directory. Nothing reaches into the engine.
 */
class RealLamps {

    /** How long a first start may take. It builds the sandbox image when none matches yet. */
    static final Duration FIRST_START = Duration.ofMinutes(25)

    /** How long a start may take when the image already exists. */
    static final Duration LATER_START = Duration.ofMinutes(2)

    /** A fresh directory for a lamp, in the system's temporary directory, not yet created. */
    static Path newLampPath(String name) {
        Files.createTempDirectory("oillamp-spike-${name}-").resolve('lamp')
    }

    /** What a command in the sandbox did. */
    static class Ran {
        String out
        String err
        int exit

        boolean isOk() { exit == 0 }

        @Override String toString() { "exit $exit\nstdout:\n$out\nstderr:\n$err" }
    }

    /**
     * Runs a command in the sandbox through {@link Lamp#exec}, gives it {@code input} on its
     * standard input, and waits for it to finish.
     */
    static Ran feeding(Lamp lamp, String input, String... command) {
        Process process = lamp.exec(command)
        process.outputStream.withCloseable { it.write(input.getBytes('UTF-8')) }
        // Read exactly what arrives. Groovy's waitForProcessOutput reads line by line and ends
        // every line with a line break, even a last line that had none, which would make the
        // sandbox look as if it added one.
        String err = ''
        var errorReader = Thread.start { err = process.errorStream.getText('UTF-8') }
        String out = process.inputStream.getText('UTF-8')
        errorReader.join()
        assert process.waitFor(2, TimeUnit.MINUTES), "`${command.join(' ')}` did not finish"
        new Ran(out: out, err: err, exit: process.exitValue())
    }

    /** Runs a command in the sandbox with nothing on its standard input, and waits for it. */
    static Ran inSandbox(Lamp lamp, String... command) { feeding(lamp, '', command) }

    /** The lamp's identity, as oillamp wrote it into {@code .oillamp/lamp.json}. */
    static Map identity(Path lamp) {
        new JsonSlurper().parse(lamp.resolve('.oillamp/lamp.json').toFile()) as Map
    }

    static String agentId(Path lamp) { identity(lamp).agentId }

    static String containerOf(Path lamp) { "oillamp-${agentId(lamp)}" }

    static Path agentHome(Path lamp) { lamp.resolve("agent-lamp-${agentId(lamp)}") }

    static boolean containerExists(String name) {
        Spike.run('podman', 'container', 'exists', name).ok
    }

    /** Runs an oillamp command the way a user types it, against this machine. */
    static OilLamp.Outcome oillamp(String... argv) {
        OilLamp.on(Machine.real()).run(argv)
    }

    /**
     * Deletes a lamp the way a user must: some of its files belong to the sandbox's own user, so
     * plain {@code rm -rf} cannot remove them. Also removes the temporary directory around it.
     */
    static void remove(Path lamp) {
        if (lamp == null) return
        if (Files.exists(lamp.resolve('.oillamp/lamp.json'))) oillamp('remove', lamp.toString(), '--yes')
        Spike.removeTree(lamp.parent.toString())
    }

    /** Waits until {@code condition} holds, checking every quarter second. */
    static boolean eventually(Duration limit, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + limit.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        condition()
    }
}
