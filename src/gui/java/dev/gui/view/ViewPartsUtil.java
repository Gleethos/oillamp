package dev.gui.view;

import java.awt.Color;
import java.awt.Component;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import dev.gui.model.Genie;

import sprouts.Val;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.style.StyledString;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// Small pieces the views share.
final class ViewPartsUtil {

    private ViewPartsUtil() {}

    /// The lamp of a genie in `phase`, drawn from SVG at `size`.
    static UIForAnySwing<?, ?> lamp(Val<Genie.Phase> phase, int size) {
        return box().withPrefSize(size, size).withMinSize(size, size)
                .withStyle(phase.viewAsString(LampSvgUtil::lamp), (svg, it) -> it.image(img -> img.svg(svg).fitMode(UI.FitComponent.MIN_DIM)));
    }

    /// A few lines of small print, wrapped to the width they are given.
    static UIForAnySwing<?, ?> note(String text, Val<Boolean> shown) {
        return box().withMinSize(0, 0).isVisibleIf(shown)
                // Some padding, on purpose: text painted by the style engine sets its component's
                // height when it is painted, and a component of no height is never painted.
                .withStyle(it -> it.padding(2, 0, 2, 0).text(t -> t
                        .content(StyledString.of(f -> f.family(FONT).size(11).color(SUBTEXT), text))
                        .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true)));
    }

    /// Text that changes, such as a problem, wrapped to the width it is given rather than cut
    /// short: the end of a message is often the part that says what to do.
    static UIForAnySwing<?, ?> wrapped(Val<String> text, Color colour, Val<Boolean> shown) {
        return box().withMinSize(0, 0).isVisibleIf(shown)
                .withStyle(text, (words, it) -> it.padding(2, 0, 2, 0).text(t -> t
                        .content(StyledString.of(f -> f.family(FONT).size(12).color(colour), words))
                        .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true)));
    }

    /// Line wrapping for a text area, which has no SwingTree method, so this one Swing setter is
    /// called directly.
    static void softWrap(JTextArea area) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
    }

    /// Opens `menu` under `button`, its right edge on the button's: such a button is near the
    /// window's right edge, and a menu opening rightwards would leave the window.
    static void below(JPopupMenu menu, Component button) {
        menu.show(button, button.getWidth() - menu.getPreferredSize().width, button.getHeight());
    }

    static JMenuItem item(String text, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(event -> action.run());
        return item;
    }
}
