package dev.oillamp;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import dev.lamp.Problem;

/// A Unix socket on the host that forwards each connection into the sandbox's SSH socket.
///
/// The container listens on `sockets/agent/ssh.sock`. The supervisor listens on
/// `run/ssh-primary.sock` and `run/ssh.sock`, which are outside anything mounted into the
/// container, and copies each connection through. Because the agent cannot reach the primary
/// socket, it cannot pose as the shell window whose connecting starts the session.
///
/// The primary relay accepts exactly one connection per session: the terminal window. The extra
/// relay accepts any number, for `oillamp shell`.
final class Relay implements AutoCloseable {

    /// The kernel's limit on a Unix socket path, in bytes. Sockets are addressed through the short
    /// runtime directory for this reason, but a long user name or a custom runtime directory can
    /// still exceed it, and `bind` would then fail with an unhelpful `EINVAL`.
    static final int MAX_SOCKET_PATH = 107;

    private static final int BUFFER_BYTES = 64 * 1024;

    /// How many connections the kernel holds for a socket until they are accepted. The default is
    /// 50, and a build that opens more than that through the proxy at once had the rest refused.
    /// The same as the proxy's own limit on open connections.
    static final int ACCEPT_QUEUE = 512;

    /// What the supervisor is told, as it happens. Every method is called from a relay thread.
    interface Listener {
        void connected();
        void disconnected();
        void trouble(Problem problem);
    }

    private final Path socket;
    private final Path target;
    private final int maxConnections;
    private final Listener listener;
    private final ServerSocketChannel server;
    private final List<SocketChannel> live = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile boolean closing;

    private Relay(Path socket, Path target, int maxConnections, Listener listener,
                  ServerSocketChannel server) {
        this.socket = socket;
        this.target = target;
        this.maxConnections = maxConnections;
        this.listener = listener;
        this.server = server;
    }

    /// Binds `socket` and forwards everything it receives to `target`.
    ///
    /// @param maxConnections `1` for the primary relay, [Integer#MAX_VALUE] for extra shells
    static Result<Relay> open(Path socket, Path target, int maxConnections, Listener listener) {
        return bind(socket).map(server -> {
            Relay relay = new Relay(socket, target, maxConnections, listener, server);
            Thread.ofVirtual().name("oillamp-relay-" + socket.getFileName()).start(relay::acceptLoop);
            return relay;
        });
    }

    /// Binds a host-only Unix socket at 0600. Used by the relays and by the control socket.
    ///
    /// The path length is checked first, because a path over the kernel's limit fails with an
    /// unhelpful `EINVAL`. An existing file is deleted first, because a socket file left by a
    /// crashed session makes `bind` fail with "address already in use". The container's
    /// entrypoint does the same for its own sockets.
    static Result<ServerSocketChannel> bind(Path socket) {
        int length = socket.toString().getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_SOCKET_PATH)
            return Result.err(Problems.socketPathTooLong(socket, MAX_SOCKET_PATH));
        try {
            Path parent = socket.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.deleteIfExists(socket);
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket), ACCEPT_QUEUE);
            // 0600 at once: only this user may connect to the session's relays and control socket.
            Filesystem.setMode(socket, PosixMode.PRIVATE_FILE);
            return Result.ok(server);
        } catch (IOException e) {
            return Result.err(Problems.cannotListen(socket, Problems.reason(e)));
        }
    }

    /// Whether something is listening on this socket right now.
    ///
    /// Used by the health check during a session. Connecting is the only check that tells a
    /// listening server apart from a leftover file with the right name.
    static boolean answers(Path socket) {
        if (!Files.exists(socket)) return false;
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            return channel.isConnected();
        } catch (IOException refused) {
            return false;
        }
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (!closing) listener.trouble(Problems.cannotListen(socket, Problems.reason(e)));
                return;
            }
            if (accepted.incrementAndGet() > maxConnections) {
                // The primary relay belongs to the terminal window oillamp opened. A second
                // connection is a mistake, so it is refused and `oillamp shell` suggested.
                closeQuietly(client);
                listener.trouble(Problems.extraPrimaryRejected(socket));
                continue;
            }
            Thread.ofVirtual().name("oillamp-relay-connection").start(() -> relayOneConnection(client));
        }
    }

    /// One connection: open the far side, copy both ways, and report it when it ends.
    private void relayOneConnection(SocketChannel client) {
        SocketChannel sandbox;
        try {
            sandbox = SocketChannel.open(UnixDomainSocketAddress.of(target));
        } catch (IOException e) {
            // The sandbox's SSH socket is gone or refusing. The user only sees a terminal window
            // that opens and closes, so this is reported in the supervisor's terminal.
            listener.trouble(Problems.sandboxEndpointDead("the shell (SSH)", target,
                    "the sandbox", "connecting for a shell: " + Problems.reason(e)));
            closeQuietly(client);
            return;
        }
        live.add(client);
        live.add(sandbox);
        listener.connected();

        AtomicInteger directions = new AtomicInteger(2);
        Runnable finished = () -> {
            if (directions.decrementAndGet() > 0) return;
            live.remove(client);
            live.remove(sandbox);
            closeQuietly(client);
            closeQuietly(sandbox);
            if (!closing) listener.disconnected();
        };
        Thread.ofVirtual().start(() -> { copy(client, sandbox); finished.run(); });
        Thread.ofVirtual().start(() -> { copy(sandbox, client); finished.run(); });
    }

    /// Moves bytes until one end stops talking, then half-closes the other.
    ///
    /// The half-close matters for SSH: the client sends EOF when the user types `exit`,
    /// and a relay that closed both directions at that moment would cut off the server's goodbye.
    private static void copy(SocketChannel from, SocketChannel to) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES);
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) to.write(buffer);
                buffer.clear();
            }
            to.shutdownOutput();
        } catch (IOException ignored) {
            // Either end going away is how a session normally ends, not a failure to report.
        }
    }

    /// Stops accepting, drops every connection and removes the socket file.
    ///
    /// The first step of the shutdown sequence. Safe to call more than once, because the terminal
    /// closing, a signal and `oillamp stop` can all start a shutdown.
    @Override public void close() {
        closing = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed, or never opened. Either way there is nothing left to do about it.
        }
        for (SocketChannel channel : live) closeQuietly(channel);
        live.clear();
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
            // A socket file we cannot remove is tidied by the next session, which unlinks first.
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing is best-effort by definition; the fd is going away with the process anyway.
        }
    }
}
