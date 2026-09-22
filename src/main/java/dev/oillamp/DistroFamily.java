package dev.oillamp;

/**
 * Package-manager families. Only {@link #DEBIAN} can be installed onto automatically in v1 (D-17).
 *
 * <p>Deliberately <b>package-private</b>: apt today, possibly dnf and pacman later (§7). Public
 * would turn adding a family into an API change.
 */
enum DistroFamily { DEBIAN, RPM, ARCH, UNKNOWN }
