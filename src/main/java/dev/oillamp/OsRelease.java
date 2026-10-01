package dev.oillamp;

import java.util.Locale;

import sprouts.Tuple;

/// The parts of `/etc/os-release` oillamp uses, plus Java's `os.name`.
record OsRelease(String osName, String id, Tuple<String> idLike, String versionId, String prettyName) {

    public boolean isLinux() { return osName.toLowerCase(Locale.ROOT).contains("linux"); }

    /// Which package manager this distribution uses, judged from `ID` and `ID_LIKE`.
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
