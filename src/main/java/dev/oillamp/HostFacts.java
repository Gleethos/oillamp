package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;
import sprouts.ValueSet;

/**
 * Everything oillamp learned about the machine it is running on — spec §24.2.
 *
 * <p>This is the whole input to host planning. Probing is effectful and lives in the shell;
 * deciding what to install and fix is pure and takes only this record. A probe that fails
 * becomes a <em>fact</em> (an empty Optional, a {@code Fails} variant) rather than an exception,
 * so one broken check never hides the other nine.
 *
 * <p>Deliberately <b>package-private</b>: everything probed about the host, in one record. It gains
 * a field whenever a new check is added, and that must never be a breaking change.
 */
record HostFacts(
    OsRelease os,
    UserInfo user,
    Optional<Path> xdgRuntimeDir,
    GraphicalSession session,
    // Only the packages oillamp requires, not everything on the system.
    ValueSet<String> installedPackages,
    SubIdFacts subIds,
    Optional<PodmanFacts> podman,
    UsernsFacts userns,
    boolean selinuxEnabled,
    Tuple<TerminalCandidate> terminals,
    Optional<Path> vncViewer,
    GpuFacts gpu,
    int cpuCount,
    SudoFacts sudo,
    String fileSystemTypeOfLamp
) {
    public HostFacts {
        if (cpuCount < 1)
            throw new IllegalArgumentException("A host has at least one CPU, got: " + cpuCount);
    }

    public boolean isLinux()  { return os.isLinux(); }
    public boolean isAptBased(){ return os.family() == DistroFamily.DEBIAN; }
    public boolean hasGraphicalSession() { return !(session instanceof GraphicalSession.None); }
}
