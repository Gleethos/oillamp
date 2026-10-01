package dev.gui.desktop;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/// A view onto a genie's desktop: a VNC client, speaking RFB 3.8 over the lamp's Unix socket.
///
/// The desktop is served by wayvnc inside the sandbox. The socket is only reachable by this user,
/// so wayvnc asks for no password, and this client offers none. It asks for pixels as plain
/// 32-bit colour ("raw" encoding): over a local socket there is nothing to gain from
/// compression, and it keeps this class small enough to read.
///
/// One thread reads what the server sends into [#screen()] and tells the [Listener]; any thread
/// may send the pointer and the keyboard. The protocol is described in RFC 6143.
public final class RfbConnection implements AutoCloseable {

    /// What a connection reports. Called on the connection's own reading thread.
    public interface Listener {
        /// The desktop has a new size; [RfbConnection#screen()] is a new image.
        void resized(int width, int height);

        /// Part of the desktop changed and is ready to be drawn again.
        void painted(int x, int y, int width, int height);

        /// The connection ended, with the reason if it was not closed on purpose.
        void ended(Optional<String> reason);
    }

    static final String VERSION = "RFB 003.008\n";
    static final int SECURITY_NONE = 1;
    static final int ENCODING_RAW = 0, ENCODING_COPY_RECT = 1, ENCODING_DESKTOP_SIZE = -223;

    private final SocketChannel channel;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Listener listener;
    private final String name;
    private volatile BufferedImage screen;
    private volatile boolean closing;

    private RfbConnection(SocketChannel channel, Listener listener) throws IOException {
        this.channel = channel;
        this.in = new DataInputStream(Channels.newInputStream(channel));
        // Written to the channel directly, not through Channels.newOutputStream: a stream from
        // there may wait for the reading thread's lock, so a key press would wait for the next
        // picture from the desktop.
        this.out = new DataOutputStream(new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        });
        this.listener = listener;
        handshake();
        int width = in.readUnsignedShort(), height = in.readUnsignedShort();
        in.skipNBytes(16);                               // the server's pixel format; ours follows
        name = new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
        screen = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        askForOurPixels();
    }

    /// Connects to the desktop at `socket`, and starts reading it.
    ///
    /// @throws IOException when the socket cannot be reached, or does not speak RFB as expected
    public static RfbConnection open(Path socket, Listener listener) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socket));
            RfbConnection connection = new RfbConnection(channel, listener);
            Thread.ofVirtual().name("desktop " + socket.getFileName()).start(connection::read);
            return connection;
        } catch (IOException | RuntimeException failed) {
            channel.close();
            throw failed;
        }
    }

    /// The desktop as last received. Lock it while reading its pixels, as the connection writes
    /// into it under the same lock.
    public BufferedImage screen() { return screen; }

    /// The desktop's name, as the server gave it.
    public String name() { return name; }

    /// Moves the pointer to `x`, `y` on the desktop, with `buttons` held: 1 left, 2 middle,
    /// 4 right, 8 and 16 the wheel turning up and down.
    public void pointer(int x, int y, int buttons) {
        send(new byte[] {5, (byte) buttons, (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y});
    }

    /// Presses or releases the key `keysym`, an X11 key symbol (see [X11KeysymUtil]).
    public void key(int keysym, boolean down) {
        send(new byte[] {4, (byte) (down ? 1 : 0), 0, 0,
                         (byte) (keysym >> 24), (byte) (keysym >> 16), (byte) (keysym >> 8), (byte) keysym});
    }

    @Override public void close() {
        closing = true;
        try {
            channel.close();
        } catch (IOException alreadyClosed) {
            // Closing is all that was wanted.
        }
    }

    // ─── the protocol ──────────────────────────────────────────────────────────────────────

    private void handshake() throws IOException {
        String version = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
        if (!version.startsWith("RFB 003."))
            throw new IOException("the desktop socket does not speak VNC (it said \"" + version.strip() + "\")");
        out.write(VERSION.getBytes(StandardCharsets.US_ASCII));
        int offered = in.readUnsignedByte();
        if (offered == 0) throw new IOException("the desktop refused the connection: " + reason());
        boolean none = false;
        for (int i = 0; i < offered; i++) none |= in.readUnsignedByte() == SECURITY_NONE;
        if (!none) throw new IOException("the desktop asks for a password, which a lamp's desktop never does");
        out.writeByte(SECURITY_NONE);
        if (in.readInt() != 0) throw new IOException("the desktop refused the connection: " + reason());
        out.writeByte(1);                                // shared: other viewers stay connected
        out.flush();
    }

    private String reason() throws IOException {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    /// 32 bits per pixel, little-endian, red in bits 16 to 23: exactly the int layout of a
    /// `TYPE_INT_RGB` image, so raw pixels are copied without converting them.
    private void askForOurPixels() throws IOException {
        synchronized (out) {
            out.write(new byte[] {0, 0, 0, 0,
                                  32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
            out.write(new byte[] {2, 0, 0, 3});
            out.writeInt(ENCODING_RAW);
            out.writeInt(ENCODING_COPY_RECT);
            out.writeInt(ENCODING_DESKTOP_SIZE);
            requestUpdate(false);
        }
    }

    private void requestUpdate(boolean incremental) throws IOException {
        synchronized (out) {
            BufferedImage now = screen;
            out.write(new byte[] {3, (byte) (incremental ? 1 : 0), 0, 0, 0, 0});
            out.writeShort(now.getWidth());
            out.writeShort(now.getHeight());
            out.flush();
        }
    }

    private void read() {
        Optional<String> reason = Optional.empty();
        try {
            while (true) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0 -> { update(); requestUpdate(true); }
                    case 1 -> { in.skipNBytes(3); in.skipNBytes(6L * in.readUnsignedShort()); }
                    case 2 -> { }                        // the bell
                    case 3 -> { in.skipNBytes(3); in.skipNBytes(Integer.toUnsignedLong(in.readInt())); }
                    default -> throw new IOException("the desktop sent a message this viewer does not know (" + type + ")");
                }
            }
        } catch (EOFException ended) {
            if (!closing) reason = Optional.of("the desktop closed the connection");
        } catch (IOException failed) {
            // A desktop that stops while this viewer still has something unread for it resets
            // the connection instead of closing it; for the user, both mean the same.
            String message = Optional.ofNullable(failed.getMessage()).orElse(failed.toString());
            if (!closing) reason = Optional.of(message.contains("reset") ? "the desktop closed the connection" : message);
        } finally {
            close();
            listener.ended(reason);
        }
    }

    private void update() throws IOException {
        in.skipNBytes(1);
        int rectangles = in.readUnsignedShort();
        for (int i = 0; i < rectangles; i++) {
            int x = in.readUnsignedShort(), y = in.readUnsignedShort();
            int width = in.readUnsignedShort(), height = in.readUnsignedShort();
            int encoding = in.readInt();
            switch (encoding) {
                case ENCODING_RAW -> raw(x, y, width, height);
                case ENCODING_COPY_RECT -> copy(in.readUnsignedShort(), in.readUnsignedShort(), x, y, width, height);
                case ENCODING_DESKTOP_SIZE -> {
                    screen = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
                    listener.resized(width, height);
                    continue;
                }
                default -> throw new IOException("the desktop sent pixels in an encoding this viewer did not ask for (" + encoding + ")");
            }
            listener.painted(x, y, width, height);
        }
    }

    private void raw(int x, int y, int width, int height) throws IOException {
        byte[] bytes = in.readNBytes(width * height * 4);
        if (bytes.length < width * height * 4) throw new EOFException();
        BufferedImage target = screen;
        int[] pixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        int stride = target.getWidth();
        synchronized (target) {
            for (int row = 0; row < height && y + row < target.getHeight(); row++) {
                int from = row * width * 4;
                int to = (y + row) * stride + x;
                for (int column = 0; column < width && x + column < stride; column++, from += 4)
                    pixels[to + column] = (bytes[from] & 0xff) | (bytes[from + 1] & 0xff) << 8
                                        | (bytes[from + 2] & 0xff) << 16;
            }
        }
    }

    private void copy(int fromX, int fromY, int x, int y, int width, int height) {
        BufferedImage target = screen;
        synchronized (target) {
            target.getRaster().setDataElements(x, y, target.getRaster().getDataElements(fromX, fromY, width, height, null));
        }
    }

    private void send(byte[] message) {
        try {
            synchronized (out) {
                out.write(message);
                out.flush();
            }
        } catch (IOException closed) {
            // The reading thread notices the connection ended and says so.
        }
    }
}
