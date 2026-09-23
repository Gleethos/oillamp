package dev.oillamp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import sprouts.Tuple;

/**
 * File operations on the lamp directory.
 *
 * <p>These use the real filesystem even in tests, unlike the rest of oillamp's effects, which go
 * through {@link Machine}. The lamp's isolation depends on real permission bits, ownership and
 * symlinks, and a simulated filesystem could hide bugs in exactly those.
 *
 * <p>Files are written atomically: to a temporary file in the same directory, then renamed. An
 * interrupted run cannot leave a half-written {@code lamp.json} or {@code runtime.env} behind.
 */
final class Filesystem {

    private Filesystem() {}

    public static void createDirectory(Path path, PosixMode mode) throws IOException {
        if (Files.isDirectory(path)) {
            setMode(path, mode);
            return;
        }
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(mode.permissions()));
    }

    /** Writes atomically, then applies the mode — never leaves a partially written file. */
    public static void writeFile(Path path, String content, PosixMode mode) throws IOException {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8), mode);
    }

    /**
     * The same for binary content, such as the wallpaper PNG in the image files. Writing it as text
     * would corrupt it.
     */
    public static void writeBytes(Path path, byte[] content, PosixMode mode) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent == null ? path : parent, ".oillamp-", ".tmp");
        try {
            Files.write(temporary, content);
            setMode(temporary, mode);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Creates a directory and every missing parent, applying the mode to those it creates. */
    public static void createDirectories(Path path, PosixMode mode) throws IOException {
        if (Files.isDirectory(path)) return;
        Path parent = path.getParent();
        if (parent != null) createDirectories(parent, mode);
        if (!Files.isDirectory(path))
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(mode.permissions()));
    }

    public static void copyFile(Path from, Path to, PosixMode mode) throws IOException {
        Path parent = to.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        setMode(to, mode);
    }

    /**
     * Points {@code link} at {@code target}, replacing anything else at that path, such as a link
     * left by a crashed session that points somewhere else.
     */
    public static void createSymlink(Path link, Path target) throws IOException {
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(target)) return;
            Files.delete(link);
        } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(link);
        }
        Path parent = link.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createSymbolicLink(link, target);
    }

    public static void setMode(Path path, PosixMode mode) throws IOException {
        Files.setPosixFilePermissions(path, mode.permissions());
    }

    public static boolean exists(Path path) { return Files.exists(path); }

    public static Optional<String> readString(Path path) {
        try {
            return Files.isReadable(path) ? Optional.of(Files.readString(path)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static void deleteIfPresent(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /**
     * Deletes a tree as far as this user is allowed to, and reports what survived.
     *
     * <p>It does not stop at the first refusal, so the caller can tell the user which paths could
     * not be deleted (usually files owned by the infra user).
     *
     * <p>Symlinks are deleted, never followed. The runtime directory holds a link into the lamp, and
     * following it would delete the lamp's contents.
     */
    public static Tuple<Path> deleteTree(Path root) {
        Tuple<Path> survivors = Tuple.of(Path.class);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return survivors;
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> children = Files.list(root)) {
                for (Path child : children.toList()) survivors = survivors.addAll(deleteTree(child));
            } catch (IOException unreadable) {
                return survivors.add(root);
            }
        }
        try {
            Files.deleteIfExists(root);
        } catch (IOException refused) {
            survivors = survivors.add(root);
        }
        return survivors;
    }

    /**
     * Every {@code .mkv} file in a recordings directory, sorted by name (which is by time).
     *
     * <p>The file is created when recording starts and written until it stops, so its creation and
     * modification times give its duration without opening it or needing {@code ffprobe}.
     *
     * @return the recordings; empty when the directory does not exist or cannot be read
     */
    public static Tuple<RecordingFile> listRecordings(Path directory) {
        Tuple<RecordingFile> found = Tuple.of(RecordingFile.class);
        if (!Files.isDirectory(directory)) return found;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                if (!entry.toString().endsWith(".mkv")) continue;
                BasicFileAttributes attributes =
                        Files.readAttributes(entry, BasicFileAttributes.class);
                found = found.add(new RecordingFile(entry, startOf(entry, attributes),
                        attributes.lastModifiedTime().toInstant(), attributes.size()));
            }
        } catch (IOException e) {
            return found;
        }
        return found;
    }

    /**
     * When the recording began: the file's creation time where the filesystem keeps one. Where it
     * does not, Java returns the modification time instead, which would make every recording zero
     * seconds long, so the session start in the file name is used. That is a few seconds early,
     * because the sandbox starts before the recorder.
     */
    private static Instant startOf(Path file, BasicFileAttributes attributes) {
        Instant created = attributes.creationTime().toInstant();
        if (created.isBefore(attributes.lastModifiedTime().toInstant())) return created;
        Path name = file.getFileName();
        return name == null ? created
                : SessionId.parse(name.toString().replaceFirst("\\.mkv$", ""))
                           .map(SessionId::startedAt)
                           .orElse(created);
    }

    /** Lists a directory for {@link LampClassifier}. */
    public static DirListing list(Path path) {
        if (!Files.exists(path)) return DirListing.missing();
        if (!Files.isDirectory(path) || !Files.isReadable(path))
            return new DirListing(true, false, Tuple.of(String.class));
        try (Stream<Path> entries = Files.list(path)) {
            Tuple<String> names = Tuple.of(String.class);
            for (Path entry : entries.sorted().toList()) {
                Path fileName = entry.getFileName();
                if (fileName != null) names = names.add(fileName.toString());
            }
            return new DirListing(true, true, names);
        } catch (IOException e) {
            return new DirListing(true, false, Tuple.of(String.class));
        }
    }
}
