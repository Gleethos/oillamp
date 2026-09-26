package dev.oillamp;

/// A sandbox image tag, `localhost/oillamp/sandbox:<16 hex digits>`.
///
/// The digits are the start of a hash of everything that goes into the image (see
/// [ImageResources#hashOf]). Lamps whose configuration produces the same image share one
/// build, and changing any input, such as the base image, extra packages or a file under
/// `src/main/resources/image`, produces a new tag and therefore a rebuild.
///
/// In podman's terms the whole string is the image's _name_: repository
/// `localhost/oillamp/sandbox` (built here, never pulled from a registry) and a tag. It is not the
/// image ID, which podman computes from the built image and shows in its own column of
/// `podman images`. The name points to an ID, as a git tag points to a commit.
///
/// The tag is a hash of the build's inputs, not of its result. The build installs whatever Debian
/// offers on the day, so an image with a given tag is as fresh as its build: oillamp never
/// rebuilds because packages were updated upstream. Removing the image with `podman rmi` makes the
/// next session build it again.
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
