package oillamp

import dev.oillamp.Machine
import dev.oillamp.OilLamp

import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.StandardCharsets

/**
 * oillamp's engine, run on a thread of this JVM against a simulated machine, but looking to a
 * {@link dev.lamp.Lamp} exactly like the separate process it normally starts.
 *
 * <p>The two pipes are real: the lamp writes to the engine's standard input and reads JSON lines
 * from its standard output, and closing the input ends the session. Only where the engine runs is
 * different. A real child process would run against the real machine, and would need podman.
 */
class EngineInThisProcess extends Process {

    /** The application writes here; the engine reads it as its standard input. */
    private final Pipe toEngine = Pipe.open()
    /** The engine writes its events here; the application reads them as its standard output. */
    private final Pipe fromEngine = Pipe.open()
    private final OutputStream applicationWrites = Channels.newOutputStream(toEngine.sink())
    private final InputStream applicationReads = Channels.newInputStream(fromEngine.source())
    private final Thread runner
    private volatile int exit = -1

    /** The command line the lamp asked for, such as {@code at <dir> --embedded}. */
    final List<String> arguments

    EngineInThisProcess(Machine.Simulation simulation, List<String> arguments) {
        this.arguments = List.copyOf(arguments)
        OutputStream engineWrites = Channels.newOutputStream(fromEngine.sink())
        Machine machine = simulation.standardInput(Channels.newInputStream(toEngine.source())).build()
        runner = Thread.start('engine-in-this-process') {
            try {
                var outcome = OilLamp.on(machine).observedBy { event ->
                    synchronized (engineWrites) {
                        engineWrites.write((event.toJson() + '\n').getBytes(StandardCharsets.UTF_8))
                        engineWrites.flush()
                    }
                }.run(arguments as String[])
                exit = outcome.status().code()
            } finally {
                engineWrites.close()
            }
        }
    }

    @Override OutputStream getOutputStream() { applicationWrites }
    @Override InputStream getInputStream() { applicationReads }
    @Override InputStream getErrorStream() { InputStream.nullInputStream() }
    @Override int waitFor() { runner.join(); exit }
    @Override int exitValue() {
        if (runner.alive) throw new IllegalThreadStateException('the engine is still running')
        exit
    }
    @Override void destroy() { applicationWrites.close() }
}
