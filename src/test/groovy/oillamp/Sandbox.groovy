package oillamp

import dev.oillamp.Machine
import dev.oillamp.OilLamp

import java.nio.file.Files
import java.nio.file.Path

/**
 * A place to run oillamp against, for the scenarios in this package.
 *
 * <p>Note what this file can and cannot see: it lives outside {@code dev.oillamp}, so the only
 * oillamp types it can touch are the six that are public. Every scenario in this package is
 * therefore written against the same surface a real caller has — not because a rule says so,
 * but because the compiler will not allow anything else.
 */
class Sandbox {

    final Path home
    final Path runtime
    private Machine.Simulation simulation

    Sandbox(Path temporaryDirectory) {
        home = Files.createDirectories(temporaryDirectory.resolve('home/dev'))
        runtime = Files.createDirectories(temporaryDirectory.resolve('run/user/1000'))
        simulation = Machine.simulated()
                .ubuntuWithEverything()
                .user('dev', 1000, 1000, home)
                .runtimeDirectory(runtime)
    }

    /** Adjusts the machine, e.g. {@code sandbox.machine { it.withoutPodman() }}. */
    Sandbox machine(Closure<Machine.Simulation> change) {
        simulation = change(simulation)
        this
    }

    OilLamp getOillamp() { OilLamp.on(simulation.build()) }

    /** A path inside the sandbox's home that does not exist yet. */
    Path lampPath(String name = 'feature-x') { home.resolve('lamps').resolve(name) }

    /** Writes a lamp configuration file, creating the lamp directory if needed. */
    Path givenConfig(Path lamp, String toml) {
        Files.createDirectories(lamp)
        Path file = lamp.resolve('oillamp.toml')
        Files.writeString(file, toml)
        file
    }

    /** Writes the user-global configuration at {@code ~/.config/oillamp/config.toml}. */
    void givenGlobalConfig(String toml) {
        Path file = home.resolve('.config/oillamp/config.toml')
        Files.createDirectories(file.parent)
        Files.writeString(file, toml)
    }
}
