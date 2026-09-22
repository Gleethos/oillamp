package dev.oillamp;

/** Whether oillamp can become root to install things — spec §11.2, {@code OIL-PKG-002}. */
sealed interface SudoFacts {
    /** {@code sudo -n true} succeeded: no prompt needed. */
    record Passwordless() implements SudoFacts {}
    /** A password is needed; usable only if stdin is a terminal that can ask for it. */
    record NeedsPassword(boolean stdinIsTerminal) implements SudoFacts {}
    /** No sudo at all. */
    record Unavailable(String reason) implements SudoFacts {}

    default boolean canInstall() {
        return switch (this) {
            case Passwordless ignored -> true;
            case NeedsPassword n      -> n.stdinIsTerminal();
            case Unavailable ignored  -> false;
        };
    }
}
