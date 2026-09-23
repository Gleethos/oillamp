package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;
import sprouts.ValueSet;

/// Everything oillamp learned about the host, gathered by [HostProbe].
///
/// This is the only input to [HostPlanner]. A check that fails is recorded as a fact (an
/// empty `Optional`, a `Fails` case) rather than thrown, so one broken check does not
/// hide the others.
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
