package dev.gui.view;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.gui.model.Genie;

import swingtree.style.SvgIcon;

/// The welcome's picture, painted at a moment of its play: the lamp lights, its flame turns to
/// pixels, bursts, and a genie takes form from it, a new one each time, as any new genie is. The
/// genie is the flame from then on: its tail is a flame on the wick, it glows, and it lives on,
/// waving, thinking and working by turns. Clicked, it vanishes in a poof, and the play begins
/// again with another genie.
///
/// The lamp is drawn smooth and the genie in pixels; the flame passes from one to the other. Its
/// pixels grow until they are the genie's, on the genie's own grid, and as they grow, the flame
/// and its light turn from the lamp's amber to the genie's colour. So the flame and the genie are
/// made of the same pixels, in the same colour.
///
/// Everything follows from the moment, so a moment is painted the same way whenever it is painted.
final class WelcomeScene {

    private WelcomeScene() {}

    /*
     *  A play, in seconds since it began:
     *
     *      0    – 1.0    the lamp fades in, dark and cold, with a wisp of smoke
     *      1.2  – 3.6    the flame catches on the wick, slowly; its light grows with it
     *      3.6  – 4.6    the flame burns calmly
     *      4.6  – 6.0    the flame turns to pixels, which grow to the genie's size as it grows;
     *                    until 6.2, it, its light and the lamp's shine turn to the genie's colour
     *      6.1  – 6.4    the flame draws itself in
     *      6.4  – 6.9    it bursts, and sparks fly
     *      6.7  – 7.7    the genie takes form from the flame, from its tail up
     *      7.8  –        the genie lives: it waves first, then thinks, works, rests and waves
     *                    by turns, floats, and blinks now and then
     *
     *  The light comes from the flame, and from the genie once it is there: its middle is theirs,
     *  and it is as large as they are, flicker and all.
     *
     *  A poof takes 1.1 seconds, and the next play begins after it, with the lamp already there.
     */
    private static final double SHOWN = 1.0;
    private static final double LIT = 1.2;
    private static final double BURNING = 3.6;
    private static final double PIXELATING = 4.6;
    private static final double PIXELATED = 6.0;
    private static final double TINTED = 6.2;
    private static final double GATHERING = 6.1;
    private static final double BURST = 6.4;
    private static final double FORMING = 6.7;
    static final double FORMED = 7.8;
    private static final double POOF = 1.1;

    /// One play of the welcome, on the clock of the page, which goes on from play to play.
    ///
    /// @param pip    the genie that takes form
    /// @param began  when the play began
    /// @param gone   the genie of the play before, which vanished in a poof; for the first play,
    ///               the same as `pip`
    /// @param poofed when it vanished, or a negative number for the first play
    record Play(GenieSvgUtil.Appearance pip, double began, GenieSvgUtil.Appearance gone, double poofed) {

        /// The first play, beginning at `clock`, with a genie of its own.
        static Play first(double clock) {
            GenieSvgUtil.Appearance pip = GenieSvgUtil.appearanceOf(UUID.randomUUID());
            return new Play(pip, clock, pip, -1);
        }

        /// The genie vanishes at `clock`, and the next play begins after the poof, with another.
        Play poof(double clock) {
            return new Play(GenieSvgUtil.appearanceOf(UUID.randomUUID()), clock + POOF, pip, clock);
        }

        /// Whether the genie has taken form at `clock`, and so can be clicked away.
        boolean formed(double clock) {
            return clock >= began + FORMED;
        }
    }

    /// Where things are in a picture of a size: the size of the genie's pixels, the lamp, its
    /// wick, and the genie's top left corner.
    private record Stage(int pixel, int lampSize, double lampX, double lampY, double wickX, double wickY, int pipX, int pipY) {

        /// The lamp in the middle of a picture `width` by `height`, twenty-five of the genie's
        /// pixels wide, and the genie above it, its tail, a flame, ending on the wick: the
        /// bottom of its column 12, row 17.
        static Stage of(int width, int height) {
            int pixel = Math.max(2, height / 50);
            int lampSize = 25 * pixel;
            double lampX = width / 2.0 - 34.0 / 64 * lampSize;
            double lampY = height - 56.0 / 64 * lampSize;
            double wickX = lampX + LampSvgUtil.WICK_X / 64 * lampSize;
            double wickY = lampY + LampSvgUtil.WICK_Y / 64 * lampSize;
            return new Stage(pixel, lampSize, lampX, lampY, wickX, wickY,
                             (int) Math.round(wickX - 12.5 * pixel), (int) Math.round(wickY - 18 * pixel));
        }

        /// The middle of the genie, where its light comes from.
        Point2D middle() {
            return new Point2D.Double(pipX + 10.0 * pixel, pipY + 9.0 * pixel);
        }
    }

    /// The colours of a flame, from its dark edge to its brightest heart.
    private record Hues(Color ember, Color flame, Color hot, Color brightest) {

        /// The lamp's own flame, in amber.
        static final Hues AMBER = new Hues(new Color(0xf0, 0x8a, 0x30), new Color(0xf0, 0xa9, 0x40),
                                           new Color(0xff, 0xd1, 0x66), new Color(0xff, 0xf1, 0xc2));

        /// A flame in a genie's colour: darker at its edge, lighter at its heart.
        static Hues of(GenieSvgUtil.Appearance genie) {
            Color body = genie.colour();
            return new Hues(mix(body, Color.BLACK, 0.3), body, mix(body, Color.WHITE, 0.45), mix(body, Color.WHITE, 0.8));
        }

        /// From these colours to `other`'s, `progress` of the way.
        Hues toward(Hues other, double progress) {
            return new Hues(mix(ember, other.ember, progress), mix(flame, other.flame, progress),
                            mix(hot, other.hot, progress), mix(brightest, other.brightest, progress));
        }

        /// One of the four by its number, from the edge, 0, to the heart, 3.
        Color get(int which) {
            return switch (which) {
                case 0 -> ember;
                case 1 -> flame;
                case 2 -> hot;
                default -> brightest;
            };
        }
    }

    private static final Color SMOKE = new Color(0x6f, 0x64, 0x79);
    private static final Color ASH = new Color(0xa8, 0x9c, 0x8a);

    /// What the genie does once it has taken form, one act after the other, over and over: how it
    /// poses, and for how many seconds. It waves first; then it does something else each time,
    /// in an order chosen once, by chance.
    private record Act(GenieSvgUtil.Pose pose, double seconds) {}
    private static final List<Act> ACTS = acts();

    /// The genie's tail in the welcome, from its row 15 down: a flame that narrows onto the wick,
    /// in one of three shapes at a time, so that it flickers. Numbers are the flame's colours, from
    /// its edge, 0, to its heart, 3.
    private static final List<List<String>> TAIL = List.of(
            List.of(".........BB221......", "..........12321.....", "...........121......"),
            List.of(".........BB122......", "..........2321......", "............2......."),
            List.of(".........BB212......", "...........13210....", "...........12......."));

    /// The sparks of the burst: where each flies, how fast, how long it lives, its colour, from
    /// the flame's edge, 0, to its heart, 3, and whether it is half a pixel.
    private record Spark(double angle, double speed, double life, int hue, boolean small) {}
    private static final List<Spark> SPARKS = sparks();

    /// What changes little is drawn once, into pictures kept here, and the pictures are drawn
    /// each moment instead: painting a gradient anew each moment, the welcome's light most of
    /// all, made the window slow to resize. Pictures that hold a colour are kept for a few
    /// colours at a time, since the colour changes each moment while the flame turns the genie's.
    ///
    /// The lamp, cold, smoking or not, and dark or not, for each size.
    private record Lamp(int size, boolean smoking, boolean dark) {}
    private static final Map<Lamp, BufferedImage> LAMPS = new ConcurrentHashMap<>();
    /// The flame's shine on the lamp, for each size and colour of flame.
    private record Shine(int size, boolean smoking, Color hot, Color flame) {}
    private static final Map<Shine, BufferedImage> SHINES = new ConcurrentHashMap<>();
    /// A round glow of `colour`, as strong as `peak` in its middle, `middle` at 0.3 of the way
    /// out, and nothing at its edge, `GLOW` pixels wide, and stretched to whatever size it is drawn.
    private record Glow(Color colour, double peak, double middle) {}
    private static final Map<Glow, BufferedImage> GLOWS = new ConcurrentHashMap<>();
    private static final int GLOW = 128;
    private static final int COLOURS_KEPT = 32;

    /// Paints `play` as it is at `clock`, in seconds of the page, onto `g`, `width` by `height`
    /// pixels.
    static void paint(Graphics2D g, int width, int height, Play play, double clock) {
        // Pixels fill whole pixels, which smoothing their edges only makes slower to paint.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        Stage stage = Stage.of(width, height);
        if (clock < play.began()) {
            double since = clock - play.poofed();
            lamp(g, stage, play.gone(), 1, strength(FORMED) * clamp(1 - since / POOF), false, 1);
            poof(g, stage, play.gone(), since);
            return;
        }
        double time = clock - play.began();
        Hues hues = hues(play, time);
        // In a play after a poof, the lamp is there already, and the smoke is the poof's.
        lamp(g, stage, play.pip(), tinted(time), strength(time), time < LIT + 0.15, play.poofed() >= 0 ? 1 : clamp(time / SHOWN));
        flame(g, stage, hues, time);
        sparks(g, stage, hues, time);
        if (time >= FORMING) pip(g, stage, play.pip(), hues, time);
    }

    /// Whether the point `x`, `y` of a picture `width` by `height` is on the lamp or on the genie.
    static boolean hits(int width, int height, int x, int y) {
        Stage stage = Stage.of(width, height);
        double unit = stage.lampSize() / 64.0;
        return new Rectangle2D.Double(stage.lampX() + 6 * unit, stage.lampY() + 22 * unit, 56 * unit, 32 * unit).contains(x, y)
            || new Rectangle2D.Double(stage.pipX(), stage.pipY(), 20.0 * stage.pixel(), 18.0 * stage.pixel()).contains(x, y);
    }

    /// The light of the flame, and of the genie once it is there, onto `g`, whose origin is the
    /// top left of a welcome `width` pixels wide. Its picture is `stageHeight` pixels tall, its top
    /// `cut` pixels cut away, above the welcome. The light shines past the welcome, as far as `g`
    /// reaches.
    ///
    /// It is a round glow in the flame's colour, a touch wider than tall. Its middle is the
    /// flame's, and then the genie's; it is as large as they are, and grows and shrinks as they
    /// flicker. It grows bright as the flame catches, flares as it bursts, and fades out in a poof.
    static void light(Graphics2D g, int width, int stageHeight, int cut, Play play, double clock) {
        Stage stage = Stage.of(width, stageHeight);
        boolean poofing = clock < play.began();
        double time = poofing ? FORMED : clock - play.began();
        double left = poofing ? clamp(1 - (clock - play.poofed()) / POOF) : 1;
        double strength = clamp(strength(time) * left);
        if (strength <= 0) return;
        double tinted = poofing ? 1 : tinted(time);
        Hues genie = Hues.of(poofing ? play.gone() : play.pip());
        Point2D.Double source = source(stage, time);
        double size = size(stage, time);
        double x = source.getX();
        double y = source.getY() - cut;
        double down = 2.4 * size;
        double across = 1.12 * down;
        if (down <= 0) return;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double near = 0.9 * size;
        Composite before = g.getComposite();
        // It turns the genie's colour with the flame: the amber light fades out as the genie's
        // fades in. A light of a colour between them would be a picture of its own each moment,
        // and a picture drawn only once is slow to draw.
        for (int which = 0; which < 2; which++) {
            Hues hues = which == 0 ? Hues.AMBER : genie;
            double part = which == 0 ? 1 - tinted : tinted;
            if (part <= 0) continue;
            g.setComposite(AlphaComposite.SrcOver.derive((float) (strength * part)));
            glow(g, glow(hues.flame(), 0.42, 0.16), x, y, across, down);
            // Near the source, a smaller glow, hotter. It fades evenly from its middle to its edge.
            glow(g, glow(hues.hot(), 0.3, 0.3 * 0.7), x, y, near, near);
        }
        g.setComposite(before);
    }

    /// The glow `drawn` onto `g`, its middle on `x`, `y`, and `across` and `down` from there to
    /// its edge.
    private static void glow(Graphics2D g, BufferedImage drawn, double x, double y, double across, double down) {
        g.drawImage(drawn, new AffineTransform(2 * across / GLOW, 0, 0, 2 * down / GLOW, x - across, y - down), null);
    }

    private static BufferedImage glow(Color colour, double peak, double middle) {
        if (GLOWS.size() > COLOURS_KEPT) GLOWS.clear();
        return GLOWS.computeIfAbsent(new Glow(colour, peak, middle), it -> {
            BufferedImage glow = new BufferedImage(GLOW, GLOW, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D on = glow.createGraphics();
            on.setPaint(new RadialGradientPaint(GLOW / 2f, GLOW / 2f, GLOW / 2f, new float[] { 0f, 0.3f, 1f },
                    new Color[] { alpha(colour, peak), alpha(colour, middle), alpha(colour, 0) }));
            on.fillRect(0, 0, GLOW, GLOW);
            on.dispose();
            return glow;
        });
    }

    /// The colours of the flame at `time` of `play`: the lamp's amber, turning to its genie's
    /// as the flame turns to pixels.
    private static Hues hues(Play play, double time) {
        return Hues.AMBER.toward(Hues.of(play.pip()), tinted(time));
    }

    /// How far the flame has turned from the lamp's amber to its genie's colour at `time`, from
    /// 0 to 1.
    private static double tinted(double time) {
        return ease(clamp((time - PIXELATING) / (TINTED - PIXELATING)));
    }

    /// Where the light comes from at `time`: the middle of the flame, which rises as it grows,
    /// and then the middle of the genie, as it takes form from the flame and floats.
    private static Point2D.Double source(Stage stage, double time) {
        double flameY = stage.wickY() - 0.42 * flameTall(stage.pixel(), time);
        double formed = ease(clamp((time - FORMING) / (FORMED - FORMING)));
        Point2D middle = stage.middle();
        double genieX = middle.getX() + lean(time) * stage.pixel();
        double genieY = middle.getY() - risen(time) * stage.pixel();
        return new Point2D.Double(stage.wickX() + (genieX - stage.wickX()) * formed, flameY + (genieY - flameY) * formed);
    }

    /// How large the light's source is at `time`, in pixels: the flame's height, flicker and all,
    /// and then the genie's, whose tail flickers.
    private static double size(Stage stage, double time) {
        double formed = ease(clamp((time - FORMING) / (FORMED - FORMING)));
        // The genie shines as a flame twice the lamp's would.
        double genie = 12 * stage.pixel() * (1 + 0.05 * Math.sin(17 * time) + 0.03 * Math.sin(31 * time + 0.7)
                                             + 0.06 * (chance((long) (time * 8), 0, 0) - 0.5));
        double flame = flameTall(stage.pixel(), time);
        return flame + (genie - flame) * formed;
    }

    /// How high the flame stands at `time`, in pixels, flicker and all: small as it catches,
    /// steady, growing as it turns to pixels, drawn in, and then bursting.
    private static double flameTall(int pixel, double time) {
        if (time < LIT) return 0;
        double size = time < BURNING ? 0.12 + 0.88 * smooth(clamp((time - LIT) / (BURNING - LIT)))
                    : time < PIXELATING ? 1
                    : time < GATHERING ? 1 + 0.7 * ease(clamp((time - PIXELATING) / (GATHERING - PIXELATING)))
                    : time < BURST ? 1.7 - 0.3 * ease(clamp((time - GATHERING) / (BURST - GATHERING)))
                    : 1.4 + 3.0 * ease(clamp((time - BURST) / 0.35));
        double flicker = 1 + (time < PIXELATING ? 0.06 : 0.12) * Math.sin(17 * time) + 0.05 * Math.sin(31 * time + 0.7);
        return 6 * pixel * size * flicker;
    }

    /// Whether the genie floats a pixel higher at `time`: it rises and sinks again, every 0.8
    /// seconds, once it has taken form.
    private static int risen(double time) {
        return time >= FORMED && (int) ((time - FORMED) / 0.8) % 2 == 1 ? 1 : 0;
    }

    /// How many pixels the genie leans to the right at `time`, or to the left when below 0: it
    /// sways slowly, once every seven seconds, a pixel each way, resting upright in between.
    private static int lean(double time) {
        return time < FORMED ? 0 : (int) Math.round(1.3 * Math.sin(2 * Math.PI * (time - FORMED) / 7.0));
    }

    /// How strong the flame's light is at `time`: nothing before it catches, about 0.9 while it
    /// burns, more as it bursts, and breathing a little once the genie is there.
    private static double strength(double time) {
        double caught = ease(clamp((time - LIT) / (BURNING - LIT)));
        double breath = time < FORMED ? 1 : 1 + 0.08 * Math.sin(2.1 * (time - FORMED));
        return (caught * 0.9 + flare(time) * 0.9) * breath;
    }

    /// The flare of the burst, rising and falling around a quarter second after it.
    private static double flare(double time) {
        return time < BURST ? 0 : Math.exp(-Math.pow((time - BURST - 0.25) / 0.3, 2));
    }

    /// The lamp, cold, smoking or not, `shown` from 0, not at all, to 1, and lit by its flame:
    /// dark where no flame burns, and shining in the flame's colour where its light falls, on
    /// top and towards the wick, as strong as `light`. The flame is `tinted` of the way from
    /// amber to the colour of `genie`.
    private static void lamp(Graphics2D g, Stage stage, GenieSvgUtil.Appearance genie, double tinted, double light, boolean smoking,
                             double shown) {
        int size = stage.lampSize();
        double lit = clamp(light);
        int x = (int) Math.round(stage.lampX());
        int y = (int) Math.round(stage.lampY());
        Composite before = g.getComposite();
        // The lamp darkens less the more it is lit: the lamp, and its dark self over it, fading as
        // the flame's light grows. It fades in only before the flame catches, all dark.
        if (lit > 0) g.drawImage(lamp(new Lamp(size, smoking, false)), x, y, null);
        g.setComposite(AlphaComposite.SrcOver.derive((float) (shown * (1 - lit))));
        g.drawImage(lamp(new Lamp(size, smoking, true)), x, y, null);
        // Its shine turns the genie's colour as the light does.
        for (int which = 0; which < 2; which++) {
            Hues hues = which == 0 ? Hues.AMBER : Hues.of(genie);
            double part = lit * (which == 0 ? 1 - tinted : tinted);
            if (part <= 0) continue;
            g.setComposite(AlphaComposite.SrcOver.derive((float) part));
            if (SHINES.size() > COLOURS_KEPT) SHINES.clear();
            g.drawImage(SHINES.computeIfAbsent(new Shine(size, smoking, hues.hot(), hues.flame()), it -> {
                BufferedImage shine = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D on = shine.createGraphics();
                on.drawImage(lamp(new Lamp(size, smoking, false)), 0, 0, null);
                // Only over the lamp itself.
                on.setComposite(AlphaComposite.SrcIn);
                double wickX = LampSvgUtil.WICK_X / 64 * size;
                double wickY = LampSvgUtil.WICK_Y / 64 * size;
                on.setPaint(new RadialGradientPaint(new Point2D.Double(wickX, wickY), (float) (0.6 * size), new float[] { 0f, 0.45f, 1f },
                        new Color[] { alpha(hues.hot(), 0.38), alpha(hues.flame(), 0.12), alpha(hues.flame(), 0) }));
                on.fillRect(0, 0, size, size);
                on.dispose();
                return shine;
            }), x, y, null);
        }
        g.setComposite(before);
    }

    private static BufferedImage lamp(Lamp lamp) {
        return LAMPS.computeIfAbsent(lamp, it -> {
            BufferedImage drawn = new BufferedImage(it.size(), it.size(), BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D on = drawn.createGraphics();
            SvgIcon.of(it.smoking() ? LampSvgUtil.lamp(Genie.Phase.ASLEEP) : LampSvgUtil.cold()).withIconSize(it.size(), it.size()).paintIcon(null, on, 0, 0);
            if (it.dark()) {
                on.setComposite(AlphaComposite.SrcAtop);
                on.setColor(alpha(Color.BLACK, 0.45));
                on.fillRect(0, 0, it.size(), it.size());
            }
            on.dispose();
            return drawn;
        });
    }

    /// The flame on the wick: smooth as it catches, then in pixels that grow to the genie's,
    /// then bursting, and gone once the genie has taken form.
    private static void flame(Graphics2D g, Stage stage, Hues hues, double time) {
        if (time < LIT || time >= FORMED) return;
        int pixel = stage.pixel();
        double tall = flameTall(pixel, time);
        // Fuller as it turns to pixels, so that it keeps its body in them.
        double wide = tall * (0.55 + 0.2 * ease(clamp((time - PIXELATING) / (PIXELATED - PIXELATING))));
        double sway = Math.sin(7 * time) * 0.06 * wide;
        double x = stage.wickX();
        double y = stage.wickY();
        List<Path2D> layers = List.of(
                drop(x + sway, y, wide, tall),
                drop(x + sway * 0.7, y - tall * 0.04, wide * 0.7, tall * 0.74),
                drop(x + sway * 0.4, y - tall * 0.08, wide * 0.42, tall * 0.48));
        List<Color> colours = List.of(hues.ember(), hues.flame(), time < BURST ? hues.hot() : hues.brightest());
        Composite before = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - clamp((time - FORMING) / (FORMED - 0.1 - FORMING)))));
        int cell = time < PIXELATING ? 1 : (int) Math.round(1 + (pixel - 1) * ease(clamp((time - PIXELATING) / (PIXELATED - PIXELATING))));
        // The pixels at its edge come and go, about ten times a second.
        long flickers = (long) (time * 10);
        if (cell < 2) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int layer = 0; layer < layers.size(); layer++) {
                g.setColor(colours.get(layer));
                g.fill(layers.get(layer));
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        } else {
            // Cells on the genie's own grid, so the flame's pixels become the genie's where they meet.
            Rectangle2D bounds = layers.getFirst().getBounds2D();
            int fromX = stage.pipX() + (int) Math.floor((bounds.getMinX() - stage.pipX()) / cell) * cell;
            int fromY = stage.pipY() + (int) Math.floor((bounds.getMinY() - stage.pipY()) / cell) * cell;
            for (int cellY = fromY; cellY < bounds.getMaxY(); cellY += cell)
                for (int cellX = fromX; cellX < bounds.getMaxX(); cellX += cell)
                    for (int layer = layers.size() - 1; layer >= 0; layer--)
                        if (layers.get(layer).contains(cellX + cell / 2.0, cellY + cell / 2.0)) {
                            if (layer == 0 && chance(flickers, cellX, cellY) < 0.08) break;
                            g.setColor(colours.get(layer));
                            g.fillRect(cellX, cellY, cell, cell);
                            break;
                        }
        }
        g.setComposite(before);
    }

    /// A drop of flame standing on `x`, `y`: round below, pointed above.
    private static Path2D drop(double x, double y, double wide, double tall) {
        Path2D drop = new Path2D.Double();
        drop.moveTo(x, y - tall);
        drop.curveTo(x + 0.12 * wide, y - 0.7 * tall, x + 0.5 * wide, y - 0.55 * tall, x + 0.5 * wide, y - 0.28 * tall);
        drop.curveTo(x + 0.5 * wide, y - 0.08 * tall, x + 0.28 * wide, y, x, y);
        drop.curveTo(x - 0.28 * wide, y, x - 0.5 * wide, y - 0.08 * tall, x - 0.5 * wide, y - 0.28 * tall);
        drop.curveTo(x - 0.5 * wide, y - 0.55 * tall, x - 0.12 * wide, y - 0.7 * tall, x, y - tall);
        drop.closePath();
        return drop;
    }

    /// The sparks of the burst, square like the genie's pixels, flying out and up, fading as
    /// they go.
    private static void sparks(Graphics2D g, Stage stage, Hues hues, double time) {
        if (time < BURST) return;
        double since = time - BURST;
        int pixel = stage.pixel();
        // On the genie's grid, half a pixel at a time, as pixels move.
        int step = Math.max(1, pixel / 2);
        for (Spark spark : SPARKS) {
            if (since > spark.life()) continue;
            double flown = spark.speed() * pixel * since;
            double x = stage.wickX() + Math.cos(spark.angle()) * flown;
            double y = stage.wickY() - 4 * pixel + Math.sin(spark.angle()) * flown - 2 * pixel * since * since;
            g.setColor(alpha(hues.get(spark.hue()), 1 - since / spark.life()));
            g.fillRect(stage.pipX() + (int) Math.round((x - stage.pipX()) / step) * step,
                       stage.pipY() + (int) Math.round((y - stage.pipY()) / step) * step,
                       spark.small() ? step : pixel, spark.small() ? step : pixel);
        }
    }

    /// The genie, taking form from the flame pixel by pixel from its tail up, and from then on
    /// the flame itself: its tail is a flame on the wick, the pixels above it flicker in the
    /// flame's colours, and it glows.
    private static void pip(Graphics2D g, Stage stage, GenieSvgUtil.Appearance pip, Hues hues, double time) {
        int pixel = stage.pixel();
        double lived = time - FORMED;
        Act act = ACTS.get(actAt(lived));
        double into = lived - startOf(lived);
        GenieSvgUtil.Pose pose = time < FORMED ? GenieSvgUtil.Pose.AWAKE : act.pose();
        int frame = switch (pose) {
            case WAVING -> (int) (into / 0.28) % 2;
            case WORKING -> (int) (into / 0.3) % 2;
            case THINKING -> GenieSvgUtil.frameAt(pose, (into % 1.2) / 1.2);
            default -> 0;
        };
        char[][] pixels = GenieSvgUtil.pixels(pip, pose, frame);
        Map<Character, Color> colours = GenieSvgUtil.colours(pip);
        // It blinks now and then, looking at the user: the upper half of its eyes closes.
        if ((pose == GenieSvgUtil.Pose.AWAKE || pose == GenieSvgUtil.Pose.WAVING) && time >= FORMED && lived % 3.7 > 3.55)
            for (int column = 0; column < pixels[7].length; column++)
                if (pixels[7][column] == 'E') pixels[7][column] = 'B';
        // The flame's pixels change about eight times a second.
        long flickers = (long) (time * 8);
        List<String> tail = TAIL.get((int) (chance(flickers, 0, 0) * TAIL.size()));
        for (int row = 15; row < pixels.length; row++)
            pixels[row] = (row - 15 < tail.size() ? tail.get(row - 15) : ".".repeat(pixels[row].length)).toCharArray();
        // Once formed, it floats: its body rises a pixel and sinks again, while its tail stays on
        // the wick and stretches, its top row drawn twice.
        int risen = risen(time);
        // It sways too: its body leans, while its tail, a flame, stays on the wick.
        int lean = lean(time);
        for (int row = 0; row < pixels.length; row++)
            for (int column = 0; column < pixels[row].length; column++) {
                char letter = pixels[row][column];
                if (letter == '.') continue;
                double appears = FORMING + 0.55 * (0.65 * (1 - row / 19.0) + 0.35 * chance(-1, row, column));
                if (time < appears) continue;
                Color colour = Character.isDigit(letter) ? hues.get(letter - '0') : colours.getOrDefault(letter, pip.colour());
                // Above the flame of its tail, its body burns here and there.
                double burning = row == 14 ? 0.45 : row == 13 ? 0.15 : 0;
                double flicker = chance(flickers, row, column);
                // The chance below `burning` picks its flame's colour too: mostly the hot one.
                if (letter == 'B' && flicker < burning) colour = hues.get(flicker < burning / 2 ? 2 : (int) (4 * flicker / burning) % 4);
                // A pixel just formed fades in, in the flame's colour, and then turns its own.
                colour = alpha(mix(hues.hot(), colour, clamp((time - appears) / 0.35)), clamp((time - appears) / 0.15));
                g.setColor(colour);
                int x = stage.pipX() + (column + (row < 15 ? lean : 0)) * pixel;
                g.fillRect(x, stage.pipY() + (row < 15 ? row - risen : row) * pixel, pixel, pixel);
                if (row == 15 && risen == 1) g.fillRect(x, stage.pipY() + 14 * pixel, pixel, pixel);
            }
    }

    /// The genie `gone` vanishing, `since` seconds ago: a flash, its pixels flying apart and
    /// fading, and a puff of smoke that swells, drifts up and clears.
    private static void poof(Graphics2D g, Stage stage, GenieSvgUtil.Appearance gone, double since) {
        int pixel = stage.pixel();
        Hues hues = Hues.of(gone);
        Point2D middle = stage.middle();
        double flash = Math.exp(-Math.pow(since / 0.12, 2));
        if (flash > 0.01) {
            double reach = 14.0 * pixel;
            Composite before = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive((float) flash));
            glow(g, glow(hues.brightest(), 0.85, 0.85 * 0.7), middle.getX(), middle.getY(), reach, reach);
            g.setComposite(before);
        }
        Random chance = new Random(5);
        double clearing = clamp(1 - since / POOF);
        for (int puff = 0; puff < 24; puff++) {
            double angle = chance.nextDouble() * 2 * Math.PI;
            double out = (2 + 5 * chance.nextDouble()) * pixel * (0.4 + ease(clamp(since / 0.6)));
            int size = pixel * (1 + (int) (3 * clamp(since / (0.5 + 0.4 * chance.nextDouble()))));
            double x = middle.getX() + Math.cos(angle) * out;
            double y = middle.getY() + Math.sin(angle) * out * 0.8 - 3 * pixel * since;
            g.setColor(alpha(chance.nextBoolean() ? SMOKE : ASH, 0.75 * clearing));
            g.fillRect((int) Math.round(x - size / 2.0), (int) Math.round(y - size / 2.0), size, size);
        }
        double flying = clamp(1 - since / 0.8);
        if (flying <= 0) return;
        char[][] pixels = GenieSvgUtil.pixels(gone, GenieSvgUtil.Pose.DIZZY, 0);
        Map<Character, Color> colours = GenieSvgUtil.colours(gone);
        for (int row = 0; row < 15; row++)
            for (int column = 0; column < pixels[row].length; column++) {
                char letter = pixels[row][column];
                if (letter == '.') continue;
                double dx = column - 9.5 + 2 * (chance(-2, row, column) - 0.5);
                double dy = row - 9 + 2 * (chance(-3, row, column) - 0.5);
                double speed = (8 + 10 * chance(-4, row, column)) * pixel / Math.max(1, Math.hypot(dx, dy));
                double x = stage.pipX() + column * pixel + dx * speed * since;
                double y = stage.pipY() + row * pixel + dy * speed * since + 6 * pixel * since * since;
                g.setColor(alpha(colours.getOrDefault(letter, gone.colour()), flying));
                g.fillRect((int) Math.round(x), (int) Math.round(y), pixel, pixel);
            }
    }

    /// The number of the act the genie is in, `lived` seconds after it took form. The acts go
    /// round once they were all played.
    private static int actAt(double lived) {
        double into = lived - roundStart(lived);
        for (int act = 0; act < ACTS.size(); act++) {
            if (into < ACTS.get(act).seconds()) return act;
            into -= ACTS.get(act).seconds();
        }
        return ACTS.size() - 1;
    }

    /// When the act the genie is in, `lived` seconds after it took form, began.
    private static double startOf(double lived) {
        double start = roundStart(lived);
        for (int act = 0; act < actAt(lived); act++) start += ACTS.get(act).seconds();
        return start;
    }

    /// When the round of acts the genie is in began.
    private static double roundStart(double lived) {
        double round = ACTS.stream().mapToDouble(Act::seconds).sum();
        return Math.floor(Math.max(0, lived) / round) * round;
    }

    private static List<Act> acts() {
        List<Act> kinds = List.of(new Act(GenieSvgUtil.Pose.THINKING, 3.6), new Act(GenieSvgUtil.Pose.WORKING, 2.4),
                                  new Act(GenieSvgUtil.Pose.AWAKE, 2.2), new Act(GenieSvgUtil.Pose.WAVING, 2.0));
        List<Act> acts = new ArrayList<>(List.of(new Act(GenieSvgUtil.Pose.WAVING, 2.8), new Act(GenieSvgUtil.Pose.AWAKE, 1.8)));
        Random chance = new Random(7);
        while (acts.size() < 24) {
            Act next = kinds.get(chance.nextInt(kinds.size()));
            if (next.pose() != acts.getLast().pose()) acts.add(next);
        }
        return List.copyOf(acts);
    }

    private static List<Spark> sparks() {
        Random chance = new Random(11);
        List<Spark> sparks = new ArrayList<>();
        for (int spark = 0; spark < 40; spark++)
            sparks.add(new Spark(-Math.PI * (0.05 + 0.9 * chance.nextDouble()) + (chance.nextDouble() < 0.2 ? Math.PI * 0.15 : 0),
                                 8 + 12 * chance.nextDouble(), 0.8 + 0.9 * chance.nextDouble(),
                                 chance.nextInt(4), chance.nextDouble() < 0.3));
        return List.copyOf(sparks);
    }

    /// A number from 0 to 1, the same for the same `a`, `b` and `c`, and unlike that for any
    /// neighbouring ones, as a pixel's chance must be: Java's `Random`, seeded with neighbouring
    /// numbers, starts with nearly the same number, and whole rows of pixels would do the same.
    private static double chance(long a, long b, long c) {
        long mixed = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + c * 0x165667B19E3779F9L;
        mixed = (mixed ^ (mixed >>> 33)) * 0xFF51AFD7ED558CCDL;
        mixed = (mixed ^ (mixed >>> 33)) * 0xC4CEB9FE1A85EC53L;
        mixed ^= mixed >>> 33;
        return (mixed >>> 11) / (double) (1L << 53);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /// Fast at first, slowing to a stop.
    private static double ease(double progress) {
        return 1 - Math.pow(1 - progress, 3);
    }

    /// Slow at first, faster, and slow again at the end.
    private static double smooth(double progress) {
        return progress * progress * (3 - 2 * progress);
    }

    private static Color alpha(Color colour, double alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), (int) Math.round(255 * clamp(alpha)));
    }

    /// `from`, turning into `to` as `progress` goes from 0 to 1.
    private static Color mix(Color from, Color to, double progress) {
        double part = clamp(progress);
        return new Color((int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * part),
                         (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * part),
                         (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * part));
    }
}
