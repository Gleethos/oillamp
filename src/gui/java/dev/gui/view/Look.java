package dev.gui.view;

import java.awt.Color;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.text.JTextComponent;

import swingtree.UI;
import swingtree.style.ComponentStyleDelegate;
import swingtree.style.StyleSheet;

import static dev.gui.view.Palette.*;

/// Paints the chrome of Genies: the frame, the sidebar, cards, buttons and inputs.
final class Look extends StyleSheet {

    /// Laid over a button while the pointer is on it, and while it is pressed.
    private static final Color HOVER = new Color(255, 255, 255, 18);
    private static final Color FLAME_HOVER = new Color(255, 255, 255, 40);
    private static final Color PRESSED = new Color(0, 0, 0, 40);

    @Override
    protected void configure() {
        // The night behind everything, with the lamp's glow in the top corner.
        add(group(Skin.FRAME), it -> it
            .backgroundColor(NIGHT)
            .foundationColor(NIGHT)
            .gradient("lamplight", g -> g
                .type(UI.GradientType.RADIAL)
                .boundary(UI.ComponentBoundary.OUTER_TO_EXTERIOR)
                .span(UI.Span.TOP_LEFT_TO_BOTTOM_RIGHT)
                .offset(it.componentWidth() * 0.35, 0)
                .size(Math.max(it.componentWidth(), it.componentHeight()) * 0.9f)
                .colors(GLOW, TRANSPARENT)
                .clipTo(UI.ComponentArea.BODY)
            )
            .componentFont(f -> f
                .family(FONT)
                .size(13)
                .color(TEXT)
            )
        );
        add(group(Skin.PAGE_SCROLL), it -> it
            .backgroundColor(TRANSPARENT)
            .border(0, TRANSPARENT)
            .padding(0)
        );
        add(group(Skin.SIDEBAR), it -> it
            .backgroundColor(SIDEBAR)
            .borderAt(UI.Edge.RIGHT, 1, BORDER)
            .padding(12, 12, 12, 12)
        );
        add(group(Skin.BRAND), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(19)
                .weight(2f)
                .color(TEXT)
            )
        );
        add(group(Skin.SECTION), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(11)
                .weight(2f)
                .spacing(0.14f)
                .color(SUBTEXT)
            )
        );
        add(group(Skin.HEADER), it -> it
            .backgroundColor(new Color(0x1a, 0x16, 0x22, 220))
            .borderAt(UI.Edge.BOTTOM, 1, BORDER)
            .padding(10, 16, 10, 16)
        );
        add(group(Skin.TITLE), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(17)
                .weight(2f)
                .color(TEXT)
            )
        );
        add(group(Skin.SUBTITLE), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(12)
                .color(SUBTEXT)
            )
        );
        add(group(Skin.META), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(11)
                .color(SUBTEXT)
            )
        );
        add(group(Skin.CARD), it -> it
            .backgroundColor(CARD)
            .borderRadius(16)
            .border(1, BORDER)
            .padding(22)
            .shadow("card", s -> s
                .color(new Color(0, 0, 0, 120))
                .offset(0, 4)
                .blurRadius(18)
            )
        );
        add(group(Skin.EMPTY_TITLE), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(20)
                .weight(2f)
                .color(TEXT)
            )
        );
        add(group(Skin.EMPTY_TEXT), it -> it
            .componentFont(f -> f
                .family(FONT)
                .size(13)
                .color(SUBTEXT)
            )
        );
        add(type(AbstractButton.class).group(Skin.FLAME_BUTTON), it ->
            lit(FLAME_HOVER, it
                .backgroundColor(FLAME)
                .foregroundColor(ON_FLAME)
                .borderRadius(10)
                .border(0, FLAME)
                .padding(7, 16, 7, 16)
                .componentFont(f -> f
                    .family(FONT).size(13).weight(2f).color(ON_FLAME)
                )
                .cursor(UI.Cursor.HAND)
            )
        );
        add(type(AbstractButton.class).group(Skin.QUIET_BUTTON), it ->
            lit(HOVER, it
                .backgroundColor(RAISED)
                .foregroundColor(TEXT)
                .borderRadius(10)
                .border(1, BORDER)
                .padding(6, 13, 6, 13)
                .componentFont(f -> f.family(FONT).size(12).color(TEXT))
                .cursor(UI.Cursor.HAND)
            )
        );
        add(type(AbstractButton.class).group(Skin.ICON_BUTTON), it ->
            lit(HOVER, it
                .backgroundColor(TRANSPARENT)
                .foregroundColor(SUBTEXT)
                .borderRadius(9)
                .border(0, TRANSPARENT)
                .padding(3, 8, 3, 8)
                .componentFont(f -> f.family(FONT).size(14).color(SUBTEXT))
                .cursor(UI.Cursor.HAND)
            )
        );
        add(type(JTextComponent.class).group(Skin.INPUT), it -> it
            .backgroundColor(RAISED)
            .foregroundColor(TEXT)
            .borderRadius(10)
            .border(1, BORDER)
            .padding(6, 10, 6, 10)
            .componentFont(f -> f.family(FONT).size(13).color(TEXT))
        );
        add(group(Skin.COMPOSER), it -> it
            .backgroundColor(CARD)
            .borderRadius(14)
            .border(1, BORDER)
            .padding(8)
        );
        // The schedule: choices as chips, the days of a calendar, jobs as tiles, and times.
        add(type(AbstractButton.class).group(Skin.CHIP), it ->
            lit(HOVER, it
                .backgroundColor(TRANSPARENT)
                .foregroundColor(TEXT)
                .borderRadius(999)
                .border(1, BORDER)
                .padding(5, 12, 5, 12)
                .componentFont(f -> f.family(FONT).size(12).color(TEXT))
                .cursor(UI.Cursor.HAND)
            )
        );
        add(type(AbstractButton.class).group(Skin.DAY), it ->
            lit(HOVER, it
                .backgroundColor(TRANSPARENT)
                .borderRadius(9)
                .border(1, TRANSPARENT)
                .padding(0)
                .componentFont(f -> f.family(FONT).size(12).color(TEXT))
                .cursor(UI.Cursor.HAND)
            )
        );
        add(group(Skin.TILE), it -> it
            .backgroundColor(CARD)
            .borderRadius(14)
            .border(1, BORDER)
            .padding(12, 14, 12, 14)
        );
        add(group(Skin.CLOCK), it -> it
            .componentFont(f -> f.family(FONT).size(12).weight(1.5f).color(SUBTEXT))
        );
        add(group(Skin.PROBLEM), it -> it
            .componentFont(f -> f.family(FONT).size(12).color(TROUBLE))
        );
        add(group(Skin.FINE), it -> it
            .componentFont(f -> f.family(FONT).size(12).color(CONTENT))
        );
    }

    /// `it`, a button, lit with `light` while the pointer is on it and darkened while it is
    /// pressed, so the user sees that it can be clicked; a button that cannot be clicked stays
    /// as it is. Painted anew whenever the button is painted, which the look-and-feel does each
    /// time the pointer comes or goes and the button is pressed or let go.
    private static ComponentStyleDelegate<AbstractButton> lit(Color light, ComponentStyleDelegate<AbstractButton> it) {
        ButtonModel model = it.component().getModel();
        int width = it.componentWidth(), height = it.componentHeight();
        return it.painter(UI.Layer.BACKGROUND, "hover", g -> {
            if (!model.isEnabled() || !model.isRollover()) return;
            g.setColor(model.isPressed() && model.isArmed() ? PRESSED : light);
            g.fillRect(0, 0, width, height);
        });
    }
}
