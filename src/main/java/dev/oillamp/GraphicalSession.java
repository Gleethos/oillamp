package dev.oillamp;

/// The graphical session oillamp is running in, from `WAYLAND_DISPLAY`, `DISPLAY` and
/// `XDG_CURRENT_DESKTOP`.
///
/// `oillamp at` needs one, because it opens windows (`OIL-HOST-003` otherwise).
/// `doctor` runs without one, because finding out that you are on a plain SSH login is one of
/// the things it is for.
sealed interface GraphicalSession {
    record Wayland(String display, String desktop) implements GraphicalSession {}
    record X11(String display, String desktop)     implements GraphicalSession {}
    record None()                                  implements GraphicalSession {}

    default String describe() {
        return switch (this) {
            case Wayland w -> "Wayland" + (w.desktop().isBlank() ? "" : " (" + w.desktop() + ")");
            case X11 x     -> "X11" + (x.desktop().isBlank() ? "" : " (" + x.desktop() + ")");
            case None ignored -> "none";
        };
    }

    /// The desktop environment's name, used to prefer that desktop's own terminal emulator.
    default String desktop() {
        return switch (this) {
            case Wayland w -> w.desktop();
            case X11 x     -> x.desktop();
            case None ignored -> "";
        };
    }
}
