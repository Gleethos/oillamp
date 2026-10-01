package dev.oillamp;

import java.util.Locale;

/// The `display.windows` setting: how the sandbox desktop arranges windows.
///
/// [#FLOATING], the default, is what most people's desktops do: a window opens at the size the
/// application asks for, and is moved by its title bar and resized by its edges. Applications are
/// built and tested that way, and it is what the agent has seen most screenshots of.
///
/// [#TILING] splits the screen between the open windows, so none ever covers another. The agent
/// then sees every window in one screenshot, but a single window always fills the whole screen
/// and cannot be moved or resized by dragging.
enum WindowLayout {
    FLOATING, TILING;

    public String configName() { return name().toLowerCase(Locale.ROOT); }
}
