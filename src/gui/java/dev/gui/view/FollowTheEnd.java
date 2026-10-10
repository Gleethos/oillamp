package dev.gui.view;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BoundedRangeModel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import swingtree.UI;

/// Decides the chat's scroll position: at the bottom while the user is there, and otherwise
/// where the user last scrolled to.
///
/// Only the user's input changes either: the mouse wheel or touchpad over the chat, and dragging
/// or clicking its scroll bar. Scrolling up by any amount leaves the bottom; scrolling down to
/// within [#SLACK] of it returns there. Every other change of the scroll bar comes from Swing's
/// layout: rows added, removed or growing, the thinking bar appearing under the chat, the
/// composer growing, the window being resized. Swing's layout also moves the scroll position
/// itself, for a moment, when the content gets shorter, as when new rows are still flat before
/// their first paint. After each such change, the scroll position is set back: to the bottom, or
/// to where the user last scrolled to.
final class FollowTheEnd {

    /// How far above the bottom, in the window's units, a scroll down still counts as reaching it.
    private static final int SLACK = 48;

    private final BoundedRangeModel range;
    private boolean atBottom = true;
    /// The scroll position the user last scrolled to, used while not at the bottom.
    private int userPosition = 0;
    /// The scroll position when the user pressed the scroll bar, to tell up from down on release.
    private int pressedAt = 0;
    /// True while the user holds the mouse button on the scroll bar.
    private boolean dragging = false;
    /// True while this class sets the scroll position, so that its own change is not acted on.
    private boolean scrolling = false;

    private FollowTheEnd(JScrollPane pane) {
        this.range = pane.getVerticalScrollBar().getModel();
        range.addChangeListener(event -> changed());
        // Added after the look-and-feel's wheel listener, so it runs after that has moved the
        // scroll bar. A touchpad turns the wheel by fractions, so only the precise rotation says
        // which way.
        pane.addMouseWheelListener(event -> userScrolled(event.getPreciseWheelRotation() < 0));
        pane.getVerticalScrollBar().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                dragging = true;
                pressedAt = range.getValue();
            }
            @Override public void mouseReleased(MouseEvent event) {
                dragging = false;
                userScrolled(range.getValue() < pressedAt);
            }
        });
    }

    /// Attaches to `pane`. The listeners this needs have no SwingTree methods.
    static FollowTheEnd on(JScrollPane pane) { return new FollowTheEnd(pane); }

    /// Scrolls to the bottom and stays there as the chat changes, as after sending a message or
    /// opening another conversation.
    void toEnd() {
        atBottom = true;
        SwingUtilities.invokeLater(this::restore);
    }

    private void userScrolled(boolean up) {
        boolean nearBottom = range.getValue() + range.getExtent() >= range.getMaximum() - UI.scale(SLACK);
        atBottom = !up && nearBottom;
        userPosition = range.getValue();
    }

    private void changed() {
        if (scrolling || dragging) return;
        int wanted = atBottom ? range.getMaximum() - range.getExtent() : userPosition;
        if (range.getValue() != wanted) SwingUtilities.invokeLater(this::restore);
    }

    /// Sets the scroll position to the bottom, or to where the user last scrolled to. While the
    /// content is too short for that, the scroll bar stops at the bottom, and the next change of
    /// the content sets it again.
    private void restore() {
        if (dragging) return;
        scrolling = true;
        try {
            range.setValue(atBottom ? range.getMaximum() - range.getExtent() : userPosition);
        } finally {
            scrolling = false;
        }
    }
}
