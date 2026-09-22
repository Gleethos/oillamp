package dev.oillamp;

/**
 * Whether there is a desktop to open windows on — spec §11.2, {@code OIL-HOST-003}.
 *
 * <p>Fatal for {@code at}, {@code view} and {@code shell}, which all open windows; {@code doctor}
 * runs anyway, because being told "you have no display" over SSH is exactly what doctor is for.
 */
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

    /** The desktop environment name, used to pick that desktop's native terminal first (D-22). */
    default String desktop() {
        return switch (this) {
            case Wayland w -> w.desktop();
            case X11 x     -> x.desktop();
            case None ignored -> "";
        };
    }
}
