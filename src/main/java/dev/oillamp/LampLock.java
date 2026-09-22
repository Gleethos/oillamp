package dev.oillamp;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The exclusive claim on a lamp, held for the whole session — spec §10.4 (FR-04).
 *
 * <p>An OS file lock rather than a pid file, for one reason: the kernel releases it when the
 * process dies, however it dies. A supervisor killed with {@code kill -9}, or lost to a power
 * cut, therefore leaves no stale lock behind, and the next {@code oillamp at} on that lamp starts
 * normally instead of demanding manual cleanup (FR-08).
 *
 * <p>{@code session.json} beside it is informational only. The lock is the truth.
 *
 * <p>Deliberately <b>package-private</b>: one lamp, at most one running sandbox. On the effects
 * allowlist; the lock's stale-detection strategy is free to improve.
 */
final class LampLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private LampLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Tries to claim the lamp.
     *
     * @return the held lock, or empty if another session has it
     */
    public static Optional<LampLock> tryAcquire(Path lockFile) throws IOException {
        Path parent = lockFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new LampLock(channel, lock));
        } catch (OverlappingFileLockException e) {
            // Another thread in *this* JVM holds it - in practice, a second lamp path that
            // resolved to the same directory.
            channel.close();
            return Optional.empty();
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    @Override public void close() throws IOException {
        try {
            if (lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }
}
