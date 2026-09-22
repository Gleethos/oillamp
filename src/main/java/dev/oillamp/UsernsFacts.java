package dev.oillamp;


/**
 * Whether unprivileged user namespaces actually work — spec §11.2, {@code OIL-PODMAN-003/004}.
 *
 * <p>Deliberately a <em>functional</em> check ({@code podman unshare true}) rather than a version
 * comparison: on Ubuntu 23.10 and newer, an AppArmor profile can block user namespaces on a
 * perfectly modern podman, and only running it reveals that.
 *
 * <p>Deliberately <b>package-private</b>: whether unprivileged user namespaces work, and whether
 * AppArmor is why not — the single most likely reason oillamp fails to start on a current Ubuntu
 * (§11.2).
 */
sealed interface UsernsFacts {
    record Works() implements UsernsFacts {}
    record Fails(Problem.Evidence.Command evidence, boolean apparmorRestricted) implements UsernsFacts {}
}
