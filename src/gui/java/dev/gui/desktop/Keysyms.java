package dev.gui.desktop;

import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.OptionalInt;

/// Which X11 key symbol, the keyboard language of VNC, a key pressed in Java stands for.
///
/// Keys that type a character are sent as that character, so the desktop gets what the user's
/// own keyboard layout produced. Keys that do not, such as the arrows, have symbols of their own.
/// A letter or digit typed with Control held reaches Java as a control character, so it is
/// sent as the plain letter or digit, with the Control key the desktop already saw held down.
public final class Keysyms {

    private Keysyms() {}

    private static final Map<Integer, Integer> SPECIAL = Map.ofEntries(
            Map.entry(KeyEvent.VK_BACK_SPACE, 0xff08), Map.entry(KeyEvent.VK_TAB, 0xff09),
            Map.entry(KeyEvent.VK_ENTER, 0xff0d), Map.entry(KeyEvent.VK_ESCAPE, 0xff1b),
            Map.entry(KeyEvent.VK_DELETE, 0xffff), Map.entry(KeyEvent.VK_HOME, 0xff50),
            Map.entry(KeyEvent.VK_LEFT, 0xff51), Map.entry(KeyEvent.VK_UP, 0xff52),
            Map.entry(KeyEvent.VK_RIGHT, 0xff53), Map.entry(KeyEvent.VK_DOWN, 0xff54),
            Map.entry(KeyEvent.VK_PAGE_UP, 0xff55), Map.entry(KeyEvent.VK_PAGE_DOWN, 0xff56),
            Map.entry(KeyEvent.VK_END, 0xff57), Map.entry(KeyEvent.VK_INSERT, 0xff63),
            Map.entry(KeyEvent.VK_SHIFT, 0xffe1), Map.entry(KeyEvent.VK_CONTROL, 0xffe3),
            Map.entry(KeyEvent.VK_CAPS_LOCK, 0xffe5), Map.entry(KeyEvent.VK_ALT, 0xffe9),
            Map.entry(KeyEvent.VK_ALT_GRAPH, 0xfe03), Map.entry(KeyEvent.VK_WINDOWS, 0xffeb),
            Map.entry(KeyEvent.VK_META, 0xffeb), Map.entry(KeyEvent.VK_CONTEXT_MENU, 0xff67),
            Map.entry(KeyEvent.VK_F1, 0xffbe), Map.entry(KeyEvent.VK_F2, 0xffbf),
            Map.entry(KeyEvent.VK_F3, 0xffc0), Map.entry(KeyEvent.VK_F4, 0xffc1),
            Map.entry(KeyEvent.VK_F5, 0xffc2), Map.entry(KeyEvent.VK_F6, 0xffc3),
            Map.entry(KeyEvent.VK_F7, 0xffc4), Map.entry(KeyEvent.VK_F8, 0xffc5),
            Map.entry(KeyEvent.VK_F9, 0xffc6), Map.entry(KeyEvent.VK_F10, 0xffc7),
            Map.entry(KeyEvent.VK_F11, 0xffc8), Map.entry(KeyEvent.VK_F12, 0xffc9));

    /// The key symbol for a key pressed in Java, or nothing for a key the desktop has no use for.
    ///
    /// @param keyCode Java's code for the key, such as [KeyEvent#VK_A]
    /// @param keyChar the character it typed, or [KeyEvent#CHAR_UNDEFINED]
    public static OptionalInt of(int keyCode, char keyChar) {
        Integer special = SPECIAL.get(keyCode);
        if (special != null) return OptionalInt.of(special);
        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= 0x20 && keyChar != 0x7f)
            return OptionalInt.of(ofCharacter(keyChar));
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) return OptionalInt.of('a' + keyCode - KeyEvent.VK_A);
        if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9) return OptionalInt.of('0' + keyCode - KeyEvent.VK_0);
        return OptionalInt.empty();
    }

    /// Latin-1 characters are their own key symbols; every other character is its code point
    /// plus 0x01000000.
    static int ofCharacter(char character) {
        return character <= 0xff ? character : 0x01000000 | character;
    }
}
