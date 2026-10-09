package dev.gui.view;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.gui.desktop.Desktop;
import dev.gui.desktop.RfbConnection;
import dev.gui.desktop.X11KeysymUtil;

import sprouts.Val;
import sprouts.Var;
import swingtree.UI;

/// A genie's desktop, at the size of the room it has, drawn to fit, or at a chosen scale, with
/// the pointer and the keyboard passed through to it.
///
/// It connects to the desktop it is shown with [#show(Optional)], and lets go of it when shown
/// nothing. Click it to type into the desktop; everything typed then goes there, Tab included,
/// until another component takes the focus. A flame-coloured frame shows when it does.
///
/// At the size of the room, it asks the desktop to take the room's size, in the screen's own
/// pixels, a moment after the room stopped changing. The desktop gets its own size back when it
/// is drawn otherwise, and when this lets go of it. A desktop that keeps its size, because it is
/// recorded, is drawn to fit.
///
/// Meant to sit in a scroll pane: fitted or at the room's size, it takes whatever room the pane
/// has; zoomed, it is as large as the desktop at that scale, and the pane scrolls.
final class DesktopScreen extends JComponent implements Scrollable {

    /// The smallest size the desktop is given: smaller, applications have no room to work in. A
    /// smaller room shows the desktop shrunk to fit.
    static final int SMALLEST_WIDTH = 400, SMALLEST_HEIGHT = 300;
    /// How long the room keeps its size before the desktop is asked to take it, so that dragging
    /// the window's edge does not ask at every step.
    static final int SETTLING_MILLIS = 250;
    /// How long a desktop that is let go of has to take its own size back.
    static final int GIVING_BACK_MILLIS = 3000;

    private Optional<RfbConnection> connection = Optional.empty();
    private Optional<Desktop> shown = Optional.empty();
    /// Whether the desktop shown said no to another size; it is then drawn to fit.
    private final Var<Boolean> keepsItsSize = Var.of(false);
    /// The size the desktop shown has, such as "1024 × 640"; empty while none is shown.
    private final Var<String> desktopSize = Var.of("");
    /// The size last asked of the desktop shown, so that one request is not sent again and again.
    private Optional<Dimension> asked = Optional.empty();
    private final Timer sizeSettled = new Timer(SETTLING_MILLIS, settled -> sizeTheDesktop());
    /// The connection being let go of, while it gives the desktop its own size back.
    private Optional<Thread> lettingGo = Optional.empty();
    private String message = "The desktop shows here while the genie is awake.";
    /// Where the desktop's picture was last drawn, to map the mouse back onto the desktop.
    private double scale = 1, left, top;
    private int buttons;
    private final Map<Integer, Integer> pressed = new HashMap<>();
    /// The scale the desktop is drawn at; 0 means fit, less than 0 at the size of the room.
    private double zoom;
    /// Called with +1 or -1 when the user turns the wheel with Control held, to zoom.
    private IntConsumer zoomSteps = steps -> {};

    DesktopScreen() {
        sizeSettled.setRepeats(false);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { sizeSettled.restart(); }
        });
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { repaint(); }
            @Override public void focusLost(FocusEvent event) {
                // A key let go of after the focus went, as Alt is after Alt+Tab, is let go of
                // where the desktop never hears it. It would stay held there, and turn every key
                // typed afterwards into a shortcut.
                releaseKeys();
                repaint();
            }
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
                X11KeysymUtil.of(event.getKeyCode(), event.getKeyChar()).ifPresent(keysym -> {
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

    /// Draws the desktop at `scale`, to fit when it is 0, or at the size of the room when it is
    /// less than 0.
    void zoom(double scale) {
        if (scale == zoom) return;
        zoom = scale;
        revalidate();
        repaint();
        sizeSettled.restart();
    }

    /// Whether the desktop shown said no to taking the room's size.
    Val<Boolean> keepsItsSize() { return keepsItsSize; }

    /// The size the desktop shown has now, such as "1024 × 640"; empty while none is shown.
    Val<String> desktopSize() { return desktopSize; }

    private void sizeIs(RfbConnection desktop) {
        desktopSize.set(desktop.screen().getWidth() + " × " + desktop.screen().getHeight());
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

    /// Fitted or at the room's size, small, so it never pushes the layout; zoomed, the desktop's
    /// size at that scale.
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

    /// Fitted or at the room's size, it follows the pane's size exactly. Zoomed, it does too while
    /// it is smaller than the pane, so it stays centred rather than stuck in a corner.
    @Override public boolean getScrollableTracksViewportWidth() {
        return zoom <= 0 || (getParent() instanceof JViewport viewport && viewport.getWidth() > getPreferredSize().width);
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return zoom <= 0 || (getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height);
    }

    /// Shows `desktop`, or, when empty, nothing. Showing the one already shown changes nothing.
    /// The desktop shown before gets its own size back.
    void show(Optional<Desktop> desktop) {
        if (desktop.equals(shown) && (connection.isPresent() || desktop.isEmpty())) return;
        releaseKeys();
        if (connection.isPresent() && shown.isPresent()) letGo(connection.get(), shown.get());
        connection = Optional.empty();
        shown = desktop;
        asked = Optional.empty();
        keepsItsSize.set(false);
        desktopSize.set("");
        message = desktop.isEmpty() ? "The desktop shows here while the genie is awake." : "Connecting to the desktop…";
        repaint();
        desktop.ifPresent(it -> Thread.ofVirtual().name("desktop connect").start(() -> connect(it)));
    }

    /// Lets go, on the desktop shown, of every key still held there.
    private void releaseKeys() {
        pressed.values().forEach(keysym -> connection.ifPresent(desktop -> desktop.key(keysym, false)));
        pressed.clear();
    }

    /// Lets go of the desktop shown, which gets its own size back. The thread doing that, for
    /// whoever has to wait for it, such as an application that is about to end.
    Optional<Thread> letGo() {
        show(Optional.empty());
        return lettingGo;
    }

    /// Closes `old` once `desktop` has its own size back, or once it had the time to. wayvnc
    /// finishes a change of size only while a viewer is connected, so the connection stays until
    /// then.
    private void letGo(RfbConnection old, Desktop desktop) {
        lettingGo = Optional.of(Thread.ofVirtual().name("desktop let go").start(() -> {
            long until = System.currentTimeMillis() + GIVING_BACK_MILLIS;
            try {
                if (!hasSize(old, desktop.ownWidth(), desktop.ownHeight())) {
                    old.askForSize(desktop.ownWidth(), desktop.ownHeight());
                    while (!hasSize(old, desktop.ownWidth(), desktop.ownHeight()) && System.currentTimeMillis() < until)
                        Thread.sleep(50);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                old.close();
            }
        }));
    }

    private static boolean hasSize(RfbConnection desktop, int width, int height) {
        return desktop.screen().getWidth() == width && desktop.screen().getHeight() == height;
    }

    /// Asks the desktop for the size it should have now, unless it has it, or was asked already.
    private void sizeTheDesktop() {
        if (connection.isEmpty() || shown.isEmpty()) return;
        Dimension wanted = wantedSize(shown.get());
        if (hasSize(connection.get(), wanted.width, wanted.height) || asked.equals(Optional.of(wanted))) return;
        asked = Optional.of(wanted);
        connection.get().askForSize(wanted.width, wanted.height);
    }

    /// At the room's size: the room, in the screen's own pixels, and at least the smallest size.
    /// Otherwise, and for a desktop that keeps its size: its own size.
    private Dimension wantedSize(Desktop desktop) {
        if (zoom >= 0 || keepsItsSize.get() || getWidth() <= 0 || getHeight() <= 0 || getGraphicsConfiguration() == null)
            return new Dimension(desktop.ownWidth(), desktop.ownHeight());
        double pixels = getGraphicsConfiguration().getDefaultTransform().getScaleX();
        return new Dimension(Math.max(SMALLEST_WIDTH, (int) Math.floor(getWidth() * pixels)),
                             Math.max(SMALLEST_HEIGHT, (int) Math.floor(getHeight() * pixels)));
    }

    private void connect(Desktop desktop) {
        try {
            RfbConnection opened = RfbConnection.open(desktop.socket(), new RfbConnection.Listener() {
                @Override public void resized(int width, int height) {
                    SwingUtilities.invokeLater(() -> {
                        connection.filter(it -> shown.equals(Optional.of(desktop))).ifPresent(DesktopScreen.this::sizeIs);
                        revalidate();
                        repaint();
                    });
                }
                @Override public void painted(int x, int y, int width, int height) { repaint(); }
                @Override public void keptItsSize() {
                    SwingUtilities.invokeLater(() -> {
                        if (shown.equals(Optional.of(desktop))) keepsItsSize.set(true);
                    });
                }
                @Override public void ended(Optional<String> reason) {
                    reason.ifPresent(why -> SwingUtilities.invokeLater(() -> {
                        if (shown.equals(Optional.of(desktop))) {
                            connection = Optional.empty();
                            shown = Optional.empty();
                            desktopSize.set("");
                            message = "The desktop went away: " + why;
                            repaint();
                        }
                    }));
                }
            });
            SwingUtilities.invokeLater(() -> {
                if (shown.equals(Optional.of(desktop))) {
                    connection = Optional.of(opened);
                    sizeIs(opened);
                } else {
                    opened.close();
                }
                revalidate();
                repaint();
                sizeSettled.restart();
            });
        } catch (IOException failed) {
            SwingUtilities.invokeLater(() -> {
                if (!shown.equals(Optional.of(desktop))) return;
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
