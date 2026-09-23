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
 * The exclusive lock on a lamp, held by the supervisor for the whole session, so that only one
 * session can run on a lamp at a time.
 *
 * <p>It is an operating-system file lock on {@code .oillamp/lock}, not a file containing a process
 * id, because the kernel releases the lock when the process dies, however it dies. After a
 * {@code kill -9} or a power cut, the next {@code oillamp at} simply starts.
 *
 * <p>{@code session.json} beside it is only informational. The lock decides whether a session is
 * running.
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
