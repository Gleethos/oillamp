package dev.gui.pi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// A running pi, in RPC mode, as a genie's harness.
///
/// Holds pi's process, which in Genies is an ssh command into the genie's sandbox: commands go to
/// its standard input, and a thread of its own reads the events from its standard output and
/// hands them on. When pi ends, the last lines of its error output say why.
public final class PiSession implements AutoCloseable {

    /// How many of pi's last error lines are kept, to say why it ended.
    private static final int ERROR_LINES_KEPT = 12;

    private final Process pi;
    private final OutputStream commands;
    private final Deque<String> errors = new ArrayDeque<>();
    private volatile boolean closing;
    private final Thread errorReader;

    private PiSession(Process pi) {
        this.pi = pi;
        this.commands = pi.getOutputStream();
        this.errorReader = Thread.ofVirtual().name("pi errors").unstarted(this::keepErrors);
    }

    /// Starts listening to `pi`.
    ///
    /// @param events receives every event the chat shows, in order, on the session's own thread
    /// @param ended  called once when pi has ended: with why, unless the session was closed
    public static PiSession over(Process pi, Consumer<PiEvent> events, Consumer<Optional<String>> ended) {
        PiSession session = new PiSession(pi);
        session.errorReader.start();
        Thread.ofVirtual().name("pi events").start(() -> session.read(events, ended));
        return session;
    }

    /// Sends one command, as [PiProtocol] writes them. A command sent after pi ended is dropped;
    /// the `ended` callback has said so already, or is about to.
    public void send(String command) {
        try {
            synchronized (commands) {
                commands.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                commands.flush();
            }
        } catch (IOException gone) {
            // pi is gone; reading its output ends and says why.
        }
    }

    public boolean isAlive() { return pi.isAlive(); }

    /// Ends pi the way it asks to be ended: by closing its input. It is stopped if it has not
    /// ended within a few seconds.
    @Override public void close() {
        closing = true;
        try {
            commands.close();
        } catch (IOException alreadyGone) {
            // Ended already.
        }
        try {
            if (!pi.waitFor(5, TimeUnit.SECONDS)) pi.destroy();
        } catch (InterruptedException e) {
            pi.destroy();
            Thread.currentThread().interrupt();
        }
    }

    private void read(Consumer<PiEvent> events, Consumer<Optional<String>> ended) {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(pi.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null)
                PiProtocol.read(line).ifPresent(events);
        } catch (IOException gone) {
            // The pipe broke: pi ended.
        }
        int code;
        try {
            code = pi.waitFor();
            errorReader.join(2_000);   // so that its last words are in
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        ended.accept(closing ? Optional.empty() : Optional.of(why(code)));
    }

    private String why(int code) {
        String said;
        synchronized (errors) {
            said = String.join("\n", errors).strip();
        }
        return "The genie's harness stopped (exit " + code + ")" + (said.isEmpty() ? "." : ": " + said);
    }

    private void keepErrors() {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(pi.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                if (line.isBlank()) continue;
                synchronized (errors) {
                    errors.addLast(line);
                    if (errors.size() > ERROR_LINES_KEPT) errors.removeFirst();
                }
            }
        } catch (IOException gone) {
            // pi ended.
        }
    }
}
