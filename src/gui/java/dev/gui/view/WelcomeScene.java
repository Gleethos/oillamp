package dev.gui.view;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
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
     *      1.2  – 2.8    the flame catches on the wick; its light grows, and lights the lamp
     *      2.8  – 3.6    the flame burns calmly
     *      3.6  – 5.0    the flame turns to pixels, which grow to the genie's size as it grows;
     *                    until 5.2, it, its light and the lamp's shine turn to the genie's colour
     *      5.1  – 5.4    the flame draws itself in
     *      5.4  – 5.9    it bursts, and sparks fly
     *      5.7  – 6.7    the genie takes form from the flame, from its tail up
     *      6.8  –        the genie lives: it waves first, then thinks, works, rests and waves
     *                    by turns, floats, and blinks now and then
     *
     *  A poof takes 1.1 seconds, and the next play begins after it, with the lamp already there.
     */
    private static final double SHOWN = 1.0;
    private static final double LIT = 1.2;
    private static final double BURNING = 2.8;
    private static final double PIXELATING = 3.6;
    private static final double PIXELATED = 5.0;
    private static final double TINTED = 5.2;
    private static final double GATHERING = 5.1;
    private static final double BURST = 5.4;
    private static final double FORMING = 5.7;
    static final double FORMED = 6.8;
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
            int pixel = Math.max(2, height / 42);
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

    /// The lamp, cold and smoking, and cold without smoke, drawn once for each size.
    private static final Map<Integer, BufferedImage> SMOKING = new ConcurrentHashMap<>();
    private static final Map<Integer, BufferedImage> COLD = new ConcurrentHashMap<>();

    /// Paints `play` as it is at `clock`, in seconds of the page, onto `g`, `width` by `height`
    /// pixels.
    static void paint(Graphics2D g, int width, int height, Play play, double clock) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Stage stage = Stage.of(width, height);
        if (clock < play.began()) {
            double since = clock - play.poofed();
            Hues gone = Hues.of(play.gone());
            double left = clamp(1 - since / POOF);
            glow(g, width, height, stage, gone, FORMED, left);
            lamp(g, stage, gone, light(FORMED) * left, false, 1);
            poof(g, stage, play.gone(), since);
            return;
        }
        double time = clock - play.began();
        Hues hues = Hues.AMBER.toward(Hues.of(play.pip()), ease(clamp((time - PIXELATING) / (TINTED - PIXELATING))));
        glow(g, width, height, stage, hues, time, 1);
        // In a play after a poof, the lamp is there already, and the smoke is the poof's.
        lamp(g, stage, hues, light(time), time < LIT + 0.15, play.poofed() >= 0 ? 1 : clamp(time / SHOWN));
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

    /// The light of the flame: a soft glow in the flame's colour that grows as the flame catches,
    /// flares when it bursts, and breathes once the genie is there.
    ///
    /// @param left how much of it is left, from 0 to 1: less as a poof clears
    private static void glow(Graphics2D g, int width, int height, Stage stage, Hues hues, double time, double left) {
        double caught = ease(clamp((time - LIT) / (BURNING - LIT)));
        double flare = flare(time);
        double strength = clamp(light(time) * clamp(left));
        if (strength <= 0) return;
        // An ellipse that has faded to nothing where the picture ends, so it shows no edge.
        double x = stage.wickX();
        double y = stage.wickY() - 8 * stage.pixel();
        double reach = Math.min(1, 0.35 + 0.65 * caught + 0.25 * flare);
        double across = Math.min(x, width - x) * reach;
        double down = Math.min(y, height - y) * reach;
        if (across <= 0 || down <= 0) return;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 1f, new Point2D.Double(0, 0), new float[] { 0f, 0.3f, 1f },
                new Color[] { alpha(hues.flame(), 0.42 * strength), alpha(hues.flame(), 0.16 * strength), alpha(hues.flame(), 0) },
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB,
                new AffineTransform(across, 0, 0, down, x, y)));
        g.fillRect(0, 0, width, height);
        float near = (float) (Math.min(across, down) * 0.35);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), near, new float[] { 0f, 1f },
                new Color[] { alpha(hues.hot(), 0.3 * strength), alpha(hues.hot(), 0) }));
        g.fillRect(0, 0, width, height);
    }

    /// How strong the flame's light is at `time`: nothing before it catches, about 0.9 while it
    /// burns, more as it bursts, and breathing a little once the genie is there.
    private static double light(double time) {
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
    /// top and towards the wick, as strong as `light`.
    private static void lamp(Graphics2D g, Stage stage, Hues hues, double light, boolean smoking, double shown) {
        int size = stage.lampSize();
        BufferedImage drawn = (smoking ? SMOKING : COLD).computeIfAbsent(size, it -> {
            BufferedImage lamp = new BufferedImage(it, it, BufferedImage.TYPE_INT_ARGB);
            Graphics2D on = lamp.createGraphics();
            SvgIcon.of(smoking ? LampSvgUtil.lamp(Genie.Phase.ASLEEP) : LampSvgUtil.cold()).withIconSize(it, it).paintIcon(null, on, 0, 0);
            on.dispose();
            return lamp;
        });
        BufferedImage lit = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D on = lit.createGraphics();
        on.drawImage(drawn, 0, 0, null);
        // Only over the lamp itself.
        on.setComposite(AlphaComposite.SrcAtop);
        on.setColor(alpha(Color.BLACK, 0.45 * (1 - clamp(light))));
        on.fillRect(0, 0, size, size);
        double wickX = LampSvgUtil.WICK_X / 64 * size;
        double wickY = LampSvgUtil.WICK_Y / 64 * size;
        on.setPaint(new RadialGradientPaint(new Point2D.Double(wickX, wickY), (float) (0.6 * size), new float[] { 0f, 0.45f, 1f },
                new Color[] { alpha(hues.hot(), 0.38 * clamp(light)), alpha(hues.flame(), 0.12 * clamp(light)), alpha(hues.flame(), 0) }));
        on.fillRect(0, 0, size, size);
        on.dispose();
        Composite before = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) shown));
        g.drawImage(lit, (int) Math.round(stage.lampX()), (int) Math.round(stage.lampY()), null);
        g.setComposite(before);
    }

    /// The flame on the wick: smooth as it catches, then in pixels that grow to the genie's,
    /// then bursting, and gone once the genie has taken form.
    private static void flame(Graphics2D g, Stage stage, Hues hues, double time) {
        if (time < LIT || time >= FORMED) return;
        int pixel = stage.pixel();
        double size = time < BURNING ? 0.15 + 0.85 * easeBack(clamp((time - LIT) / (BURNING - LIT)))
                    : time < PIXELATING ? 1
                    : time < GATHERING ? 1 + 0.7 * ease(clamp((time - PIXELATING) / (GATHERING - PIXELATING)))
                    : time < BURST ? 1.7 - 0.3 * ease(clamp((time - GATHERING) / (BURST - GATHERING)))
                    : 1.4 + 3.0 * ease(clamp((time - BURST) / 0.35));
        double flicker = 1 + (time < PIXELATING ? 0.06 : 0.12) * Math.sin(17 * time) + 0.05 * Math.sin(31 * time + 0.7);
        double tall = 6 * pixel * size * flicker;
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
            for (int layer = 0; layer < layers.size(); layer++) {
                g.setColor(colours.get(layer));
                g.fill(layers.get(layer));
            }
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
        int risen = time >= FORMED && (int) (lived / 0.8) % 2 == 1 ? 1 : 0;
        double formed = clamp((time - FORMING) / (FORMED - FORMING));
        float halo = 13f * pixel;
        Point2D middle = stage.middle();
        double middleY = middle.getY() - risen * pixel;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(middle.getX(), middleY), halo, new float[] { 0f, 1f },
                new Color[] { alpha(hues.hot(), 0.22 * formed * (1 + 0.15 * Math.sin(2.1 * time))), alpha(hues.hot(), 0) }));
        g.fill(new Ellipse2D.Double(middle.getX() - halo, middleY - halo, 2 * halo, 2 * halo));
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
                g.fillRect(stage.pipX() + column * pixel, stage.pipY() + (row < 15 ? row - risen : row) * pixel, pixel, pixel);
                if (row == 15 && risen == 1) g.fillRect(stage.pipX() + column * pixel, stage.pipY() + 14 * pixel, pixel, pixel);
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
            float reach = 14f * pixel;
            g.setPaint(new RadialGradientPaint(middle, reach, new float[] { 0f, 1f },
                    new Color[] { alpha(hues.brightest(), 0.85 * flash), alpha(hues.brightest(), 0) }));
            g.fill(new Ellipse2D.Double(middle.getX() - reach, middle.getY() - reach, 2 * reach, 2 * reach));
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

    /// As [#ease], going a little past the end before settling: a flame that catches.
    private static double easeBack(double progress) {
        double over = 1.6;
        double back = progress - 1;
        return 1 + (over + 1) * back * back * back + over * back * back;
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
