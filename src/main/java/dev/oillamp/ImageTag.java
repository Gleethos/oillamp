package dev.oillamp;

/// A sandbox image tag, `localhost/oillamp/sandbox:<16 hex digits>`.
///
/// The digits are the start of a hash of everything that goes into the image (see
/// [ImageResources#hashOf]). Lamps whose configuration produces the same image share one
/// build, and changing any input, such as the base image, extra packages or a file under
/// `src/main/resources/image`, produces a new tag and therefore a rebuild.
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
