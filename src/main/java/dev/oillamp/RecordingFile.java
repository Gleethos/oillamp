package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/// One screen recording file (`.mkv`) and what its timestamps say.
///
/// @param startedAt  when recording began: the file's creation time where the filesystem records
///                   one, otherwise the session start encoded in the file name (a few seconds
///                   earlier, because the sandbox starts before the recorder does)
/// @param recordedAt when recording stopped: the file's last modification time, because
///                   wf-recorder writes to the file until it is stopped
record RecordingFile(Path path, Instant startedAt, Instant recordedAt, long sizeBytes) {

    public RecordingFile {
        if (sizeBytes < 0) throw new IllegalArgumentException("Negative file size: " + sizeBytes);
    }

    /// The session this recorded, when the file still carries its name.
    public Optional<SessionId> session() {
        Path name = path.getFileName();
        return name == null ? Optional.empty()
                : SessionId.parse(name.toString().replaceFirst("\\.mkv$", ""));
    }

    /// How long the recording ran.
    ///
    /// Empty when the start is after the end, which happens with a copied or restored file.
    public Optional<Duration> duration() {
        Duration ran = Duration.between(startedAt, recordedAt);
        return ran.isNegative() ? Optional.empty() : Optional.of(ran);
    }
}
