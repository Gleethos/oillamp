package dev.oillamp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import sprouts.Pair;
import sprouts.Tuple;

/**
 * The {@link Machine} that really is this computer — spec §26.1/§26.2.
 *
 * <p>Two rules hold for every command run here:
 * <ul>
 *   <li><b>Never a shell.</b> The argv is passed straight to {@link ProcessBuilder}, so nothing a
 *       configuration value contains can become a second command.</li>
 *   <li><b>Always a timeout.</b> On expiry the process <em>and its descendants</em> are destroyed,
 *       because a hung {@code podman} that left children behind would otherwise outlive oillamp
 *       and keep the lamp locked (NFR-02).</li>
 * </ul>
 *
 * <p>Deliberately <b>package-private</b>: reached only through {@link Machine#real()}. Nobody
 * outside can name the real implementation, and that is exactly what keeps the seam a seam.
 */
final class RealMachine implements Machine {

    /** Output beyond this is dropped, keeping the tail; a runaway build must not exhaust memory. */
    private static final int MAX_CAPTURED_BYTES = 4 * 1024 * 1024;

    private final SecureRandom random = new SecureRandom();

    @Override public Instant now() { return Instant.now(); }

    @Override public String operatingSystemName() { return System.getProperty("os.name", "unknown"); }

    @Override public Optional<String> environmentVariable(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isEmpty());
    }

    @Override public String randomToken(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            out.append(AgentId.ALPHABET.charAt(random.nextInt(AgentId.ALPHABET.length())));
        return out.toString();
    }

    @Override public boolean isInteractive() {
        // Since JDK 22 a Console is always returned even when redirected, so the null check of
        // older code is not enough - ask the console whether it really is a terminal.
        return Optional.ofNullable(System.console()).filter(java.io.Console::isTerminal).isPresent();
    }

    @Override public Outcome run(Command command) {
        ProcessBuilder builder = new ProcessBuilder(shield(command));
        for (Pair<String, String> variable : command.environment())
            builder.environment().put(variable.first(), variable.second());
        command.workingDirectory().ifPresent(directory -> builder.directory(directory.toFile()));

        Instant started = Instant.now();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Outcome.NotFound(command.executable());
        }

        // Drain both pipes on their own virtual threads: a process that fills its stderr buffer
        // while we wait on stdout would deadlock.
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outReader = drain(process.getInputStream(), out);
        Thread errReader = drain(process.getErrorStream(), err);

        try {
            boolean finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyTree(process);
                outReader.join(Duration.ofSeconds(1));
                errReader.join(Duration.ofSeconds(1));
                return new Outcome.TimedOut(command.timeout(), out.toString());
            }
            outReader.join(Duration.ofSeconds(5));
            errReader.join(Duration.ofSeconds(5));
            return new Outcome.Finished(process.exitValue(), out.toString(), err.toString(),
                                        Duration.between(started, Instant.now()));
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            return new Outcome.TimedOut(Duration.between(started, Instant.now()), out.toString());
        }
    }

    /**
     * The argv to actually run, with a shielded command wrapped in {@code setsid}.
     *
     * <p>{@code --wait} is not optional: plain {@code setsid} forks when it is already a process
     * group leader and returns 0 immediately, which would report every shutdown command as having
     * succeeded whatever it did. With it, setsid waits and passes the real exit status back.
     *
     * <p>If {@code setsid} is somehow missing the command still runs, just unshielded — losing the
     * ability to stop a container because a util-linux binary is absent would be the worse trade.
     */
    private List<String> shield(Command command) {
        List<String> argv = asList(command.argv());
        if (!command.shielded()) return argv;
        Optional<Path> setsid = locateExecutable("setsid");
        if (setsid.isEmpty()) return argv;
        List<String> shielded = new ArrayList<>(argv.size() + 2);
        shielded.add(setsid.get().toString());
        shielded.add("--wait");
        shielded.addAll(argv);
        return shielded;
    }

    /**
     * Starts a window and leaves it running.
     *
     * <p>Two differences from {@link #run} matter. There is no timeout, because the thing being
     * started is a window the user will close when they are done with it. And its output is kept
     * in memory rather than discarded, because the one moment a viewer's output is worth having
     * is when the window is gone a second after it opened — which is exactly when nobody was
     * looking at it.
     */
    @Override public Window launch(Command command, Window.Stdio stdio) {
        ProcessBuilder builder = new ProcessBuilder(asList(command.argv()));
        for (Pair<String, String> variable : command.environment())
            builder.environment().put(variable.first(), variable.second());
        command.workingDirectory().ifPresent(directory -> builder.directory(directory.toFile()));
        if (stdio == Window.Stdio.TERMINAL) {
            builder.inheritIO();
        } else {
            // Nothing may be read from the user's terminal by a window running beside it: a
            // detached process that inherited stdin would compete with oillamp for every
            // keystroke, and the one that lost would be whichever the user was actually typing at.
            builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return Window.refused(command.executable(), reasonFor(e));
        }
        return new ProcessWindow(process, stdio);
    }

    private static String reasonFor(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** A started process, seen through the four questions a window has to answer. */
    private static final class ProcessWindow implements Window {

        private final Process process;
        private final StringBuilder said = new StringBuilder();

        private ProcessWindow(Process process, Stdio stdio) {
            this.process = process;
            if (stdio == Stdio.DETACHED) {
                drain(process.getInputStream(), said);
                drain(process.getErrorStream(), said);
            }
        }

        @Override public long pid() { return process.pid(); }

        @Override public boolean isRunning() { return process.isAlive(); }

        @Override public Optional<Integer> exitCode() {
            return process.isAlive() ? Optional.empty() : Optional.of(process.exitValue());
        }

        @Override public Optional<String> failure() { return Optional.empty(); }

        @Override public String output() { return said.toString().strip(); }

        @Override public int waitFor() {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyTree(process);
                return 130;
            }
        }

        /**
         * Asks politely, then insists. A terminal emulator that ignores SIGTERM would otherwise
         * keep a session's window on screen after the session it belonged to has ended.
         */
        @Override public void close() {
            if (!process.isAlive()) return;
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) destroyTree(process);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyTree(process);
            }
        }
    }

    @Override public Optional<String> readSystemFile(Path path) {
        try {
            return Files.isReadable(path) ? Optional.of(Files.readString(path)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override public Tuple<Path> listSystemDirectory(Path path) {
        if (!Files.isDirectory(path)) return Tuple.of(Path.class);
        try (Stream<Path> entries = Files.list(path)) {
            Tuple<Path> out = Tuple.of(Path.class);
            for (Path entry : entries.sorted().toList()) out = out.add(entry);
            return out;
        } catch (IOException e) {
            return Tuple.of(Path.class);
        }
    }

    @Override public Optional<Path> locateExecutable(String name) {
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null) return Optional.empty();
        for (String directory : pathVariable.split(":", -1)) {
            if (directory.isEmpty()) continue;
            Path candidate = Path.of(directory, name);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate))
                return Optional.of(candidate);
        }
        return Optional.empty();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private static Thread drain(InputStream stream, StringBuilder sink) {
        return Thread.ofVirtual().start(() -> {
            try (stream) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (sink.length() < MAX_CAPTURED_BYTES)
                        sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Kills descendants first, so a child cannot be re-parented and survive. */
    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static java.util.List<String> asList(Tuple<String> argv) {
        java.util.List<String> out = new java.util.ArrayList<>(argv.size());
        for (String value : argv) out.add(value);
        return out;
    }
}
