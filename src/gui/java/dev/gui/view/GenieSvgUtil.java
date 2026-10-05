package dev.gui.view;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.gui.model.Entry;
import dev.gui.model.Genie;

import swingtree.api.IconDeclaration;

/// Pip, the genie mascot: a little pixel spirit with a big head, two big eyes, short arms and a
/// tail where its legs would be. It is drawn from rows of letters, one letter per pixel, and
/// handed out as SVG text, so it stays sharp at any size and its colours can change.
///
/// Every genie looks a little different: its body colour and what it wears follow from its id,
/// so a genie always looks the same and nothing about its looks has to be kept. Its pose follows
/// from what it does: awake, asleep, thinking, working with its tools, or dizzy when something
/// went wrong. Thinking and working are animations of a few frames each.
///
/// The picture is twenty pixels square. The genie takes sixteen of them; the rest is room for a
/// hat, for the z of its sleep and for what floats around it. Shown at a whole multiple of twenty,
/// its pixels stay square and sharp.
///
/// The letters:
///
/// | Letter | Pixel |
/// |---|---|
/// | `.` | none, or, over the genie, the genie's pixel as it is |
/// | `B` | the body colour |
/// | `E` | the eyes |
/// | `Z` | the z of its sleep, and the dots of its thoughts |
/// | `Y` | a sparkle or a star |
/// | `C`, `c` | the cloth of a turban |
/// | `G` | gold |
/// | `R`, `r` | red |
/// | `w` | white: the plume of a turban |
/// | `H`, `h` | hair |
/// | `F`, `f` | a flame |
final class GenieSvgUtil {

    private GenieSvgUtil() {}

    /// The things a genie can wear. A genie wears at most one hat: a turban, a fez, a topknot or
    /// a flame. The order here is the order they are drawn in, so a hat covers what is under it.
    enum Accessory { VEST, CUFFS, EARRING, TURBAN, FEZ, TOPKNOT, FLAME }

    /// What a genie does, as its picture shows it.
    enum Pose {
        /// Awake, and waiting for the user.
        AWAKE(1),
        /// Asleep in its lamp: eyes shut, arms tucked in, a z above its head.
        ASLEEP(1),
        /// Its model thinks: it looks up, and dots rise one by one.
        THINKING(4),
        /// One of its tools runs: it looks down at its work and raises one arm, then the other,
        /// with a sparkle on that side.
        WORKING(2),
        /// Something went wrong: crossed eyes and stars.
        DIZZY(1);

        /// How many pictures the pose has; more than one makes an animation.
        final int frames;

        Pose(int frames) { this.frames = frames; }
    }

    /// What one genie looks like: its body colour and what it wears.
    record Appearance(Color colour, Set<Accessory> accessories) {}

    /// The body colours a genie gets one of: sky, mint, lilac, rose, sea, lemon, coral and cloud.
    /// Each looks right on Genies' dark plum and next to its amber, and none is that amber, so a
    /// genie never looks like a button.
    static final List<Color> COLOURS = List.of(
            new Color(0x6f, 0xc8, 0xf0), new Color(0x7e, 0xe0, 0xa8), new Color(0xb9, 0xa2, 0xf5), new Color(0xf5, 0x9a, 0xbf),
            new Color(0x4f, 0xd1, 0xc5), new Color(0xf2, 0xdc, 0x6b), new Color(0xff, 0x8a, 0x7a), new Color(0xd9, 0xdd, 0xe8));

    private static final List<Accessory> HATS = List.of(Accessory.TURBAN, Accessory.FEZ, Accessory.TOPKNOT, Accessory.FLAME);

    /// The appearance of the genie with `id`, the same every time: any of the colours above; a hat three
    /// times in four, any of the four; and a vest, cuffs and an earring, each once in three. The
    /// id seeds Java's `Random`, whose numbers for a seed are fixed by its specification, so a
    /// genie keeps its appearance across versions of Java.
    static Appearance appearanceOf(UUID id) {
        Random random = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        Color colour = COLOURS.get(random.nextInt(COLOURS.size()));
        Set<Accessory> wears = EnumSet.noneOf(Accessory.class);
        if (random.nextInt(4) != 0) wears.add(HATS.get(random.nextInt(HATS.size())));
        if (random.nextInt(3) == 0) wears.add(Accessory.VEST);
        if (random.nextInt(3) == 0) wears.add(Accessory.CUFFS);
        if (random.nextInt(3) == 0) wears.add(Accessory.EARRING);
        return new Appearance(colour, wears);
    }

    /// The pose of `genie`. Waking, it is still in its lamp, so it sleeps. Working, it thinks,
    /// unless the newest thing in its conversation is a tool that is running.
    static Pose poseOf(Genie genie) {
        return switch (genie.phase()) {
            case ASLEEP, WAKING -> Pose.ASLEEP;
            case READY -> Pose.AWAKE;
            case BROKEN -> Pose.DIZZY;
            case WORKING -> {
                boolean toolRuns = !genie.transcript().isEmpty()
                        && genie.transcript().entries().last().kind() == Entry.Kind.TOOL
                        && genie.transcript().entries().last().isWriting();
                yield toolRuns ? Pose.WORKING : Pose.THINKING;
            }
        };
    }

    /// The frame of `pose` to show when an animation looping from 0 to 1 is at `progress`.
    static int frameAt(Pose pose, double progress) {
        return Math.min(pose.frames - 1, (int) (progress * pose.frames));
    }

    /// Every genie handed out, by its appearance, pose and frame. The chat asks again on every
    /// step of an animation, and SwingTree keeps a drawn icon only while its declaration is held
    /// somewhere, so holding them here spares writing and drawing a genie again. There are nine
    /// for each genie, one for each frame of its poses.
    private static final Map<List<Object>, IconDeclaration> GENIES = new ConcurrentHashMap<>();

    /// No genie: an empty picture of the same size, for where the genie is in its lamp.
    static final IconDeclaration NONE = IconDeclaration.ofSvg("<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'/>");

    /// The genie that `appearance` describes, in `frame` of `pose`, twenty pixels square.
    static IconDeclaration genie(Appearance appearance, Pose pose, int frame) {
        return GENIES.computeIfAbsent(List.of(appearance, pose, frame), key -> IconDeclaration.ofSvg(svg(appearance, pose, frame)));
    }

    /// The SVG text of the genie: one path for each colour, each pixel a square of one unit.
    /// Under them all lies the genie's whole shape in its body colour, so no thin gap shows
    /// between two colours where the drawing is scaled and smoothed.
    static String svg(Appearance appearance, Pose pose, int frame) {
        char[][] pixels = new char[BODY.length][];
        for (int y = 0; y < BODY.length; y++) pixels[y] = BODY[y].toCharArray();
        List<Sprite> layers = new ArrayList<>(pose(pose, frame));
        EnumSet<Accessory> worn = EnumSet.noneOf(Accessory.class);
        worn.addAll(appearance.accessories());
        for (Accessory accessory : worn) layers.add(sprite(accessory));
        for (Sprite sprite : layers)
            for (int row = 0; row < sprite.rows().size(); row++) {
                String line = sprite.rows().get(row);
                for (int column = 0; column < line.length(); column++)
                    if (line.charAt(column) != '.') pixels[sprite.y() + row][sprite.x() + column] = line.charAt(column);
            }

        Map<Character, Color> paint = new LinkedHashMap<>();
        paint.put('B', appearance.colour());
        paint.put('E', new Color(0x1f, 0x16, 0x26));
        paint.put('Z', new Color(0xa8, 0x9c, 0x8a));
        paint.put('Y', new Color(0xff, 0xe0, 0x8a));
        paint.put('C', new Color(0xf4, 0xea, 0xd8));
        paint.put('c', new Color(0xc9, 0xb8, 0x9c));
        paint.put('G', new Color(0xf6, 0xcf, 0x57));
        paint.put('R', new Color(0xe0, 0x3e, 0x52));
        paint.put('r', new Color(0x9c, 0x22, 0x3a));
        paint.put('w', new Color(0xff, 0xff, 0xff));
        paint.put('H', new Color(0x86, 0x6a, 0xab));
        paint.put('h', new Color(0x4a, 0x35, 0x60));
        paint.put('F', new Color(0xff, 0xd1, 0x66));
        paint.put('f', new Color(0xf0, 0x8a, 0x30));
        for (int y = 0; y < pixels.length; y++)
            for (int x = 0; x < pixels[y].length; x++)
                if (pixels[y][x] != '.' && !paint.containsKey(pixels[y][x]))
                    throw new IllegalStateException("no colour for '" + pixels[y][x] + "' at " + x + "," + y);

        int size = BODY.length;
        StringBuilder svg = new StringBuilder()
                .append("<svg xmlns='http://www.w3.org/2000/svg' width='").append(size).append("' height='").append(size)
                .append("' viewBox='0 0 ").append(size).append(' ').append(size).append("' shape-rendering='crispEdges'>");
        appendPath(svg, pixels, '.', appearance.colour());
        for (Map.Entry<Character, Color> colour : paint.entrySet())
            appendPath(svg, pixels, colour.getKey(), colour.getValue());
        return svg.append("</svg>").toString();
    }

    /// Appends one path in `colour` covering every pixel that is `letter`, or, for `.`, every
    /// pixel that is not empty: the genie's whole shape. A run of such pixels in a row becomes
    /// one rectangle, so neighbouring pixels of one colour never show a seam between them.
    private static void appendPath(StringBuilder svg, char[][] pixels, char letter, Color colour) {
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < pixels.length; y++) {
            char[] row = pixels[y];
            int x = 0;
            while (x < row.length) {
                if (letter == '.' ? row[x] == '.' : row[x] != letter) { x++; continue; }
                int start = x;
                while (x < row.length && (letter == '.' ? row[x] != '.' : row[x] == letter)) x++;
                path.append('M').append(start).append(' ').append(y).append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        if (path.isEmpty()) return;
        svg.append("<path fill='").append(String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue()))
           .append("' d='").append(path).append("'/>");
    }

    /// A small drawing laid over the genie: its rows of letters, and where its top left pixel
    /// goes. A `.` leaves the pixel under it as it is.
    private record Sprite(int x, int y, List<String> rows) {
        Sprite(int x, int y, String... rows) { this(x, y, List.of(rows)); }
    }

    /// The genie without eyes and without the tips of its arms, which each pose adds.
    private static final String[] BODY = """
            ....................
            ....................
            ....................
            ....................
            ....BBBBBBBBBBBB....
            ...BBBBBBBBBBBBBB...
            ...BBBBBBBBBBBBBB...
            ...BBBBBBBBBBBBBB...
            ...BBBBBBBBBBBBBB...
            ...BBBBBBBBBBBBBB...
            ....BBBBBBBBBBBB....
            ...BBBBBBBBBBBBBB...
            .....BBBBBBBBBB.....
            ......BBBBBBBBB.....
            ........BBBBBB......
            .........BBBBB.BB...
            ..........BBBBBB....
            ....................
            ....................
            ....................
            """.lines().toArray(String[]::new);

    /// What `frame` of `pose` adds to the body: eyes, arms, and what floats around the genie.
    private static List<Sprite> pose(Pose pose, int frame) {
        Sprite arms = new Sprite(2, 11, "B..............B");
        return switch (pose) {
            case AWAKE -> List.of(arms,
                    new Sprite(5, 7, "EE......EE", "EE......EE"));
            case ASLEEP -> List.of(
                    new Sprite(5, 8, "EE......EE"),
                    new Sprite(16, 0, "ZZZZ", "..Z.", ".Z..", "ZZZZ"));
            case THINKING -> List.of(arms,
                    new Sprite(6, 6, "EE......EE", "EE......EE"),
                    switch (frame) {
                        case 0 -> new Sprite(17, 4, "Z");
                        case 1 -> new Sprite(17, 2, ".Z", "..", "Z.");
                        default -> new Sprite(17, 0, ".ZZ", ".ZZ", ".Z.", "...", "Z..");
                    });
            case WORKING -> List.of(
                    new Sprite(5, 8, "EE......EE", "EE......EE"),
                    frame == 0 ? new Sprite(2, 10, "BB..............", "...............B")
                               : new Sprite(2, 10, "..............BB", "B..............."),
                    frame == 0 ? new Sprite(0, 1, ".Y.", "YYY", ".Y.")
                               : new Sprite(17, 1, ".Y.", "YYY", ".Y."));
            case DIZZY -> List.of(arms,
                    new Sprite(4, 6, "E.E......E.E", ".E........E.", "E.E......E.E"),
                    new Sprite(0, 2, ".Y................", "YYY..............Y", ".Y..............YYY", ".................Y."));
        };
    }

    /// The drawing of `accessory`, made to fit the body and every pose.
    private static Sprite sprite(Accessory accessory) {
        return switch (accessory) {
            case TURBAN -> new Sprite(3, 0,
                    "......CC.w....",
                    "....CcCCCw....",
                    "..CCCcCCCCcC..",
                    ".CcCCGRRGCCcC.",
                    ".CCcCCGGCCcCC.",
                    "..cccccccccc..");
            case FEZ -> new Sprite(7, 1,
                    ".RRRR..",
                    ".RRRRG.",
                    "RRRRRRG",
                    "rrrrrr.");
            case TOPKNOT -> new Sprite(8, 0,
                    ".hH.",
                    "hhH.",
                    ".hh.",
                    ".GG.");
            case FLAME -> new Sprite(8, 0,
                    ".F..",
                    ".FF.",
                    "FfF.",
                    "FffF");
            case EARRING -> new Sprite(1, 6,
                    "B................B",
                    "BB..............BB",
                    ".................G");
            case CUFFS -> new Sprite(3, 11,
                    "G............G");
            case VEST -> new Sprite(5, 11,
                    "RR......RR",
                    ".RG....GR.");
        };
    }
}
