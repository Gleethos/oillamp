package dev.gui.desktop;

import java.nio.file.Path;

/// A genie's desktop: where to reach it, and the size it has of its own.
///
/// @param socket    the Unix socket its VNC server listens on
/// @param ownWidth  its own width in pixels, which it gets back after it was shown at the size of a panel
/// @param ownHeight its own height in pixels
public record Desktop(Path socket, int ownWidth, int ownHeight) {}
