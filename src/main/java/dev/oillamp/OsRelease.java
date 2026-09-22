package dev.oillamp;

import sprouts.Tuple;

/**
 * The contents of {@code /etc/os-release} that matter, plus {@code os.name} — spec §11.2.
 *
 * <p>Deliberately <b>package-private</b>: a parsed {@code /etc/os-release}.
 */
record OsRelease(String osName, String id, Tuple<String> idLike, String versionId, String prettyName) {

    public boolean isLinux() { return osName.toLowerCase(java.util.Locale.ROOT).contains("linux"); }

    /**
     * Which package manager family this distribution belongs to. The package set is keyed by
     * this (spec §11.1), so adding dnf or pacman later is data, not new code paths.
     */
    public DistroFamily family() {
        if (matches("debian") || matches("ubuntu")) return DistroFamily.DEBIAN;
        if (matches("fedora") || matches("rhel"))   return DistroFamily.RPM;
        if (matches("arch"))                        return DistroFamily.ARCH;
        return DistroFamily.UNKNOWN;
    }

    private boolean matches(String needle) {
        if (id.equalsIgnoreCase(needle)) return true;
        for (String like : idLike)
            if (like.equalsIgnoreCase(needle)) return true;
        return false;
    }
}
