package dev.lamp;

import java.util.Optional;

/// The process exit codes. Scripts depend on them, so a code's meaning never changes: `4`
/// always means "that lamp is already running".
///
/// Public so that a caller can act on how the process ended by name rather than by number.
public enum ExitStatus {
    /// Success, or a session that ended cleanly.
    SUCCESS(0),
    /// Any `ERROR` problem not covered by a more specific code below.
    ERROR(1),
    /// The command line or the configuration was wrong.
    USAGE(2),
    /// Host prerequisites are missing and oillamp was not allowed to install them.
    PREREQUISITES_MISSING(3),
    /// Another session already holds this lamp — `OIL-LOCK-001`.
    LAMP_BUSY(4),
    /// The container or the session itself failed.
    SESSION_FAILED(5),
    /// The user interrupted before the session was running.
    INTERRUPTED(130);

    private final int code;

    ExitStatus(int code) { this.code = code; }

    public int code() { return code; }

    /// The status an oillamp process meant by exiting with `code`, or empty for a code oillamp
    /// never uses, such as a Java runtime that could not start.
    public static Optional<ExitStatus> ofCode(int code) {
        for (ExitStatus status : values())
            if (status.code == code) return Optional.of(status);
        return Optional.empty();
    }

    public boolean isSuccess() { return this == SUCCESS; }
}
