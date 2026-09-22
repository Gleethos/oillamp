package dev.oillamp;

import sprouts.Tuple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The sandbox image's own files, as they are carried inside the oillamp jar — spec §12.3, §15.2.
 *
 * <p>Two jobs, and they are the same job seen twice. Extracting the build context needs to know
 * which files exist; deciding whether the image must be rebuilt needs to know what is <em>in</em>
 * them. Both read the generated {@code image/MANIFEST}, so a file added to the image is picked up
 * by both without anybody remembering to update a list — the failure that a hardcoded list invites
 * is an image that silently keeps being reused after its contents changed.
 *
 * <p>The mode in the manifest is not decoration either: an entrypoint extracted without its
 * executable bit produces a container that dies as pid 1 with "permission denied", which is a
 * long way from the cause.
 *
 * <p>Deliberately <b>package-private</b>: how oillamp carries its own image around. Callers see
 * the consequence — a build that is skipped or not — rather than the mechanism.
 */
final class ImageResources {

    private static final String ROOT = "/image/";
    private static final String MANIFEST = ROOT + "MANIFEST";

    private ImageResources() {}

    /** One file of the image: where it goes, and whether it must be executable. */
    record Entry(String path, PosixMode mode) {}

    /**
     * Every file of the image, in manifest order.
     *
     * @throws IllegalStateException if the manifest is missing, which means a broken build rather
     *         than anything a user did — the jar is not the jar we shipped.
     */
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

    /** The bytes of one image file, by its manifest path. */
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

    /**
     * A hash over every image file and every build argument, which becomes the image tag.
     *
     * <p>This is what makes rebuilds automatic and sharing safe: two lamps whose configuration
     * produces byte-identical inputs get the same tag and one build, and changing any input — a
     * package added to {@code extra_apt_packages}, a line edited in the entrypoint — produces a
     * different tag, so the old image is never silently reused. Paths are hashed alongside the
     * contents so that moving a file counts as a change.
     */
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
