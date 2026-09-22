package dev.oillamp;

/**
 * The process exit codes oillamp promises — spec §27.5.
 *
 * <p>Scripts and CI wrap this tool, so the codes are a contract: {@code 4} always means
 * "that lamp is already running", never "some other error".
 */
public enum ExitStatus {
    /** Success, or a session that ended cleanly. */
    SUCCESS(0),
    /** Any {@code ERROR} problem not covered by a more specific code below. */
    ERROR(1),
    /** The command line or the configuration was wrong. */
    USAGE(2),
    /** Host prerequisites are missing and oillamp was not allowed to install them. */
    PREREQUISITES_MISSING(3),
    /** Another session already holds this lamp — {@code OIL-LOCK-001}. */
    LAMP_BUSY(4),
    /** The container or the session itself failed. */
    SESSION_FAILED(5),
    /** The user interrupted before the session was running. */
    INTERRUPTED(130);

    private final int code;

    ExitStatus(int code) { this.code = code; }

    public int code() { return code; }

    public boolean isSuccess() { return this == SUCCESS; }
}
