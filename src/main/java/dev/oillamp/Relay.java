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

/**
 * A host-side socket that forwards into the sandbox — spec §17.3, §26.3 (D-09).
 *
 * <p>The sandbox is reached only over Unix sockets, and the two that matter live in different
 * places for a reason. The container binds {@code sockets/agent/ssh.sock}, which it can see; the
 * supervisor binds {@code run/ssh-primary.sock} and {@code run/ssh.sock}, which it cannot. Each
 * connection to one is copied into the other. That extra hop buys the thing the whole session
 * lifecycle depends on: the agent <em>cannot</em> reach the primary socket, so it cannot take the
 * slot that decides when the session ends, and closing the terminal window is a signal only a
 * human can send.
 *
 * <p>The primary accepts exactly one connection per session; the extra relay accepts any number,
 * because {@code oillamp shell} is supposed to be openable as often as the user likes.
 *
 * <p>Deliberately <b>package-private</b>: the relay of D-09. On the effects allowlist — it binds
 * sockets and moves bytes, which is as impure as this codebase gets.
 */
final class Relay implements AutoCloseable {

    /**
     * The kernel's limit on a Unix socket path, minus the terminating NUL. Lamps live where the
     * user keeps their projects, which is why the sockets live under {@code $XDG_RUNTIME_DIR}
     * instead (D-25) — but a long username or a custom runtime dir can still exceed it, and the
     * failure for that is an {@code EINVAL} from {@code bind} that explains nothing.
     */
    static final int MAX_SOCKET_PATH = 107;

    private static final int BUFFER_BYTES = 64 * 1024;

    /** What the supervisor is told, as it happens. Every method is called from a relay thread. */
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

    /**
     * Binds {@code socket} and forwards everything it receives to {@code target}.
     *
     * @param maxConnections {@code 1} for the primary (D-09), or {@link Integer#MAX_VALUE}
     */
    static Result<Relay> open(Path socket, Path target, int maxConnections, Listener listener) {
        return bind(socket).map(server -> {
            Relay relay = new Relay(socket, target, maxConnections, listener, server);
            Thread.ofVirtual().name("oillamp-relay-" + socket.getFileName()).start(relay::acceptLoop);
            return relay;
        });
    }

    /**
     * Binds a host-only Unix socket at 0600 — used by the relays and by the control socket.
     *
     * <p>Two details are the whole reason this is one function rather than three call sites. The
     * path is measured first, because a path over the kernel's limit fails with an {@code EINVAL}
     * from {@code bind} that mentions neither the path nor the limit. And the file is unlinked
     * first, because a socket file left by a crashed session is not a listener: binding over it
     * fails with "address already in use" for an address nothing is using. That is the host-side
     * twin of what the entrypoint learned to do inside the container (§16).
     */
    static Result<ServerSocketChannel> bind(Path socket) {
        int length = socket.toString().getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_SOCKET_PATH)
            return Result.err(Problems.socketPathTooLong(socket, MAX_SOCKET_PATH));
        try {
            Path parent = socket.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.deleteIfExists(socket);
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            // 0600 as soon as it exists: these sockets are the session's control points, and the
            // reason they live in the host-only runtime directory is that nothing else may reach
            // them — least of all the agent.
            Filesystem.setMode(socket, PosixMode.PRIVATE_FILE);
            return Result.ok(server);
        } catch (IOException e) {
            return Result.err(Problems.cannotListen(socket, Problems.reason(e)));
        }
    }

    /**
     * Whether something is listening on this socket right now.
     *
     * <p>The same question the readiness check asks during startup, asked again while the session
     * runs. Connecting is the only form of it that distinguishes a server from a file with the
     * right name — and a session whose desktop died an hour in looks, from any cheaper check,
     * exactly like one that is fine.
     */
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
                // §17.3: the primary slot belongs to the terminal oillamp opened. A second
                // connection is either a mistake or someone trying to take over the session's
                // lifetime, and neither should be answered with a shell.
                closeQuietly(client);
                listener.trouble(Problems.extraPrimaryRejected(socket));
                continue;
            }
            Thread.ofVirtual().name("oillamp-relay-connection").start(() -> serve(client));
        }
    }

    /** One connection: open the far side, copy both ways, and report it when it ends. */
    private void serve(SocketChannel client) {
        SocketChannel sandbox;
        try {
            sandbox = SocketChannel.open(UnixDomainSocketAddress.of(target));
        } catch (IOException e) {
            // The sandbox's own socket is gone or refusing. The user sees this as a terminal
            // window that opens and closes, so it has to be said out loud rather than logged.
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

    /**
     * Moves bytes until one end stops talking, then half-closes the other.
     *
     * <p>The half-close matters for SSH: the client sends EOF when the user types {@code exit},
     * and a relay that closed both directions at that moment would cut off the server's goodbye.
     */
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

    /** True once the one connection the primary allows has been used (§17.3). */
    public boolean isTaken() { return accepted.get() >= maxConnections; }

    /**
     * Stops accepting, drops every connection and removes the socket file.
     *
     * <p>Step 1 of the shutdown sequence (§10.7), and idempotent, because the shutdown sequence
     * can be reached from the terminal closing, a signal and {@code oillamp stop} at once.
     */
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
