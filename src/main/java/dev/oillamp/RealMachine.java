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

/// The [Machine] that really is this computer. Obtained with [Machine#real()].
///
/// Two rules hold for every command run here:
/// - **Never a shell.** The argument list goes straight to [ProcessBuilder], so nothing
///   inside a configuration value can become a second command.
/// - **Always a timeout.** When it expires, the process and all its child processes are
///   killed, so a hung `podman` cannot leave processes behind.
final class RealMachine implements Machine {

    /// Output beyond this many bytes is dropped (the start is kept), so a very noisy build cannot exhaust memory.
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
        // Since JDK 22, System.console() returns a Console even when output is redirected, so
        // ask whether it really is a terminal.
        return Optional.ofNullable(System.console()).filter(java.io.Console::isTerminal).isPresent();
    }

    @Override public Outcome run(Command command) {
        return run(command, line -> { });
    }

    @Override public Outcome run(Command command, java.util.function.Consumer<String> eachLine) {
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
        Thread outReader = drain(process.getInputStream(), out, eachLine);
        Thread errReader = drain(process.getErrorStream(), err, eachLine);

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

    /// The argv to actually run, with a shielded command wrapped in `setsid`.
    ///
    /// `--wait` is required: without it, `setsid` may fork and return 0 at once, so
    /// every shutdown command would appear to succeed. With it, setsid waits and returns the
    /// command's real exit code.
    ///
    /// If `setsid` is missing, the command runs unshielded rather than not at all.
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

    /// Starts a window and leaves it running.
    ///
    /// Unlike [#run], there is no timeout: the user closes the window when they are done.
    /// The window's output is kept in memory, because when a window closes straight after opening,
    /// its output is the only explanation.
    @Override public Window launch(Command command, Window.Stdio stdio) {
        ProcessBuilder builder = new ProcessBuilder(asList(command.argv()));
        for (Pair<String, String> variable : command.environment())
            builder.environment().put(variable.first(), variable.second());
        command.workingDirectory().ifPresent(directory -> builder.directory(directory.toFile()));
        if (stdio == Window.Stdio.TERMINAL) {
            builder.inheritIO();
        } else {
            // A window must not read from the user's terminal, or it would compete with oillamp
            // for keystrokes.
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

    /// A started process, as a [Machine.Window].
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

        /// Sends SIGTERM, then kills the process if it is still there after two seconds, so a
        /// window does not stay open after its session ended.
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
        return drain(stream, sink, line -> { });
    }

    /// Reads a process's output into `sink`, and passes each complete line to
    /// `eachLine` as it arrives. A carriage return also ends a line, because progress output
    /// often rewrites one line with `\r`.
    private static Thread drain(InputStream stream, StringBuilder sink,
                                java.util.function.Consumer<String> eachLine) {
        return Thread.ofVirtual().start(() -> {
            StringBuilder line = new StringBuilder();
            try (java.io.Reader reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    synchronized (sink) {
                        if (sink.length() < MAX_CAPTURED_BYTES) sink.append(buffer, 0, read);
                    }
                    for (int i = 0; i < read; i++) {
                        char c = buffer[i];
                        if (c == '\n' || c == '\r') {
                            if (!line.toString().isBlank()) eachLine.accept(line.toString());
                            line.setLength(0);
                        } else {
                            line.append(c);
                        }
                    }
                }
                if (!line.toString().isBlank()) eachLine.accept(line.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /// Kills the child processes first, so none can survive by being re-parented.
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
