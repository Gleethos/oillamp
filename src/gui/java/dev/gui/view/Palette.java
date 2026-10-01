package dev.gui.view;

import java.awt.Color;
import java.awt.Font;

/// The colours of Genies: a lamp burning at night. Deep plum for the dark, brass for the lamp,
/// and the amber of its flame for whatever is alive or asks to be clicked.
final class Palette {

    private Palette() {}

    static final Color NIGHT       = new Color(0x15, 0x12, 0x1c);
    static final Color SIDEBAR     = new Color(0x1a, 0x16, 0x22);
    static final Color CARD        = new Color(0x22, 0x1d, 0x2c);
    static final Color RAISED      = new Color(0x2b, 0x25, 0x37);
    static final Color BORDER      = new Color(0x3a, 0x32, 0x48);
    static final Color TEXT        = new Color(0xef, 0xe6, 0xd8);
    static final Color SUBTEXT     = new Color(0xa8, 0x9c, 0x8a);
    static final Color FLAME       = new Color(0xf0, 0xa9, 0x40);
    static final Color ON_FLAME    = new Color(0x1d, 0x14, 0x06);
    static final Color BRASS       = new Color(0xc8, 0x96, 0x3e);
    /// Inline code in an answer: warm, so it stands out from the text around it.
    static final Color CODE_TEXT   = new Color(0xf3, 0xc6, 0x77);
    static final Color YOURS       = new Color(0x3b, 0x2d, 0x18);
    static final Color GENIES      = new Color(0x26, 0x20, 0x33);
    static final Color SMOKE       = new Color(0x1d, 0x19, 0x26);
    static final Color TROUBLE     = new Color(0xe0, 0x64, 0x5a);
    static final Color TROUBLE_WASH = new Color(0x3a, 0x1c, 0x1e);
    static final Color CONTENT     = new Color(0x8f, 0xd1, 0x9e);
    static final Color GLOW        = new Color(0xf0, 0xa9, 0x40, 38);
    static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    /// The fonts Genies brings along, installed before any is named.
    static {
        com.formdev.flatlaf.fonts.inter.FlatInterFont.install();
        com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont.install();
        com.formdev.flatlaf.fonts.roboto_mono.FlatRobotoMonoFont.install();
    }

    static final String FONT = "Inter";
    static final String MONO = regular("JetBrains Mono", "Roboto Mono");

    /// The first of `families` whose plain face is its regular one. A family also installed on
    /// the desktop, in other weights, wins over the copy Genies brings, and its plain face can
    /// then be bold or light: JetBrains Mono shows bold everywhere on a desktop that has it as
    /// a set of separate weights. The last family is used when none fits.
    private static String regular(String... families) {
        for (String family : families)
            if (new Font(family, Font.PLAIN, 13).getFontName().equals(family + " Regular")) return family;
        return families[families.length - 1];
    }
}
