package dev.gui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import dev.gui.model.Trouble;

/// Where Genies keeps what went wrong that it did not expect: a bug of its own, or a library
/// failing. It is the handler of every exception no code caught, on any thread, Swing's included.
///
/// Each one is written, with its stack trace, to `errors.log` in Genies' data folder, and to the
/// error output, and handed to the window, which shows it until the user has looked.
///
/// The same exception again and again, such as one thrown each time something is painted, is
/// written in full once, then as one line each time, and shown once.
public final class ErrorLog implements Thread.UncaughtExceptionHandler {

    /// Above this size the log is moved to `errors.log.1`, replacing the one there, and begun again.
    public static final long MOST_BYTES = 1_000_000;

    private final Path file;
    /// The window, once there is one. What went wrong before is only in the log.
    private volatile Consumer<Trouble> shown = trouble -> { };
    /// The kind and place of the last exception, to know it when it comes again.
    private Optional<String> last = Optional.empty();

    public ErrorLog(Path file) {
        this.file = file;
    }

    public Path file() { return file; }

    /// From now on, each new trouble also goes to `window`, on whichever thread it happened.
    public void showIn(Consumer<Trouble> window) {
        shown = window;
    }

    @Override public void uncaughtException(Thread thread, Throwable failed) {
        record("thread " + (thread.getName().isEmpty() ? thread.toString() : thread.getName()), failed);
    }

    /// Keeps `failed`, which happened in `where`. Never throws: where the log cannot be written,
    /// the error output still has it.
    public synchronized void record(String where, Throwable failed) {
        Instant now = Instant.now();
        String what = failed.toString().replaceAll("\\s+", " ");
        StackTraceElement[] stack = failed.getStackTrace();
        String kind = what + (stack.length == 0 ? "" : " at " + stack[0]);
        if (last.filter(kind::equals).isPresent()) {
            write(now + "  again, in " + where + ": " + what + "\n");
            return;
        }
        last = Optional.of(kind);
        StringWriter trace = new StringWriter();
        failed.printStackTrace(new PrintWriter(trace));
        String details = now + "  in " + where + "\n" + trace;
        write(details + "\n");
        try {
            shown.accept(new Trouble(now, where, what, details));
        } catch (RuntimeException notShown) {
            write(now + "  could not be shown: " + notShown + "\n");
        }
    }

    private void write(String text) {
        System.err.print("genies: " + text);
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MOST_BYTES)
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException unwritten) {
            System.err.println("genies: could not write to " + file + ": " + unwritten);
        }
    }
}
