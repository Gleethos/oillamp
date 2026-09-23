package dev.oillamp;

/// What `podman version` and `podman info` reported.
record PodmanFacts(
    String version,
    boolean rootless,
    String ociRuntime,
    String storageDriver,
    int cgroupVersion
) {
    /// The oldest podman with the rootless keep-id mapping oillamp needs. Ubuntu 24.04 ships 4.9.x.
    public static final String MINIMUM_VERSION = "4.9";

    /// Passing the GPU into the container needs `--group-add keep-groups`, which only crun supports.
    public boolean supportsKeepGroups() { return ociRuntime.equals("crun"); }

    public boolean isAtLeastMinimum() { return compareVersions(version, MINIMUM_VERSION) >= 0; }

    /// Compares dotted numeric versions, ignoring any trailing suffix such as `-rc1`.
    public static int compareVersions(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = numberAt(a, i);
            int y = numberAt(b, i);
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try { return Integer.parseInt(parts[index]); } catch (NumberFormatException e) { return 0; }
    }
}
