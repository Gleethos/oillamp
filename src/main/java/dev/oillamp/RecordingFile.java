package dev.oillamp;

import java.nio.file.Path;
import java.time.Instant;

/** One screen recording on disk, as the shell found it. */
record RecordingFile(Path path, Instant recordedAt, long sizeBytes) {

    public RecordingFile {
        if (sizeBytes < 0) throw new IllegalArgumentException("Negative file size: " + sizeBytes);
    }
}
