package dev.gui.view;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import swingtree.api.IconDeclaration;

/// The signs on Genies' buttons, drawn as SVG text with the same pen: lines one and a half
/// units wide on a square of sixteen, in the colour of the button's words. A sign stands beside
/// its button's words while the header has room for them, and alone when it has not.
final class SignSvgUtil {

    private SignSvgUtil() {}

    /// Every sign handed out, by its SVG text. SwingTree keeps a drawn icon only while its
    /// declaration is held somewhere, so holding them here spares drawing a sign again each time
    /// a button's style is worked out. A few drawings in a few colours, so it stays small.
    private static final Map<String, IconDeclaration> SIGNS = new ConcurrentHashMap<>();

    static final String CHAT = "<path d='M3.5 3h9a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H7l-3 2.5V12h-.5A1.5 1.5 0 0 1 2 10.5v-6A1.5 1.5 0 0 1 3.5 3z'/>";
    static final String SCHEDULE = "<rect x='2' y='3.5' width='12' height='10.5' rx='1.5'/><path d='M2 7h12M5.5 2v3M10.5 2v3'/>";
    static final String HISTORY = "<path d='M2.5 8a5.5 5.5 0 1 0 1.6-3.9'/><path d='M3.8 1.8v2.6h2.6'/><path d='M8 5v3.2l2.2 1.4'/>";
    static final String DESKTOP = "<rect x='1.5' y='2.5' width='13' height='8.5' rx='1.5'/><path d='M8 11v3M5.5 14h5'/>";
    static final String STOP = "<rect x='4' y='4' width='8' height='8' rx='1.5' fill='currentColor'/>";
    static final String SLEEP = "<path d='M13.5 9.6A5.8 5.8 0 1 1 6.4 2.5a4.6 4.6 0 0 0 7.1 7.1z'/>";
    static final String GENIES = "<path d='M2.5 4h11M2.5 8h11M2.5 12h11'/>";
    static final String DELETE = "<path d='M2.5 4.5h11M6.5 4.5V3h3v1.5M4 4.5l.7 9a1 1 0 0 0 1 .9h4.6a1 1 0 0 0 1-.9l.7-9M6.8 7v4.5M9.2 7v4.5'/>";
    static final String NEW = "<path d='M8 3v10M3 8h10'/>";
    static final String SETTINGS = "<path d='M2 4.5h12M2 11.5h12'/>"
                                 + "<circle cx='10.5' cy='4.5' r='2' fill='currentColor'/><circle cx='5.5' cy='11.5' r='2' fill='currentColor'/>";
    static final String MORE = "<circle cx='3.5' cy='8' r='1.2' fill='currentColor' stroke='none'/>"
                             + "<circle cx='8' cy='8' r='1.2' fill='currentColor' stroke='none'/>"
                             + "<circle cx='12.5' cy='8' r='1.2' fill='currentColor' stroke='none'/>";

    /// `drawing`, one of the signs above, in `colour`, sixteen units square.
    static IconDeclaration sign(String drawing, Color colour) {
        String hex = String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 16 16' color='" + hex
                + "' fill='none' stroke='currentColor' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'>"
                + drawing + "</svg>";
        return SIGNS.computeIfAbsent(svg, IconDeclaration::ofSvg);
    }
}
