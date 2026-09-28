package dev.lamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// A running lamp, held by the application that started it.
///
/// ```
/// try (Lamp lamp = Lamp.at(Path.of("/home/me/campaigns/north")).onEvent(ui::show).start()) {
///     if (lamp.awaitRunning(Duration.ofMinutes(15))) { … }
/// }   // closing ends the session and waits until the sandbox is gone
/// ```
///
/// The sandbox itself is run by oillamp's engine, in a separate Java process started from the
/// same classpath as the application: `oillamp at <dir> --embedded`. This object holds that
/// process:
///
/// - its **standard output** carries every [LampEvent] as a line of JSON, which is read on a
///   thread of its own and passed to the listeners;
/// - its **standard input** stays open for as long as the application wants the session. Closing
///   it, in [#close()], ends the session. If the application dies, the operating system closes it,
///   so the sandbox never outlives the application.
///
/// Public because this is what an application uses oillamp through.
public final class Lamp implements AutoCloseable {

    /// The engine's main class. Named as text, because this package must not depend on the engine.
    static final String ENGINE = "dev.oillamp.OilLamp";

    private final Path directory;
    private final Process engine;
    private final List<Consumer<LampEvent>> listeners;
    private final CountDownLatch runningOrEnded = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile boolean running;
    private volatile Optional<ExitStatus> exit = Optional.empty();

    private Lamp(Path directory, Process engine, List<Consumer<LampEvent>> listeners) {
        this.directory = directory;
        this.engine = engine;
        this.listeners = listeners;
    }

    /// Starts describing the lamp in `directory`, which oillamp creates if it does not exist.
    public static Starting at(Path directory) {
        return new Starting(directory.toAbsolutePath(), List.of(), Lamp::sameJava);
    }

    /// Starts the engine as a separate process, and returns the command line it is started with.
    ///
    /// Given a replacement with [Starting#launchedBy], for tests, or for an application that
    /// runs the engine some other way.
    @FunctionalInterface
    public interface Launcher {
        Process launch(List<String> arguments) throws IOException;
    }

    /// A lamp that has been described but not started yet.
    public static final class Starting {

        private final Path directory;
        private final List<Consumer<LampEvent>> listeners;
        private final Launcher launcher;

        private Starting(Path directory, List<Consumer<LampEvent>> listeners, Launcher launcher) {
            this.directory = directory;
            this.listeners = listeners;
            this.launcher = launcher;
        }

        /// Receives every event the engine reports, in order, from the moment it starts.
        ///
        /// Called on one thread that reads the engine's output. A listener that takes long holds
        /// up the ones after it, so a Swing application hands the event over to its event thread.
        public Starting onEvent(Consumer<LampEvent> listener) {
            List<Consumer<LampEvent>> more = new ArrayList<>(listeners);
            more.add(listener);
            return new Starting(directory, List.copyOf(more), launcher);
        }

        public Starting launchedBy(Launcher launcher) {
            return new Starting(directory, listeners, launcher);
        }

        /// Starts the engine, and returns at once. The sandbox is running when
        /// [Lamp#awaitRunning] says so; building its image the first time takes several minutes.
        ///
        /// @throws IOException when the engine's process could not be started at all
        public Lamp start() throws IOException {
            Process engine = launcher.launch(List.of("at", directory.toString(), "--embedded"));
            Lamp lamp = new Lamp(directory, engine, listeners);
            Thread.ofVirtual().name("lamp-events-" + directory.getFileName()).start(lamp::readEvents);
            return lamp;
        }
    }

    /// The lamp directory.
    public Path directory() { return directory; }

    /// Waits until the session is running, the engine has ended, or `limit` has passed.
    ///
    /// @return true once the session is running. False if it ended without getting there, in
    ///         which case the events said why, or if it is still starting after `limit`
    public boolean awaitRunning(Duration limit) throws InterruptedException {
        runningOrEnded.await(limit.toMillis(), TimeUnit.MILLISECONDS);
        return running && exit.isEmpty();
    }

    /// How the engine ended, once it has.
    public Optional<ExitStatus> exitStatus() { return exit; }

    /// Ends the session and waits until the engine has shut the sandbox down.
    ///
    /// Closing the engine's standard input is the signal. The engine then stops the container,
    /// which takes up to `timeouts.stop_seconds`, and exits.
    ///
    /// Interrupting the waiting thread stops the wait, not the shutdown.
    @Override public void close() {
        try {
            engine.getOutputStream().close();
        } catch (IOException alreadyGone) {
            // The pipe could not be closed because the engine is no longer reading it.
        }
        try {
            ended.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void readEvents() {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null)
                LampEvent.fromJson(line).ifPresent(this::deliver);
        } catch (IOException ended) {
            // The engine's output closed; it has exited, or is about to.
        }
        int code;
        try {
            code = engine.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        exit = Optional.of(ExitStatus.ofCode(code).orElse(ExitStatus.ERROR));
        runningOrEnded.countDown();
        ended.countDown();
    }

    private void deliver(LampEvent event) {
        if (event instanceof LampEvent.SessionStateChanged changed
                && changed.status().state().equals("running")) {
            running = true;
            runningOrEnded.countDown();
        }
        for (Consumer<LampEvent> listener : listeners) listener.accept(event);
    }

    /// Starts the engine with this process's own Java runtime and classpath, so that the
    /// application and the engine are always the same version of oillamp. The engine's error
    /// output goes where the application's does.
    private static Process sameJava(List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ENGINE));
        command.addAll(arguments);
        return new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }
}
