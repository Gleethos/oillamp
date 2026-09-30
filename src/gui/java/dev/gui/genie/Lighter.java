package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import dev.gui.model.Settings;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

/// Lights a genie's lamp: starts its sandbox and waits until it runs.
///
/// [LampLighter] does this with oillamp's [dev.lamp.Lamp].
@FunctionalInterface
public interface Lighter {

    /// Starts the lamp in `directory` and returns once its sandbox runs.
    ///
    /// @param key      the model key, which the lamp's engine keeps on the host
    /// @param progress receives what the lamp is doing, in a few words, while it starts
    /// @param events   receives every event of the lamp's session, from any thread, including
    ///                 the progress of the agent's runs
    /// @throws IOException when the lamp did not start; the message says why, for the user
    Lit light(Path directory, Settings settings, String key, Consumer<String> progress, Consumer<LampEvent> events)
            throws IOException, InterruptedException;

    /// The lamp in `directory`, not lit, for what works without its sandbox: its schedule and
    /// its history.
    default Lamp.Starting unlit(Path directory) {
        return Lamp.at(directory);
    }

    /// A lamp whose sandbox runs.
    interface Lit extends AutoCloseable {

        /// Runs a command in the sandbox, as the agent, with the agent's environment.
        Process exec(String... command) throws IOException;

        /// The sandbox desktop's VNC socket.
        Path desktop();

        /// See [Lamp#send].
        LampEvent.Run send(Lamp.Question question) throws IOException, InterruptedException, Lamp.Failed;

        /// See [Lamp#cancel(String)].
        void cancel(String run) throws IOException, InterruptedException, Lamp.Failed;

        /// Ends the sandbox, and returns once it is gone.
        @Override void close();
    }
}
