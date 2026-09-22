package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import sprouts.Tuple;

/**
 * A structured, diagnosable failure — spec §27.2 / NFR-03.
 *
 * <p>oillamp never reports a bare stack trace to the user. Everything that can go wrong is
 * a value of this type: <em>what</em> failed, <em>why that matters</em>, the <em>evidence</em>
 * (command lines, exit codes, file paths, config key paths), concrete <em>fixes</em>, and where
 * the full log is. That makes failures renderable (console, log, future GUI) and assertable.
 *
 * <p>This is part of the public API: it is what a caller — a terminal user, a test, or the
 * planned Swing front end — actually observes when something goes wrong.
 *
 * <p>Deliberately <b>public</b>: NFR-03 requires failures to be structured rather than prose —
 * which only helps a caller that is allowed to inspect the structure instead of re-parsing English.
 */
public record Problem(
    Code code,
    Severity severity,
    String title,
    String whatHappened,
    String whyItMatters,
    Tuple<Evidence> evidence,
    Tuple<Fix> fixes,
    Optional<Path> logFile
) {
    public Problem {
        if (title.isBlank())
            throw new IllegalArgumentException("A problem needs a title");
    }

    /** How bad it is. Only {@link #ERROR} aborts what oillamp was doing. */
    public enum Severity { INFO, WARNING, ERROR }

    /**
     * A stable identifier from the catalog in spec §27.3, of the form {@code OIL-<AREA>-<NNN>}.
     * Users quote it in bug reports and tests assert on it, so it must never change meaning.
     */
    public record Code(String value) {
        public Code {
            if (!value.matches("OIL-[A-Z]+-\\d{3}"))
                throw new IllegalArgumentException("Not a problem code: " + value);
        }
        @Override public String toString() { return value; }
    }

    /** Concrete proof of what oillamp observed, so the user need not reproduce it. */
    public sealed interface Evidence {
        /** A command oillamp ran, and how it went. */
        record Command(Tuple<String> argv, int exitCode, String stderrTail, Duration took) implements Evidence {}
        /** A path that is relevant to the failure. */
        record File(Path path, String note) implements Evidence {}
        /** A single named value oillamp read (an environment variable, a version, a sysctl). */
        record Value(String name, String value) implements Evidence {}
        /** A block of text, e.g. the tail of a container log. */
        record Excerpt(String title, String text) implements Evidence {}
        /** A configuration mistake, located precisely — spec FR-52. */
        record Config(Path file, String keyPath, String value, String expected) implements Evidence {}
    }

    /** Something the user can do about it. The command, if present, is copy-pasteable. */
    public record Fix(String description, Optional<String> command) {
        public static Fix of(String description) {
            return new Fix(description, Optional.empty());
        }
        public static Fix run(String description, String command) {
            return new Fix(description, Optional.of(command));
        }
    }

    public boolean isError()   { return severity == Severity.ERROR; }
    public boolean isWarning() { return severity == Severity.WARNING; }

    public Problem withEvidence(Evidence more) {
        return new Problem(code, severity, title, whatHappened, whyItMatters,
                           evidence.add(more), fixes, logFile);
    }

    public Problem withFix(Fix more) {
        return new Problem(code, severity, title, whatHappened, whyItMatters,
                           evidence, fixes.add(more), logFile);
    }

    public Problem inLog(Path log) {
        return new Problem(code, severity, title, whatHappened, whyItMatters,
                           evidence, fixes, Optional.of(log));
    }
}
