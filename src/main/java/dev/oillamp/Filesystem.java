package dev.oillamp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.stream.Stream;

import sprouts.Tuple;

/**
 * Filesystem operations for the lamp directory — spec §26.2.
 *
 * <p>Unlike the rest of the host adapters, these run against the real filesystem even in tests.
 * The lamp's isolation story is made of permission bits, ownership and symlinks (§9.2); a
 * simulated filesystem that approximated those would let a bug in exactly the place it matters most.
 *
 * <p>Writes are atomic — a temporary file in the same directory, then an atomic rename — so an
 * interrupted run can never leave a half-written {@code lamp.json} or {@code runtime.env} behind
 * for the next run to misread (NFR-02).
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
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent == null ? path : parent, ".oillamp-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            setMode(temporary, mode);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void copyFile(Path from, Path to, PosixMode mode) throws IOException {
        Path parent = to.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        setMode(to, mode);
    }

    /**
     * Points {@code link} at {@code target}, replacing a link that points elsewhere.
     * The short runtime directory is recreated per session, so a stale link from a crashed
     * session must not survive into the next one (D-25).
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

    /** Looks at a candidate lamp directory, producing the value {@code LampClassifier} decides on. */
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
