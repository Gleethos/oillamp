package dev.oillamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Association;
import sprouts.Pair;
import sprouts.Tuple;

/// The control socket, through which another oillamp process talks to a running session.
///
/// `view`, `shell`, `stop` and `status` send requests to the supervisor
/// rather than acting on the lamp themselves, because the supervisor holds the lock, the relays and
/// the session state. A `stop` that removed the container directly would leave the supervisor
/// believing its session was still running.
///
/// The socket is [LampLayout#controlSocket()]:
/// `$XDG_RUNTIME_DIR/oillamp/<agent id>/run/control.sock`, usually under `/run/user/<uid>`. It is
/// outside the lamp and never mounted into the container, so only processes on the host, and only
/// this user's (mode 0600), can reach it. Both ends are oillamp: the container takes no part.
///
/// One exchange, for example `oillamp stop`:
///
/// 1. The new process calls [#ask], which connects and writes one line, `{"op":"stop"}`.
/// 2. In the supervisor, [Server] accepts the connection on a thread of its own, reads the line
///    into a [Request], and calls the [Handler], which is `Supervisor.answer`.
/// 3. The handler returns a [Reply]. For `stop` it has only posted an event; the session's event
///    loop does the rest.
/// 4. [Server] writes the reply as one line, `{"ok":true,"state":"shutting-down"}`, and the
///    connection closes.
///
/// One request, `follow`, is answered with more than one line: after the reply, the session writes
/// each of its events as a line of JSON, the same lines an embedded session writes to its standard
/// output, until the session ends or the follower hangs up.
///
/// The protocol is one JSON object per line and one request per connection, simple enough for a
/// future front end to use as well. It is only spoken between processes of the same oillamp
/// version, so it has no versioning. You can speak it from a shell:
/// `echo '{"op":"status"}' | socat - UNIX-CONNECT:<control.sock>`.
final class Control {

    private Control() {}


    /// What a second oillamp is asking for.
    record Request(String op, Association<String, String> arguments) {

        public static Request of(String op) {
            return new Request(op, Association.between(String.class, String.class));
        }

        public Request with(String key, String value) {
            return new Request(op, arguments.put(key, value));
        }

        public boolean flag(String key) { return arguments.get(key).orElse("false").equals("true"); }

        public String render() {
            ObjectNode node = Json.object();
            node.put("op", op);
            for (Pair<String, String> argument : arguments)
                node.put(argument.first(), argument.second());
            return node.toString();
        }

        public static Optional<Request> parse(String line) {
            try {
                JsonNode node = Json.READER.readTree(line);
                JsonNode op = node.get("op");
                if (op == null || !op.isTextual()) return Optional.empty();
                Association<String, String> arguments = Association.between(String.class, String.class);
                for (var field : node.properties())
                    if (!field.getKey().equals("op"))
                        arguments = arguments.put(field.getKey(), field.getValue().asText());
                return Optional.of(new Request(op.asText(), arguments));
            } catch (JacksonException e) {
                return Optional.empty();
            }
        }
    }

    /// What the supervisor answers.
    ///
    /// @param values named facts, for `status` and for whatever a front end wants to show
    /// @param argv   a command the asking process should run itself; used by `shell`
    /// @param stream what is written after the reply, on the same connection; used by `follow`
    record Reply(boolean succeeded, Association<String, String> values, Tuple<String> argv,
                 Optional<Stream> stream) {

        public static Reply ok() {
            return new Reply(true, Association.between(String.class, String.class),
                             Tuple.of(String.class), Optional.empty());
        }

        public static Reply failed(String why) { return Reply.ok().with("error", why).asFailure(); }

        public Reply with(String key, String value) {
            return new Reply(succeeded, values.put(key, value), argv, stream);
        }

        public Reply withArgv(Tuple<String> argv) { return new Reply(succeeded, values, argv, stream); }

        public Reply withStream(Stream stream) { return new Reply(succeeded, values, argv, Optional.of(stream)); }

        private Reply asFailure() { return new Reply(false, values, argv, stream); }

        public String error() { return values.get("error").orElse("the session did not say why"); }

        public String render() {
            ObjectNode node = Json.object();
            node.put("ok", succeeded);
            for (Pair<String, String> value : values)
                node.put(value.first(), value.second());
            if (!argv.isEmpty()) {
                var array = node.putArray("argv");
                for (String argument : argv) array.add(argument);
            }
            return node.toString();
        }

        public static Reply parse(String line) {
            try {
                JsonNode node = Json.READER.readTree(line);
                Association<String, String> values = Association.between(String.class, String.class);
                for (var field : node.properties())
                    if (!field.getKey().equals("ok") && !field.getKey().equals("argv"))
                        values = values.put(field.getKey(), field.getValue().asText());
                Tuple<String> argv = Tuple.of(String.class);
                JsonNode array = node.get("argv");
                if (array != null && array.isArray())
                    for (JsonNode element : array) argv = argv.add(element.asText());
                JsonNode ok = node.get("ok");
                return new Reply(ok != null && ok.asBoolean(), values, argv, Optional.empty());
            } catch (JacksonException e) {
                return Reply.failed("the session answered with something that is not JSON");
            }
        }
    }

    /// Lines written after a reply, one at a time, until there are no more.
    interface Stream {
        /// Writes every line to `out`, and returns when there are no more. Stops at the first
        /// line `out` cannot take, because the other end has hung up.
        void writeTo(Line out) throws IOException, InterruptedException;
    }

    /// Where a [Stream] writes. Takes a line without its line break.
    interface Line {
        void write(String line) throws IOException;
    }

    /// Answers control requests. Called on a control-socket thread, never on the event loop.
    interface Handler {
        Reply handle(Request request);
    }

    /// The listening half, owned by the supervisor for the length of the session.
    static final class Server implements AutoCloseable {

        private final Path socket;
        private final ServerSocketChannel channel;
        private volatile boolean closing;

        private Server(Path socket, ServerSocketChannel channel) {
            this.socket = socket;
            this.channel = channel;
        }

        static Result<Server> open(Path socket, Handler handler) {
            return Relay.bind(socket).map(channel -> {
                Server server = new Server(socket, channel);
                Thread.ofVirtual().name("oillamp-control").start(() -> server.acceptLoop(handler));
                return server;
            });
        }

        /// Each connection gets its own thread, so one that is slow to send its request does not
        /// hold up the others.
        private void acceptLoop(Handler handler) {
            while (channel.isOpen()) {
                SocketChannel client;
                try {
                    client = channel.accept();
                } catch (IOException e) {
                    if (closing) return;
                    continue;
                }
                Thread.ofVirtual().name("oillamp-control-request").start(() -> serve(client, handler));
            }
        }

        private static void serve(SocketChannel client, Handler handler) {
            try (client) {
                String line = within(REQUEST_TIME, client, () -> readLine(client));
                Reply reply = Request.parse(line)
                        .map(handler::handle)
                        .orElseGet(() -> Reply.failed("that is not a control request"));
                write(client, reply.render() + "\n");
                if (reply.stream().isPresent()) reply.stream().get().writeTo(event -> write(client, event + "\n"));
            } catch (IOException e) {
                // A malformed, abandoned or silent connection must not end the session.
            } catch (InterruptedException e) {
                // The session is going away; so does the connection.
            }
        }

        @Override public void close() {
            closing = true;
            try {
                channel.close();
            } catch (IOException ignored) {
                // Already closed. The socket file is removed below either way.
            }
            try {
                Files.deleteIfExists(socket);
            } catch (IOException ignored) {
                // The next session unlinks before it binds, so a leftover file is harmless.
            }
        }
    }

    /// Asks a running session something.
    ///
    /// No socket file means no session is running (`OIL-SESSION-001`). A socket that does
    /// not answer means a supervisor died without cleaning up (`OIL-SESSION-002`), which
    /// `oillamp stop` can fix. A session that answers but says no, for example because it is
    /// already shutting down, is alive (`OIL-SESSION-003`).
    static Result<Reply> ask(Path socket, Path lamp, Request request, String command) {
        return ask(socket, lamp, request, command, ANSWER_TIME);
    }

    /// The same, for a request whose answer takes longer, such as `ask`, which is answered once
    /// the agent is done.
    static Result<Reply> ask(Path socket, Path lamp, Request request, String command, Duration patience) {
        if (!Files.exists(socket)) return Result.err(Problems.noSessionRunning(lamp, command));
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            write(channel, request.render() + "\n");
            channel.shutdownOutput();
            Reply reply = Reply.parse(within(patience, channel, () -> readLine(channel)));
            return reply.succeeded() ? Result.ok(reply)
                              : Result.err(Problems.sessionRefused(lamp, command, reply.error()));
        } catch (IOException e) {
            return Result.err(Problems.supervisorUnreachable(socket, Problems.reason(e)));
        }
    }

    /// Asks a running session for a request answered with a stream, such as `follow`, and passes
    /// each line after the reply to `eachLine`, until the session ends the stream or `until`
    /// reaches its end.
    ///
    /// @param until for an embedded `follow`, the standard input the application holds; empty to
    ///              follow for as long as the session runs
    /// @return the reply, once the stream has ended; failing as [#ask] does
    static Result<Reply> follow(Path socket, Path lamp, Request request, String command,
                                Consumer<String> eachLine,
                                Optional<InputStream> until) {
        if (!Files.exists(socket)) return Result.err(Problems.noSessionRunning(lamp, command));
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            until.ifPresent(input -> Thread.ofVirtual().name("oillamp-follow-until").start(() -> {
                try (input) {
                    byte[] ignored = new byte[4096];
                    while (input.read(ignored) >= 0) { /* keep reading until it closes */ }
                } catch (IOException closed) {
                    // Gone, which ends following all the same.
                }
                closeQuietly(channel);
            }));
            write(channel, request.render() + "\n");
            var lines = new BufferedReader(new InputStreamReader(
                    Channels.newInputStream(channel), StandardCharsets.UTF_8));
            String first = within(ANSWER_TIME, channel, lines::readLine);
            Reply reply = Reply.parse(first == null ? "" : first);
            if (!reply.succeeded()) return Result.err(Problems.sessionRefused(lamp, command, reply.error()));
            try {
                String line;
                while ((line = lines.readLine()) != null) eachLine.accept(line);
            } catch (IOException ended) {
                // Stopped from this end, or the session went away: either way the stream is over.
            }
            return Result.ok(reply);
        } catch (IOException e) {
            return Result.err(Problems.supervisorUnreachable(socket, Problems.reason(e)));
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException alreadyClosed) {
            // Then the reading has ended already.
        }
    }

    // ─── reading and writing one line ──────────────────────────────────────────────────────

    /// How long the session waits for a connection to send its request. Every oillamp command
    /// sends it at once; this only ends connections that never will.
    private static final Duration REQUEST_TIME = Duration.ofSeconds(5);
    /// How long a command waits for the session's answer. Every request is answered at once, so a
    /// session that takes longer is frozen or stuck.
    private static final Duration ANSWER_TIME = Duration.ofSeconds(5);

    private interface Exchange<T> {
        T run() throws IOException;
    }

    /// Runs `exchange`, closing the channel if it takes longer than `limit`. A blocking read on a
    /// channel has no timeout of its own; closing the channel ends it with an exception.
    private static <T> T within(Duration limit, SocketChannel channel, Exchange<T> exchange)
            throws IOException {
        var expired = new AtomicBoolean();
        Thread deadline = Thread.ofVirtual().name("oillamp-control-deadline").start(() -> {
            try {
                Thread.sleep(limit);
                expired.set(true);
                channel.close();
            } catch (InterruptedException | IOException finishedInTime) {
                // Nothing to end.
            }
        });
        try {
            return exchange.run();
        } catch (IOException e) {
            if (expired.get())
                throw new IOException("nothing came back within " + limit.toSeconds() + " seconds", e);
            throw e;
        } finally {
            deadline.interrupt();
        }
    }

    private static String readLine(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        StringBuilder out = new StringBuilder();
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            buffer.clear();
            out.append(new String(bytes, StandardCharsets.UTF_8));
            int newline = out.indexOf("\n");
            if (newline >= 0) return out.substring(0, newline);
            if (out.length() > 256 * 1024) return "";
        }
        return out.toString();
    }

    private static void write(SocketChannel channel, String text) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) channel.write(buffer);
    }
}
