package dev.oillamp;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Association;
import sprouts.Pair;
import sprouts.Tuple;

/**
 * The control socket, through which another oillamp process talks to a running session.
 *
 * <p>{@code view}, {@code shell}, {@code stop} and {@code status} send requests to the supervisor
 * rather than acting on the lamp themselves, because the supervisor holds the lock, the relays and
 * the session state. A {@code stop} that removed the container directly would leave the supervisor
 * believing its session was still running.
 *
 * <p>The protocol is one JSON object per line and one request per connection, simple enough for a
 * future front end to use as well. It is only spoken between processes of the same oillamp
 * version, so it has no versioning.
 */
final class Control {

    private Control() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a second oillamp is asking for. */
    record Request(String op, Association<String, String> arguments) {

        public static Request of(String op) {
            return new Request(op, Association.between(String.class, String.class));
        }

        public Request with(String key, String value) {
            return new Request(op, arguments.put(key, value));
        }

        public boolean flag(String key) { return arguments.get(key).orElse("false").equals("true"); }

        public String render() {
            ObjectNode node = JSON.createObjectNode();
            node.put("op", op);
            for (Pair<String, String> argument : arguments)
                node.put(argument.first(), argument.second());
            return node.toString();
        }

        public static Optional<Request> parse(String line) {
            try {
                JsonNode node = JSON.readTree(line);
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

    /**
     * What the supervisor answers.
     *
     * @param values named facts, for {@code status} and for whatever a front end wants to show
     * @param argv   a command the asking process should run itself; used by {@code shell}
     */
    record Reply(boolean succeeded, Association<String, String> values, Tuple<String> argv) {

        public static Reply ok() {
            return new Reply(true, Association.between(String.class, String.class),
                             Tuple.of(String.class));
        }

        public static Reply failed(String why) { return Reply.ok().with("error", why).asFailure(); }

        public Reply with(String key, String value) {
            return new Reply(succeeded, values.put(key, value), argv);
        }

        public Reply withArgv(Tuple<String> argv) { return new Reply(succeeded, values, argv); }

        private Reply asFailure() { return new Reply(false, values, argv); }

        public String error() { return values.get("error").orElse("the session did not say why"); }

        public String render() {
            ObjectNode node = JSON.createObjectNode();
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
                JsonNode node = JSON.readTree(line);
                Association<String, String> values = Association.between(String.class, String.class);
                for (var field : node.properties())
                    if (!field.getKey().equals("ok") && !field.getKey().equals("argv"))
                        values = values.put(field.getKey(), field.getValue().asText());
                Tuple<String> argv = Tuple.of(String.class);
                JsonNode array = node.get("argv");
                if (array != null && array.isArray())
                    for (JsonNode element : array) argv = argv.add(element.asText());
                JsonNode ok = node.get("ok");
                return new Reply(ok != null && ok.asBoolean(), values, argv);
            } catch (JacksonException e) {
                return Reply.failed("the session answered with something that is not JSON");
            }
        }
    }

    /** Answers control requests. Called on a control-socket thread, never on the event loop. */
    interface Handler {
        Reply handle(Request request);
    }

    /** The listening half, owned by the supervisor for the length of the session. */
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

        private void acceptLoop(Handler handler) {
            while (channel.isOpen()) {
                try (SocketChannel client = channel.accept()) {
                    String line = readLine(client);
                    Reply reply = Request.parse(line)
                            .map(handler::handle)
                            .orElseGet(() -> Reply.failed("that is not a control request"));
                    write(client, reply.render() + "\n");
                } catch (IOException e) {
                    if (closing) return;
                    // A malformed or abandoned connection must not end the session.
                }
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

    /**
     * Asks a running session something.
     *
     * <p>No socket file means no session is running ({@code OIL-SESSION-001}). A socket that does
     * not answer means a supervisor died without cleaning up ({@code OIL-SESSION-002}), which
     * {@code oillamp stop} can fix.
     */
    static Result<Reply> ask(Path socket, Path lamp, Request request, String command) {
        if (!Files.exists(socket)) return Result.err(Problems.noSessionRunning(lamp, command));
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            write(channel, request.render() + "\n");
            channel.shutdownOutput();
            Reply reply = Reply.parse(readLine(channel));
            return reply.succeeded() ? Result.ok(reply)
                              : Result.err(Problems.supervisorUnreachable(socket, reply.error()));
        } catch (IOException e) {
            return Result.err(Problems.supervisorUnreachable(socket, Problems.reason(e)));
        }
    }

    // ─── reading and writing one line ──────────────────────────────────────────────────────

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
