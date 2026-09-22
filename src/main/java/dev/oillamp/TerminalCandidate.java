package dev.oillamp;

import java.nio.file.Path;

/** A terminal emulator found on PATH, with the profile that knows how to drive it (D-22). */
record TerminalCandidate(TerminalProfileId id, Path executable) {}
