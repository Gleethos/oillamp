package dev.oillamp;


/// Whether rootless podman can create user namespaces on this host, found by running
/// `podman unshare true`.
///
/// This is tested by running the command rather than by checking versions, because on Ubuntu
/// 23.10 and newer an AppArmor setting can block user namespaces even for an up-to-date podman.
/// That is the most likely reason oillamp fails to start on a current Ubuntu, so [Fails]
/// records whether AppArmor is the cause (`OIL-PODMAN-004`) or not (`OIL-PODMAN-003`).
sealed interface UsernsFacts {
    record Works() implements UsernsFacts {}
    record Fails(Problem.Evidence.Command evidence, boolean apparmorRestricted) implements UsernsFacts {}
}
