package dev.gui.model;

/// How the chat and a genie's desktop share the room they have: the chat on the left and the
/// desktop on the right, or the chat on top and the desktop under it in a narrow room. The user
/// drags the grip between the two to give one more room and the other less. Dragged (nearly) all
/// the way toward the desktop, letting go closes it.
///
/// Until the user drags, Genies picks the share. Beside the desktop, the chat gets five twelfths
/// of the width, but no more than reads comfortably, so on a wide screen the rest goes to the
/// desktop. Above the desktop, the chat gets three fifths of the height. Once dragged, the chat
/// keeps its share in that arrangement as the window grows or shrinks.
///
/// @param besideShare the chat's share of the room's width beside the desktop; 0 while Genies
///                    picks it
/// @param aboveShare  the chat's share of the room's height above the desktop; 0 while Genies
///                    picks it
/// @param held        whether the user holds the grip. Only then may the desktop get less than it
///                    needs, down to nothing, which shows that letting go closes it
public record Split(double besideShare, double aboveShare, boolean held) {

    public static final Split PICKED = new Split(0, 0, false);

    /// The least room the chat needs along the split, in the window's units.
    public static final int LEAST_CHAT = 280;
    /// The least room the desktop needs along the split. Let go with less than half of it, the
    /// desktop closes; with less than all of it, the desktop gets all of it.
    public static final int LEAST_DESKTOP = 240;
    /// How thick the grip between the two is.
    public static final int GRIP = 9;
    /// The shortest room the two are split in: a shorter one scrolls.
    public static final int SHORTEST = LEAST_CHAT + GRIP + LEAST_DESKTOP;
    /// The most width Genies picks for the chat beside the desktop.
    static final int PICKED_WIDEST = 560;

    /// How long the chat is along the split, in a room `length` long.
    ///
    /// @param length at least [#SHORTEST]
    /// @param beside whether the desktop is beside the chat, rather than under it
    public int chat(int length, boolean beside) {
        double share = beside ? besideShare : aboveShare;
        long picked = beside ? Math.min(Math.round(length * 5 / 12.0), PICKED_WIDEST) : Math.round(length * 3 / 5.0);
        long part = share > 0 ? Math.round(length * share) : picked;
        return Math.clamp(part, LEAST_CHAT, length - GRIP - (held ? 0 : LEAST_DESKTOP));
    }
}
