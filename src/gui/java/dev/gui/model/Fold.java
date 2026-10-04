package dev.gui.model;

/// Whether a list that folds away is open, and how tall its area is; the user drags the area's
/// lower edge to change that, all the way up to nothing, which folds it away. Such are the trees of
/// conversations under a genie, and the list of genies above the genie in a narrow window.
///
/// @param height the most the list's area takes, in the window's units; a shorter list takes less
public record Fold(boolean shown, int height) {
    public static final int HIGHEST = 900;
    public static final Fold CLOSED = new Fold(false, 180);

    public Fold toggled()                 { return new Fold(!shown, height); }
    public Fold withShown(boolean shown)  { return new Fold(shown, height); }
    public Fold withHeight(int height)    { return new Fold(shown, Math.clamp(height, 0, HIGHEST)); }

    /// The user let go of the grip. Dragged all the way up, the list folds away, and opens
    /// again as tall as it was before that drag, never as an empty strip.
    ///
    /// @param before the height when the drag began
    public Fold released(int before) {
        return height > 0 ? this : new Fold(false, before > 0 ? before : CLOSED.height());
    }
}
