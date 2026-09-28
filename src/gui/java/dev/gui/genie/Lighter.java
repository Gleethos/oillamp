package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import dev.gui.model.Settings;

/// Lights a genie's lamp: starts its sandbox and waits until it runs.
///
/// [LampLighter] does this with oillamp's [dev.lamp.Lamp]. The scenarios give a [GenieRunner] a
/// stand-in instead, so a genie's life can be followed without podman.
@FunctionalInterface
public interface Lighter {

    /// Starts the lamp in `directory` and returns once its sandbox runs.
    ///
    /// @param key      the model key, which the lamp's engine keeps on the host
    /// @param progress receives what the lamp is doing, in a few words, while it starts
    /// @throws IOException when the lamp did not start; the message says why, for the user
    Lit light(Path directory, Settings settings, String key, Consumer<String> progress)
            throws IOException, InterruptedException;

    /// A lamp whose sandbox runs.
    interface Lit extends AutoCloseable {

        /// Runs a command in the sandbox, as the agent, with the agent's environment.
        Process exec(String... command) throws IOException;

        /// The sandbox desktop's VNC socket.
        Path desktop();

        /// Ends the sandbox, and returns once it is gone.
        @Override void close();
    }
}
