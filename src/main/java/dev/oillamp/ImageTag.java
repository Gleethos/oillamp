package dev.oillamp;

/**
 * A sandbox image tag — spec §12.3.
 *
 * <p>The tag carries the first 16 hex characters of the build-input hash, so every lamp whose
 * configuration produces the same image shares one build, and changing a build input (base image,
 * extra packages, the bundled helper jar) automatically produces a different tag and a rebuild.
 *
 * <p>Deliberately <b>package-private</b>: derived from the image's content hash, so it changes
 * whenever the image does.
 */
record ImageTag(String value) {

    public static final String REPOSITORY = "localhost/oillamp/sandbox";

    public ImageTag {
        if (!value.matches("\\Qlocalhost/oillamp/sandbox\\E:[0-9a-f]{16}"))
            throw new IllegalArgumentException("Not an oillamp image tag: '" + value + "'");
    }

    public static ImageTag ofHash(String hexHash) {
        if (hexHash.length() < 16)
            throw new IllegalArgumentException("Build hash is too short: " + hexHash);
        return new ImageTag(REPOSITORY + ":" + hexHash.substring(0, 16));
    }

    @Override public String toString() { return value; }
}
