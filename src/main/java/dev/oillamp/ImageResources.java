package dev.oillamp;

import sprouts.Tuple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// The files the sandbox image is built from, stored inside the oillamp jar under `/image/`.
///
/// A jar cannot list its own directories, so the build writes `/image/MANIFEST`: one line
/// per file with its mode (`755` or `644`) and path. This class uses it for two things:
/// extracting the files before a build, and hashing their contents to compute the image tag. Adding
/// a file to the image needs no code change; it appears in the manifest automatically.
///
/// The mode matters: an entrypoint extracted without its executable bit makes the container die
/// at once with "permission denied".
final class ImageResources {

    private static final String ROOT = "/image/";
    private static final String MANIFEST = ROOT + "MANIFEST";

    private ImageResources() {}

    /// One file of the image: where it goes, and whether it must be executable.
    record Entry(String path, PosixMode mode) {}

    /// Every file of the image, in manifest order.
    ///
    /// @throws IllegalStateException if the manifest is missing, which means oillamp itself was
    ///         built incorrectly
    public static Tuple<Entry> entries() {
        String manifest = readText(MANIFEST).orElseThrow(() -> new IllegalStateException(
                "the sandbox image manifest is missing from this build of oillamp"));
        Tuple<Entry> entries = Tuple.of(Entry.class);
        for (String line : manifest.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            int space = trimmed.indexOf(' ');
            if (space < 0)
                throw new IllegalStateException("malformed image manifest line: '" + trimmed + "'");
            entries = entries.add(new Entry(trimmed.substring(space + 1),
                                            PosixMode.parse(trimmed.substring(0, space))));
        }
        return entries;
    }

    /// The bytes of one image file, by its manifest path.
    public static byte[] read(String path) {
        try (InputStream in = ImageResources.class.getResourceAsStream(ROOT + path)) {
            if (in == null)
                throw new IllegalStateException("the image manifest lists '" + path
                        + "', but this build of oillamp does not contain it");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// A SHA-256 hash over every image file (path, mode and contents) and every build argument.
    /// Its first 16 hex digits become the image tag.
    ///
    /// Lamps whose inputs are identical get the same tag and share one image. Changing any input,
    /// such as a package in `extra_apt_packages` or a line in the entrypoint, gives a new tag,
    /// so an outdated image is never reused. Paths are included so that moving a file counts as a
    /// change.
    public static String hashOf(java.util.SortedMap<String, String> buildArguments) {
        MessageDigest digest = sha256();
        for (Entry entry : entries()) {
            digest.update(entry.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.mode().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(read(entry.path()));
            digest.update((byte) 0);
        }
        buildArguments.forEach((name, value) -> {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '=');
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static java.util.Optional<String> readText(String resource) {
        try (InputStream in = ImageResources.class.getResourceAsStream(resource)) {
            if (in == null) return java.util.Optional.empty();
            return java.util.Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
