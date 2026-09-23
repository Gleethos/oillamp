package dev.oillamp;

import java.nio.file.Path;

/// A terminal emulator found on `PATH`, and which entry of the terminal table it matches.
record TerminalCandidate(TerminalProfileId id, Path executable) {}
