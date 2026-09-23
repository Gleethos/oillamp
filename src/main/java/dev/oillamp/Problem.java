package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import sprouts.Tuple;

/**
 * Something that went wrong, described well enough to act on.
 *
 * <p>oillamp never shows the user a bare stack trace. Every failure is one of these: <em>what</em>
 * happened, <em>why it matters</em>, the <em>evidence</em> (commands and their output, files,
 * values, configuration locations) and concrete <em>fixes</em>. The wording for each code lives in
 * {@link Problems}.
 *
 * <p>Public because callers, including tests and a future GUI, need to inspect failures rather than
 * parse text.
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
     * A stable identifier of the form {@code OIL-<AREA>-<NNN>}. Users quote it in bug reports and
     * tests match on it, so a code must never change meaning. The codes are listed in
     * {@link Problems} and in {@code docs/ARCHITECTURE.md}.
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
        /** A configuration mistake: the file, the key path, the value found and what was expected. */
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
