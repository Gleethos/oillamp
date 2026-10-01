package gui

import dev.gui.desktop.RfbConnection
import dev.gui.desktop.X11KeysymUtil
import spock.lang.Specification
import spock.lang.Timeout

import java.awt.event.KeyEvent
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 *  Genies shows a genie's desktop inside its own window, through a small VNC client.
 *
 *  <p>The desktop is served by wayvnc in the sandbox, on a Unix socket the lamp names
 *  ({@code Lamp.desktop()}). Here a stand-in desktop plays wayvnc's part of the protocol,
 *  RFB 3.8, byte by byte, so the client is checked against the protocol rather than against
 *  itself. A spike checks it against the real wayvnc.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WatchingAGeniesDesktopSpec extends Specification {

    Path socket
    ServerSocketChannel server
    DataInputStream fromViewer
    DataOutputStream toViewer
    final List<String> heard = new CopyOnWriteArrayList<>()
    RfbConnection viewer

    def setup() {
        // Short on purpose: a Unix socket path may not be longer than 107 bytes.
        socket = Files.createTempDirectory(Path.of('/tmp'), 'rfb').resolve('vnc.sock')
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
    }

    def cleanup() {
        viewer?.close()
        server?.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(socket.parent)
    }

    def 'The viewer connects without a password, and asks for pixels it can draw as they come'() {
        reportInfo """
            Only this user can open the desktop's socket, so wayvnc asks for no password and the
            viewer offers none. It then asks for plain 32-bit pixels in exactly the layout of a
            Java image, so it can copy them without converting, and for the whole screen once.
        """
        when:
            connect(4, 2)

        then: 'the pixel format: 32 bits, 24 deep, little-endian, true colour, red at bit 16'
            fromViewer.readNBytes(20) as List == [0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0] as List<Byte>

        and: 'the encodings: raw, copy-rect, and being told when the desktop changes size, in both forms'
            fromViewer.readNBytes(4) as List == [2, 0, 0, 4] as List<Byte>
            [fromViewer.readInt(), fromViewer.readInt(), fromViewer.readInt(), fromViewer.readInt()] == [0, 1, -223, -308]

        and: 'the whole screen, not just what changed'
            fromViewer.readNBytes(2) as List == [3, 0] as List<Byte>
            [fromViewer.readUnsignedShort(), fromViewer.readUnsignedShort(),
             fromViewer.readUnsignedShort(), fromViewer.readUnsignedShort()] == [0, 0, 4, 2]

        and:
            viewer.name() == 'lamp k3v7'
            viewer.screen().width == 4
    }

    def 'Pixels the desktop sends are drawn where it puts them, and the viewer asks for the next change'() {
        reportInfo """
            wayvnc sends only what changed. Each rectangle lands in the viewer's image at its
            place, and the window is told to redraw that part. Then the viewer asks for the
            next change, and only the change, so an idle desktop costs nothing.
        """
        given:
            connect(4, 2)
            skipTheViewersSetup()

        when: 'two pixels arrive at (1,0): red, then blue'
            toViewer.write([0, 0, 0, 1] as byte[])
            rectangle(1, 0, 2, 1, 0)
            toViewer.write([0, 0, (byte) 0xff, 0, (byte) 0xff, 0, 0, 0] as byte[])
            toViewer.flush()

        then:
            fromViewer.readNBytes(2) as List == [3, 1] as List<Byte>
            waitUntil { heard.contains('painted 1,0 2x1') }
            (viewer.screen().getRGB(0, 0) & 0xffffff) == 0
            (viewer.screen().getRGB(1, 0) & 0xffffff) == 0xff0000
            (viewer.screen().getRGB(2, 0) & 0xffffff) == 0x0000ff
    }

    def 'Clicks and keys reach the desktop'() {
        reportInfo """
            The user can work on the genie's desktop, for example to fill in what the genie
            could not. The pointer goes as position and held buttons, keys as X11 key symbols,
            pressed and released.
        """
        given:
            connect(4, 2)
            skipTheViewersSetup()

        when:
            viewer.pointer(300, 2, 1)
            viewer.key(0xff0d, true)

        then:
            fromViewer.readNBytes(6) as List == [5, 1, 1, 44, 0, 2] as List<Byte>
            fromViewer.readNBytes(8) as List == [4, 1, 0, 0, 0, 0, (byte) 0xff, 0x0d] as List<Byte>
    }

    def 'A desktop that changes size gets a new picture of that size'() {
        reportInfo """
            The sandbox's desktop can change resolution. wayvnc says so with a rectangle of the
            "desktop size" kind, and the viewer starts a new image of that size.
        """
        given:
            connect(4, 2)
            skipTheViewersSetup()

        when:
            toViewer.write([0, 0, 0, 1] as byte[])
            rectangle(0, 0, 1280, 800, -223)
            toViewer.flush()

        then:
            waitUntil { heard.contains('resized 1280x800') }
            viewer.screen().width == 1280
            viewer.screen().height == 800
    }

    def 'The viewer asks the desktop for another size, and draws it at that size once it has it'() {
        reportInfo """
            Genies can show the desktop at the size of the panel it sits in. The viewer asks for
            that size with a "set desktop size" message, naming the desktop's one screen. wayvnc
            answers with a rectangle of the "extended desktop size" kind: x 1 means the answer is
            for this viewer, y 0 that it is done. The picture then has the new size.
        """
        given:
            connect(4, 2)
            skipTheViewersSetup()
            announce(7)

        when:
            viewer.askForSize(1280, 800)

        then: 'the request: type 251, the size, one screen, its id 7, at 0,0, of that size'
            fromViewer.readNBytes(2) as List == [(byte) 251, 0] as List<Byte>
            [fromViewer.readUnsignedShort(), fromViewer.readUnsignedShort()] == [1280, 800]
            fromViewer.readNBytes(2) as List == [1, 0] as List<Byte>
            [fromViewer.readInt(), fromViewer.readUnsignedShort(), fromViewer.readUnsignedShort(),
             fromViewer.readUnsignedShort(), fromViewer.readUnsignedShort(), fromViewer.readInt()] == [7, 0, 0, 1280, 800, 0]

        when: 'the desktop answers that it is done'
            sizeChanged(1, 0, 1280, 800)

        then:
            waitUntil { heard.contains('resized 1280x800') }
            viewer.screen().width == 1280
    }

    def 'A desktop that keeps its size says so, and the picture stays as it was'() {
        reportInfo """
            A recorded desktop does not change size: wayvnc answers y 1, "not allowed". The
            viewer passes that on, so Genies can say why the desktop does not fit the panel, and
            goes on showing the desktop at the size it has.
        """
        given:
            connect(4, 2)
            skipTheViewersSetup()

        when:
            viewer.askForSize(1280, 800)
            sizeChanged(1, 1, 1280, 800)

        then:
            waitUntil { heard.contains('kept its size') }
            !heard.any { it.startsWith('resized') }
            viewer.screen().width == 4
    }

    def 'A desktop that passes the request on changes size later, as it does by itself'() {
        reportInfo """
            wayvnc does not change the size itself: it asks sway, and answers y 4, "passed on".
            Once sway has done it, wayvnc reports the new size like any change, x 0. Only then
            does the picture change. The rectangle wayvnc sends once, unasked, to say it knows
            this extension, has the size the desktop already has, and changes nothing.
        """
        given:
            connect(4, 2)
            skipTheViewersSetup()

        when:
            announce(0)
            viewer.askForSize(1280, 800)
            sizeChanged(1, 4, 1280, 800)
            sizeChanged(0, 0, 1280, 800)

        then:
            waitUntil { heard.contains('resized 1280x800') }
            heard.findAll { it.startsWith('resized') || it == 'kept its size' } == ['resized 1280x800']
    }

    def 'When the desktop goes away, the viewer says so'() {
        reportInfo """
            The genie may be put to sleep while its desktop is shown. The window is told the
            connection ended, and why, so it can show that instead of a frozen picture.
        """
        given:
            connect(4, 2)

        when:
            toViewer.close()

        then:
            waitUntil { heard.any { it.startsWith('ended the desktop closed the connection') } }
    }

    def 'A desktop that wants a password is refused, not guessed at'() {
        reportInfo """
            A lamp's desktop never asks for a password. One that does is not a lamp's, and the
            viewer says so rather than trying anything.
        """
        given:
            Thread.start {
                var client = server.accept()
                var out = new DataOutputStream(Channels.newOutputStream(client))
                var input = new DataInputStream(Channels.newInputStream(client))
                out.write('RFB 003.008\n'.getBytes(StandardCharsets.US_ASCII))
                input.readNBytes(12)
                out.write([1, 2] as byte[])                 // VNC authentication only
                out.flush()
                input.read()
            }

        when:
            RfbConnection.open(socket, listener())

        then:
            var refused = thrown(IOException)
            refused.message.contains('asks for a password')
    }

    def 'Keys become the X11 key symbols a desktop understands'() {
        reportInfo """
            A key that types a character is sent as that character, so the user's own keyboard
            layout decides what is typed. Keys that type nothing have symbols of their own. With
            Control held, Java reports a control character for a letter, so the letter itself
            is sent and the desktop, which saw Control go down, makes the shortcut of it.
        """
        expect:
            X11KeysymUtil.of(code, character as char).orElse(-1) == keysym

        where:
            code                  | character                 || keysym
            KeyEvent.VK_A         | 'a'                       || 0x61
            KeyEvent.VK_A         | 'A'                       || 0x41
            KeyEvent.VK_E         | 'é'                       || 0xe9
            KeyEvent.VK_UNDEFINED | '€'                       || 0x010020ac
            KeyEvent.VK_C         | 3                         || 0x63
            KeyEvent.VK_ENTER     | '\n'                      || 0xff0d
            KeyEvent.VK_LEFT      | KeyEvent.CHAR_UNDEFINED   || 0xff51
            KeyEvent.VK_CONTROL   | KeyEvent.CHAR_UNDEFINED   || 0xffe3
            KeyEvent.VK_F5        | KeyEvent.CHAR_UNDEFINED   || 0xffc2
            KeyEvent.VK_NUM_LOCK  | KeyEvent.CHAR_UNDEFINED   || -1
    }

    // ─── the stand-in desktop ──────────────────────────────────────────────────────────────

    /** Accepts the viewer and plays wayvnc's part of the handshake, for a screen of this size. */
    private void connect(int width, int height) {
        var accepted = Thread.start {
            SocketChannel client = server.accept()
            fromViewer = new DataInputStream(Channels.newInputStream(client))
            toViewer = new DataOutputStream(Channels.newOutputStream(client))
            toViewer.write('RFB 003.008\n'.getBytes(StandardCharsets.US_ASCII))
            toViewer.flush()
            assert new String(fromViewer.readNBytes(12), StandardCharsets.US_ASCII) == 'RFB 003.008\n'
            toViewer.write([1, 1] as byte[])                    // one security type: none
            toViewer.flush()
            assert fromViewer.read() == 1
            toViewer.writeInt(0)                                // security passed
            toViewer.flush()
            assert fromViewer.read() == 1                       // shared
            toViewer.writeShort(width)
            toViewer.writeShort(height)
            toViewer.write(new byte[16])
            var name = 'lamp k3v7'.getBytes(StandardCharsets.UTF_8)
            toViewer.writeInt(name.length)
            toViewer.write(name)
            toViewer.flush()
        }
        viewer = RfbConnection.open(socket, listener())
        accepted.join()
    }

    private void skipTheViewersSetup() {
        fromViewer.readNBytes(20 + 4 + 16 + 10)
    }

    /** The rectangle wayvnc sends once to say it knows the extension: the size as it is. */
    private void announce(int screenId) {
        sizeChanged(0, 0, viewer.screen().width, viewer.screen().height, screenId)
        fromViewer.readNBytes(10)                     // the viewer, having read it, asks for more
    }

    /** An extended-desktop-size rectangle: why (0 by itself, 1 this viewer asked), how it went, and one screen. */
    private void sizeChanged(int why, int how, int width, int height, int screenId = 0) {
        toViewer.write([0, 0, 0, 1] as byte[])
        rectangle(why, how, width, height, -308)
        toViewer.write([1, 0, 0, 0] as byte[])
        toViewer.writeInt(screenId)
        toViewer.writeShort(0); toViewer.writeShort(0); toViewer.writeShort(width); toViewer.writeShort(height)
        toViewer.writeInt(0)
        toViewer.flush()
    }

    private void rectangle(int x, int y, int width, int height, int encoding) {
        toViewer.writeShort(x)
        toViewer.writeShort(y)
        toViewer.writeShort(width)
        toViewer.writeShort(height)
        toViewer.writeInt(encoding)
    }

    private RfbConnection.Listener listener() {
        new RfbConnection.Listener() {
            void resized(int width, int height) { heard << "resized ${width}x${height}".toString() }
            void painted(int x, int y, int width, int height) { heard << "painted ${x},${y} ${width}x${height}".toString() }
            void ended(Optional<String> reason) { heard << "ended ${reason.orElse('')}".toString() }
            void keptItsSize() { heard << 'kept its size' }
        }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; heard ${heard}" as Object)
    }
}
