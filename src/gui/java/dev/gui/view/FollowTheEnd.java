package dev.gui.view;

import javax.swing.BoundedRangeModel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import swingtree.UI;

/// Keeps a conversation at its end while the user is there, and leaves them be when they are
/// not.
///
/// Whether the user is "at the end" is decided only by what they do: scrolling, dragging,
/// resizing the window. When the conversation grows instead, a message sent, an answer streaming
/// in, a row that became taller once its text was laid out, the pane follows if the user was at
/// the end, all the way down, however late the growth comes. Scrolled up to read, they stay
/// where they are.
final class FollowTheEnd {

    /// How close to the end still counts as at the end, in the window's units.
    private static final int SLACK = 48;

    private final JScrollPane pane;
    private boolean atEnd = true;
    private int maximum = -1;

    private FollowTheEnd(JScrollPane pane) {
        this.pane = pane;
        BoundedRangeModel range = pane.getVerticalScrollBar().getModel();
        range.addChangeListener(event -> changed(range));
    }

    /// Makes `pane` follow its end. The one raw listener this needs has no SwingTree method.
    static FollowTheEnd on(JScrollPane pane) { return new FollowTheEnd(pane); }

    /// Goes to the end, and follows it from now on, as when another conversation is shown.
    void toEnd() {
        atEnd = true;
        SwingUtilities.invokeLater(this::scrollToEnd);
    }

    private void changed(BoundedRangeModel range) {
        if (range.getMaximum() != maximum) {
            // The conversation grew or shrank: follow it, if the user was at the end.
            maximum = range.getMaximum();
            if (atEnd) SwingUtilities.invokeLater(this::scrollToEnd);
        } else {
            // The user moved, or the window changed size.
            atEnd = range.getValue() + range.getExtent() >= range.getMaximum() - UI.scale(SLACK);
        }
    }

    private void scrollToEnd() {
        BoundedRangeModel range = pane.getVerticalScrollBar().getModel();
        range.setValue(range.getMaximum() - range.getExtent());
    }
}
