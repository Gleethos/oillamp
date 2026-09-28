package dev.gui.view;

import javax.swing.JTextArea;

import dev.gui.model.Genie;

import sprouts.Val;
import swingtree.UI;
import swingtree.UIForAnySwing;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// Small pieces the views share.
final class Parts {

    private Parts() {}

    /// The lamp of a genie in `phase`, drawn from SVG at `size`.
    static UIForAnySwing<?, ?> lamp(Val<Genie.Phase> phase, int size) {
        return box().withPrefSize(size, size).withMinSize(size, size)
                .withStyle(phase.viewAsString(Art::lamp), (svg, it) -> it.image(img -> img.svg(svg).fitMode(UI.FitComponent.MIN_DIM)));
    }

    /// A few lines of small print, wrapped to the width they are given.
    static UIForAnySwing<?, ?> note(String text, Val<Boolean> shown) {
        return box().withMinSize(0, 0).isVisibleIf(shown)
                // Some padding, on purpose: text painted by the style engine sets its component's
                // height when it is painted, and a component of no height is never painted.
                .withStyle(it -> it.padding(2, 0, 2, 0).text(t -> t
                        .content(swingtree.style.StyledString.of(f -> f.family(FONT).size(11).color(SUBTEXT), text))
                        .placement(UI.Placement.TOP_LEFT).wrapLines(true).autoPreferredHeight(true)));
    }

    /// Line wrapping for a text area, which has no SwingTree method, so this one Swing setter is
    /// called directly.
    static void softWrap(JTextArea area) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
    }
}
