package dev.oillamp;

/**
 * Whether oillamp can use {@code sudo} to install host packages. This decides whether missing
 * packages can be installed now or only reported ({@code OIL-PKG-002}). A dry run does not need
 * sudo, because it only describes what would be done.
 */
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
