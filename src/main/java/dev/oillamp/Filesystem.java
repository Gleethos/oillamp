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
 * Filesystem operations for the lamp directory — spec §26.2.
 *
 * <p>Unlike the rest of the host adapters, these run against the real filesystem even in tests.
 * The lamp's isolation story is made of permission bits, ownership and symlinks (§9.2); a
 * simulated filesystem that approximated those would let a bug in exactly the place it matters most.
 *
 * <p>Writes are atomic — a temporary file in the same directory, then an atomic rename — so an
 * interrupted run can never leave a half-written {@code lamp.json} or {@code runtime.env} behind
 * for the next run to misread (NFR-02).
 *
 * <p>Deliberately <b>package-private</b>: the POSIX operations the layout needs — modes, ownership,
 * symlinks. It is on the effects allowlist, and nothing outside should be able to invoke them at
 * all.
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
     * The same, for content that is not text.
     *
     * <p>Needed because the sandbox image carries a PNG. Writing that through the text path would
     * put it through a charset encoder and corrupt it, and the symptom would be a desktop with no
     * wallpaper rather than anything that mentions encoding.
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

    /**
     * Deletes a tree as far as this user is allowed to, and reports what survived.
     *
     * <p>Deliberately does not throw on the first refusal. Part of a lamp belongs to a container
     * uid, and stopping at the first {@code Permission denied} would leave the caller unable to
     * say <em>which</em> paths need {@code podman unshare} — which is the only useful thing to
     * tell a user in that situation.
     *
     * <p>Symlinks are deleted, never followed: the runtime directory holds one pointing back into
     * the lamp (D-25), and following it would delete the target's contents through the link.
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
     * Every {@code .mkv} in a recordings directory, oldest first.
     *
     * <p>Two ends of one life, and the gap between them is the point: the file is created when
     * wf-recorder opens it and written to until it is interrupted, so its own timestamps say how
     * long it ran. Taking both here is what lets a listing show a duration without opening the
     * file or shelling out to ffprobe — neither of which oillamp requires of the host.
     *
     * @return what is there; empty when the directory does not exist or cannot be read, because
     *         "no recordings" and "not readable" lead to the same, harmless, listing
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
     * When the recording began, from the best source this filesystem offers.
     *
     * <p>Creation time is the truthful answer, but not every filesystem keeps one: where it does
     * not, the JDK hands back the modification time, which would make every recording zero
     * seconds long. The session in the name is the fallback — a few seconds early, because the
     * sandbox has to start before there is a screen to record, but never wrong by more than that.
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
