package dev.oillamp;

/// Families of Linux distributions, grouped by package manager. oillamp can only install host
/// packages automatically on [#DEBIAN] (apt). On the others it lists the packages for the
/// user to install.
enum DistroFamily { DEBIAN, RPM, ARCH, UNKNOWN }
