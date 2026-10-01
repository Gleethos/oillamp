package dev.gui.view;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import dev.gui.desktop.Keysyms;
import dev.gui.desktop.RfbConnection;

import swingtree.UI;

/// A genie's desktop, drawn to fit or at a chosen scale, with the pointer and the keyboard passed
/// through to it.
///
/// It connects to the desktop socket it is shown with [#show(Optional)], and lets go of it when
/// shown nothing. Click it to type into the desktop; everything typed then goes there, Tab
/// included, until another component takes the focus. A flame-coloured frame shows when it does.
///
/// Meant to sit in a scroll pane: fitted, it takes whatever room the pane has; zoomed, it is as
/// large as the desktop at that scale, and the pane scrolls.
final class DesktopScreen extends JComponent implements Scrollable {

    private Optional<RfbConnection> connection = Optional.empty();
    private Optional<Path> shown = Optional.empty();
    private String message = "The desktop shows here while the genie is awake.";
    /// Where the desktop's picture was last drawn, to map the mouse back onto the desktop.
    private double scale = 1, left, top;
    private int buttons;
    private final Map<Integer, Integer> pressed = new HashMap<>();
    /// The scale the desktop is drawn at; 0 means fit.
    private double zoom;
    /// Called with +1 or -1 when the user turns the wheel with Control held, to zoom.
    private IntConsumer zoomSteps = steps -> {};

    DesktopScreen() {
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { repaint(); }
            @Override public void focusLost(FocusEvent event) { repaint(); }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                buttons |= mask(event);
                pointer(event);
            }
            @Override public void mouseReleased(MouseEvent event) {
                buttons &= ~mask(event);
                pointer(event);
            }
            @Override public void mouseMoved(MouseEvent event) { pointer(event); }
            @Override public void mouseDragged(MouseEvent event) { pointer(event); }
            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                if (event.isControlDown()) {
                    zoomSteps.accept(event.getWheelRotation() < 0 ? 1 : -1);
                    return;
                }
                int wheel = event.getWheelRotation() < 0 ? 8 : 16;
                connection.ifPresent(desktop -> {
                    int x = desktopX(event.getX()), y = desktopY(event.getY());
                    desktop.pointer(x, y, buttons | wheel);
                    desktop.pointer(x, y, buttons);
                });
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                Keysyms.of(event.getKeyCode(), event.getKeyChar()).ifPresent(keysym -> {
                    pressed.put(event.getKeyCode(), keysym);
                    connection.ifPresent(desktop -> desktop.key(keysym, true));
                });
                event.consume();
            }
            @Override public void keyReleased(KeyEvent event) {
                Integer keysym = pressed.remove(event.getKeyCode());
                if (keysym != null) connection.ifPresent(desktop -> desktop.key(keysym, false));
                event.consume();
            }
        });
    }

    /// Draws the desktop at `scale`, or to fit when it is 0 or less.
    void zoom(double scale) {
        if (scale == zoom) return;
        zoom = scale;
        revalidate();
        repaint();
    }

    void onZoomSteps(IntConsumer steps) { zoomSteps = steps; }

    /// The scale fit draws the desktop at right now, so that zooming from fit starts there.
    double fitScale() {
        Dimension room = getParent() instanceof JViewport viewport ? viewport.getExtentSize() : getSize();
        return connection.map(desktop -> Math.min((double) room.width / desktop.screen().getWidth(),
                                                  (double) room.height / desktop.screen().getHeight()))
                         .filter(scale -> scale > 0).orElse(1.0);
    }

    // ─── in a scroll pane ──────────────────────────────────────────────────────────────────

    /// Fitted, small, so it never pushes the layout; zoomed, the desktop's size at that scale.
    @Override public Dimension getPreferredSize() {
        if (zoom <= 0) return new Dimension(UI.scale(320), UI.scale(200));
        return connection.map(desktop -> new Dimension(
                        (int) Math.round(desktop.screen().getWidth() * zoom),
                        (int) Math.round(desktop.screen().getHeight() * zoom)))
                .orElse(new Dimension(UI.scale(320), UI.scale(200)));
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return UI.scale(24);
    }

    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    }

    /// Fitted, it follows the pane's size exactly. Zoomed, it does too while it is smaller than
    /// the pane, so it stays centred rather than stuck in a corner.
    @Override public boolean getScrollableTracksViewportWidth() {
        return zoom <= 0 || (getParent() instanceof JViewport viewport && viewport.getWidth() > getPreferredSize().width);
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return zoom <= 0 || (getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height);
    }

    /// Shows the desktop at `socket`, or, when empty, nothing. Showing the one already shown
    /// changes nothing.
    void show(Optional<Path> socket) {
        if (socket.equals(shown) && (connection.isPresent() || socket.isEmpty())) return;
        connection.ifPresent(RfbConnection::close);
        connection = Optional.empty();
        shown = socket;
        message = socket.isEmpty() ? "The desktop shows here while the genie is awake." : "Connecting to the desktop…";
        repaint();
        socket.ifPresent(path -> Thread.ofVirtual().name("desktop connect").start(() -> connect(path)));
    }

    private void connect(Path socket) {
        try {
            RfbConnection opened = RfbConnection.open(socket, new RfbConnection.Listener() {
                @Override public void resized(int width, int height) { SwingUtilities.invokeLater(() -> { revalidate(); repaint(); }); }
                @Override public void painted(int x, int y, int width, int height) { repaint(); }
                @Override public void ended(Optional<String> reason) {
                    reason.ifPresent(why -> SwingUtilities.invokeLater(() -> {
                        if (shown.equals(Optional.of(socket))) {
                            connection = Optional.empty();
                            shown = Optional.empty();
                            message = "The desktop went away: " + why;
                            repaint();
                        }
                    }));
                }
            });
            SwingUtilities.invokeLater(() -> {
                if (shown.equals(Optional.of(socket))) connection = Optional.of(opened);
                else opened.close();
                revalidate();
                repaint();
            });
        } catch (IOException failed) {
            SwingUtilities.invokeLater(() -> {
                message = "Could not show the desktop: " + failed.getMessage();
                repaint();
            });
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(Palette.SMOKE);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (connection.isEmpty()) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Palette.SUBTEXT);
                g.setFont(getFont().deriveFont(UI.scale(13f)));
                int width = g.getFontMetrics().stringWidth(message);
                g.drawString(message, Math.max(8, (getWidth() - width) / 2), getHeight() / 2);
                return;
            }
            BufferedImage screen = connection.get().screen();
            scale = zoom > 0 ? zoom : Math.min((double) getWidth() / screen.getWidth(), (double) getHeight() / screen.getHeight());
            int width = (int) Math.round(screen.getWidth() * scale), height = (int) Math.round(screen.getHeight() * scale);
            left = Math.max(0, (getWidth() - width) / 2.0);
            top = Math.max(0, (getHeight() - height) / 2.0);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            synchronized (screen) {
                g.drawImage(screen, (int) left, (int) top, width, height, null);
            }
            if (hasFocus()) {
                // The keyboard goes to the desktop now: say so, around the picture.
                g.setColor(Palette.FLAME);
                g.setStroke(new BasicStroke(UI.scale(2f)));
                g.drawRect((int) left + 1, (int) top + 1, width - 2, height - 2);
            }
        } finally {
            g.dispose();
        }
    }

    private void pointer(MouseEvent event) {
        connection.ifPresent(desktop -> desktop.pointer(desktopX(event.getX()), desktopY(event.getY()), buttons));
    }

    private int desktopX(int x) {
        int max = connection.map(desktop -> desktop.screen().getWidth() - 1).orElse(0);
        return Math.max(0, Math.min(max, (int) ((x - left) / scale)));
    }

    private int desktopY(int y) {
        int max = connection.map(desktop -> desktop.screen().getHeight() - 1).orElse(0);
        return Math.max(0, Math.min(max, (int) ((y - top) / scale)));
    }

    private static int mask(MouseEvent event) {
        return switch (event.getButton()) {
            case MouseEvent.BUTTON1 -> 1;
            case MouseEvent.BUTTON2 -> 2;
            case MouseEvent.BUTTON3 -> 4;
            default -> 0;
        };
    }
}
