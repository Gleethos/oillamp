package dev.oillamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One screen recording on disk, as the shell found it.
 *
 * <p>Deliberately <b>package-private</b>: one {@code .mkv} and what is known about it.
 *
 * @param startedAt  when the recorder opened the file. Its creation time where the filesystem
 *                   keeps one, and otherwise the session in its name, which starts a few seconds
 *                   earlier — the sandbox has to come up before there is a screen to record.
 * @param recordedAt when the recording <em>stopped</em> — the file's modification time, because
 *                   wf-recorder writes to it until it is interrupted.
 */
record RecordingFile(Path path, Instant startedAt, Instant recordedAt, long sizeBytes) {

    public RecordingFile {
        if (sizeBytes < 0) throw new IllegalArgumentException("Negative file size: " + sizeBytes);
    }

    /** The session this recorded, when the file still carries its name. */
    public Optional<SessionId> session() {
        Path name = path.getFileName();
        return name == null ? Optional.empty()
                : SessionId.parse(name.toString().replaceFirst("\\.mkv$", ""));
    }

    /**
     * How long it ran — from when the recorder opened the file to when it last wrote to it.
     *
     * <p>Empty for a file whose timestamps disagree about the order of events. A copied or
     * restored file can arrive with a creation time after its modification time, and inventing a
     * negative duration for it would be worse than admitting the file cannot say.
     */
    public Optional<Duration> duration() {
        Duration ran = Duration.between(startedAt, recordedAt);
        return ran.isNegative() ? Optional.empty() : Optional.of(ran);
    }
}
