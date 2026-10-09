package dev.gui.view

import dev.gui.desktop.Desktop
import spock.lang.Specification
import spock.lang.Timeout

import javax.swing.SwingUtilities
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 *  What the desktop hears of the keys the user presses while Genies shows it.
 *
 *  <p>A stand-in desktop plays the VNC server's part, and the scenarios press keys through the
 *  desktop panel's own listeners, as Java does when the user types.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TypingIntoAGeniesDesktopSpec extends Specification {

    Path socket
    ServerSocketChannel server
    DataInputStream fromViewer
    DesktopScreen screen

    def setup() {
        // Short on purpose: a Unix socket path may not be longer than 107 bytes.
        socket = Files.createTempDirectory(Path.of('/tmp'), 'keys').resolve('vnc.sock')
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        SwingUtilities.invokeAndWait { screen = new DesktopScreen() }
        SwingUtilities.invokeAndWait { screen.show(Optional.of(new Desktop(socket, 4, 2))) }
        var channel = server.accept()
        fromViewer = new DataInputStream(Channels.newInputStream(channel))
        var toViewer = new DataOutputStream(Channels.newOutputStream(channel))
        toViewer.write('RFB 003.008\n'.getBytes(StandardCharsets.US_ASCII))
        toViewer.flush()
        fromViewer.readNBytes(12)
        toViewer.write([1, 1] as byte[])
        toViewer.flush()
        fromViewer.read()
        toViewer.writeInt(0)
        toViewer.flush()
        fromViewer.read()
        toViewer.writeShort(4)
        toViewer.writeShort(2)
        toViewer.write(new byte[16])
        toViewer.writeInt(4)
        toViewer.write('lamp'.getBytes(StandardCharsets.UTF_8))
        toViewer.flush()
        // The viewer's pixel format, its encodings and its first request for pixels.
        fromViewer.readNBytes(20 + 4 + 16 + 10)
        var deadline = System.currentTimeMillis() + 10_000
        var connected = false
        while (!connected && System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait { connected = screen.@connection.isPresent() }
            Thread.sleep(20)
        }
        assert connected, 'the desktop panel never connected to the stand-in desktop'
    }

    def cleanup() {
        SwingUtilities.invokeAndWait { screen.letGo() }
        server?.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(socket.parent)
    }

    def 'A key still held when the desktop loses the focus is let go of on the desktop'() {
        reportInfo """
            The user presses Alt+Tab to switch to another window. Alt goes down on the desktop,
            but by the time it is let go of, the keyboard belongs to another window, and the
            desktop never hears it. It kept Alt held, so every letter typed afterwards was a
            shortcut: Firefox opened its menus instead of taking text. Now the desktop panel lets
            go of every key it pressed as soon as it loses the focus.
        """
        when: 'Alt goes down on the desktop, and then the focus goes elsewhere'
            SwingUtilities.invokeAndWait {
                var alt = new KeyEvent(screen, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                                       InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED)
                screen.keyListeners.each { it.keyPressed(alt) }
                var away = new FocusEvent(screen, FocusEvent.FOCUS_LOST, true)
                screen.focusListeners.each { it.focusLost(away) }
            }

        then: 'the desktop heard Alt go down, and then go up'
            fromViewer.readNBytes(8) as List == [4, 1, 0, 0, 0, 0, (byte) 0xff, (byte) 0xe9] as List<Byte>
            fromViewer.readNBytes(8) as List == [4, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xe9] as List<Byte>
    }

    def 'A key still held when Genies stops showing the desktop is let go of on it'() {
        reportInfo """
            The user holds Shift and switches to another genie, whose desktop takes the panel.
            The first desktop must not be left with Shift held: it is let go of there before
            the connection to it ends.
        """
        when:
            SwingUtilities.invokeAndWait {
                var shift = new KeyEvent(screen, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                                         InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)
                screen.keyListeners.each { it.keyPressed(shift) }
                screen.show(Optional.empty())
            }

        then:
            fromViewer.readNBytes(8) as List == [4, 1, 0, 0, 0, 0, (byte) 0xff, (byte) 0xe1] as List<Byte>
            fromViewer.readNBytes(8) as List == [4, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xe1] as List<Byte>
    }
}
