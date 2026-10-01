package dev.gui.model;

import java.util.Locale;

/// How large a genie's desktop is shown.
///
/// A genie's desktop has a size of its own, often larger than the room next to the chat. Panel
/// gives the desktop the size of that room, so what the genie shows fills it, pixel for pixel;
/// the desktop gets its own size back when it is no longer shown so. Fit shrinks the desktop's
/// picture to the room, so all of it is visible and nothing needs scrolling. A scale shows it at
/// that size, which makes small text readable, with scroll bars when it does not fit.
///
/// @param scale the size, 1 being one desktop pixel per screen pixel; 0 means fit, and less than
///              0 the size of the panel
public record DesktopZoom(double scale) {

    public static final DesktopZoom PANEL = new DesktopZoom(-1);
    public static final DesktopZoom FIT = new DesktopZoom(0);

    /// The scales the zoom steps through.
    static final double[] STEPS = {0.5, 0.67, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0};

    public boolean isFit() { return scale == 0; }

    /// Whether the desktop takes the size of the panel it is shown in.
    public boolean isPanel() { return scale < 0; }

    /// One step larger. From fit or panel, the step above the scale the desktop shows at.
    ///
    /// @param fitScale the scale fit shows the desktop at right now
    public DesktopZoom in(double fitScale) {
        double from = scale <= 0 ? fitScale : scale;
        for (double step : STEPS) if (step > from + 0.01) return new DesktopZoom(step);
        return new DesktopZoom(STEPS[STEPS.length - 1]);
    }

    /// One step smaller. From fit or panel, the step below the scale the desktop shows at.
    public DesktopZoom out(double fitScale) {
        double from = scale <= 0 ? fitScale : scale;
        for (int i = STEPS.length - 1; i >= 0; i--) if (STEPS[i] < from - 0.01) return new DesktopZoom(STEPS[i]);
        return new DesktopZoom(STEPS[0]);
    }

    /// What the zoom buttons say: "Panel", "Fit", or the scale as a percentage.
    public String label() {
        return isPanel() ? "Panel" : isFit() ? "Fit" : String.format(Locale.ROOT, "%d%%", Math.round(scale * 100));
    }
}
