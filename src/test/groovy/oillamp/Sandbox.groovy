package oillamp

import dev.oillamp.Machine
import dev.oillamp.OilLamp

import java.nio.file.Files
import java.nio.file.Path

/**
 * A place to run oillamp against, for the scenarios in this package.
 *
 * <p>Note what this file can and cannot see: it lives outside {@code dev.oillamp}, so the only
 * oillamp types it can touch are the public ones. Every scenario in this package is
 * therefore written against the same surface a real caller has, not because a rule says so,
 * but because the compiler will not allow anything else.
 */
class Sandbox {

    final Path home
    final Path runtime
    private Machine.Simulation simulation

    Sandbox(Path temporaryDirectory) {
        home = Files.createDirectories(temporaryDirectory.resolve('home/dev'))
        runtime = shortRuntimeDirectory()
        simulation = Machine.simulated()
                .ubuntuWithEverything()
                .user('dev', 1000, 1000, home)
                .runtimeDirectory(runtime)
    }

    /**
     * A stand-in for {@code $XDG_RUNTIME_DIR}, and deliberately not inside the scenario's own
     * temporary directory.
     *
     * <p>The sockets a session binds are real, and the kernel caps a Unix socket path at 107
     * bytes, which is why oillamp reaches them through {@code $XDG_RUNTIME_DIR} rather than
     * through the lamp. Spock's temporary directories are named after the scenario and are long
     * enough on their own to break that, so a scenario run from one would fail for a reason that
     * has nothing to do with what it is testing. A real runtime directory is {@code /run/user/1000};
     * this is the same shape and the same length.
     */
    private static Path shortRuntimeDirectory() {
        Path base = Path.of('/tmp', 'oil-t')
        Path directory = Files.createTempDirectory(Files.createDirectories(base), '')
        directory.toFile().deleteOnExit()
        directory
    }

    /** Adjusts the machine, e.g. {@code sandbox.machine { it.withoutPodman() }}. */
    Sandbox machine(Closure<Machine.Simulation> change) {
        simulation = change(simulation)
        this
    }

    OilLamp getOillamp() { OilLamp.on(simulation.build()) }

    /**
     * Starts the engine the way a {@link dev.lamp.Lamp} does, but on this machine: in this JVM,
     * against the simulation. Each engine started is kept in {@link #engines}.
     */
    dev.lamp.Lamp.Launcher getLauncher() {
        return { List<String> arguments, Map<String, String> environment ->
            var engine = new EngineInThisProcess(simulation, arguments, environment)
            engines << engine
            engine
        } as dev.lamp.Lamp.Launcher
    }

    final List<EngineInThisProcess> engines = new java.util.concurrent.CopyOnWriteArrayList<>()

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
