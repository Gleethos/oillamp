package dev.oillamp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Tuple;

/// The egress proxy: the sandbox's only way out to the network, plus the configured forwards.
///
/// The container runs with `--network=none`: no route, no DNS, only a loopback interface.
/// Inside it, a socat relay listens on `127.0.0.1:3128` and forwards each connection to a
/// Unix socket. On the host side of that socket, this class speaks HTTP proxy, asks [Policy]
/// whether the connection is allowed, resolves the name on the host, and connects on the agent's
/// behalf. A program that ignores the proxy variables has no network at all.
///
/// TLS is never intercepted. A `CONNECT` tunnel is copied byte for byte, so oillamp sees the
/// host name, the port and the resolved address, never the content.
///
/// How requests are handled and logged is described in `docs/ARCHITECTURE.md`,
/// "The network".
final class Egress implements AutoCloseable {

    /// A request head larger than this is not a proxy request.
    private static final int HEADER_LIMIT = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CONNECTIONS = Relay.ACCEPT_QUEUE;
    private static final int BUFFER_BYTES = 64 * 1024;
    /// The rule name logged for a host name that could not be resolved.
    private static final String UNRESOLVED = "unresolved";
    private static final ObjectMapper JSON = new ObjectMapper();

    /// What the supervisor is told as it happens. Called from connection threads.
    interface Listener {
        void denied(Journey journey);
        void trouble(Problem problem);
    }

    /// One connection, as written to the network log: host, port, resolved address, decision,
    /// deciding rule, byte counts and duration. Never content.
    record Journey(Instant at, String channel, String method, String host, int port,
                  Optional<IpAddress> address, Decision decision, String rule,
                  long bytesUp, long bytesDown, Duration took) {

        String toJson() {
            ObjectNode line = JSON.createObjectNode()
                .put("ts", at.toString()).put("channel", channel).put("method", method)
                .put("host", host).put("port", port)
                .put("address", address.map(IpAddress::text).orElse(null))
                .put("decision", decision.configName()).put("rule", rule)
                .put("bytesUp", bytesUp).put("bytesDown", bytesDown).put("ms", took.toMillis());
            return line.toString();
        }

        String describe() {
            return method + " " + host + ":" + port
                 + address.map(a -> " (" + a.text() + ")").orElse("")
                 + " — " + decision.configName() + " by rule \"" + rule + "\"";
        }
    }

    private final NetworkPolicy policy;
    private final boolean logAllowed;
    private final Listener listener;
    private final Journal journal;
    private final List<ServerSocketChannel> servers = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> live = new CopyOnWriteArrayList<>();
    private final AtomicLong open = new AtomicLong();
    private volatile boolean closing;

    private Egress(NetworkPolicy policy, boolean logAllowed, Listener listener, Journal journal) {
        this.policy = policy;
        this.logAllowed = logAllowed;
        this.listener = listener;
        this.journal = journal;
    }

    /// Starts the proxy and every configured forward.
    ///
    /// The sockets are mode 0666 rather than 0600, because the socat relay inside the container
    /// runs as the infra user, a different uid. They are still private to this user on the host,
    /// because the enclosing `.oillamp` directory is 0700.
    static Result<Egress> open(LampLayout layout, LampConfig config, SessionId session,
                               Listener listener) {
        Journal journal = new Journal(layout.networkLog(session));
        Egress egress = new Egress(config.network(), config.network().logAllowed(),
                                   listener, journal);
        Result<ServerSocketChannel> proxy = bindShared(layout.proxySocket());
        if (proxy instanceof Result.Err<ServerSocketChannel> failure) {
            egress.close();   // stops the network log's writer thread
            return Result.err(failure.problems());
        }
        egress.serve(((Result.Ok<ServerSocketChannel>) proxy).value(), egress::handleProxy, "proxy");

        for (Forward forward : config.forwards()) {
            Result<ServerSocketChannel> bound = bindShared(layout.forwardSocket(forward.name()));
            if (bound instanceof Result.Err<ServerSocketChannel> failure) {
                egress.close();
                return Result.err(failure.problems());
            }
            egress.serve(((Result.Ok<ServerSocketChannel>) bound).value(),
                    client -> egress.handleForward(client, forward), "fwd-" + forward.name());
        }
        return Result.ok(egress);
    }

    private static Result<ServerSocketChannel> bindShared(Path socket) {
        Result<ServerSocketChannel> bound = Relay.bind(socket);
        if (bound instanceof Result.Ok<ServerSocketChannel>) {
            try {
                // Relay.bind makes the socket 0600. The relay inside the container runs as the
                // infra user, a different uid, so it needs 0666 to connect.
                Filesystem.setMode(socket, PosixMode.SHARED_SOCKET);
            } catch (IOException e) {
                return Result.err(Problems.cannotListen(socket, Problems.reason(e)));
            }
        }
        return bound;
    }

    private interface Handler { void handle(SocketChannel client); }

    private void serve(ServerSocketChannel server, Handler handler, String name) {
        servers.add(server);
        Thread.ofVirtual().name("oillamp-egress-" + name).start(() -> {
            while (server.isOpen()) {
                SocketChannel client;
                try {
                    client = server.accept();
                } catch (IOException e) {
                    if (!closing) listener.trouble(Problems.cannotListen(Path.of(name), Problems.reason(e)));
                    return;
                }
                // 512 open connections is far more than any build needs; a sandbox opening more is
                // probably stuck in a loop. Close extra connections instead of growing without limit.
                if (open.get() >= MAX_CONNECTIONS) {
                    closeQuietly(client);
                    continue;
                }
                open.incrementAndGet();
                live.add(client);
                Thread.ofVirtual().name("oillamp-egress-connection").start(() -> {
                    try {
                        handler.handle(client);
                    } finally {
                        open.decrementAndGet();
                        live.remove(client);
                        closeQuietly(client);
                    }
                });
            }
        });
    }

    // ─── the proxy ─────────────────────────────────────────────────────────────────────────

    private void handleProxy(SocketChannel client) {
        Instant started = Instant.now();
        InputStream in = Channels.newInputStream(client);
        OutputStream out = Channels.newOutputStream(client);
        try {
            Optional<Head> parsed = readHead(in);
            if (parsed.isEmpty()) return;      // the client went away mid-request
            Head head = parsed.get();
            if (head.badRequest().isPresent()) {
                respond(out, 400, head.badRequest().get());
                return;
            }
            if (head.method().equals("CONNECT")) tunnel(in, out, head, started);
            else forwardHttp(in, out, head, started);
        } catch (IOException e) {
            // Either end going away mid-connection is ordinary; the agent sees the failure.
            noteTrouble(e);
        }
    }

    /// `CONNECT host:port`, used for HTTPS and therefore most real traffic: resolve, check the
    /// policy, connect, answer `200`, then copy bytes both ways until one side closes. Nothing
    /// in the tunnel is read.
    private void tunnel(InputStream in, OutputStream out,
                        Head head, Instant started) throws IOException {
        Resolution resolution = resolve(head.host());
        if (resolution.failed()) {
            respond(out, 502, "oillamp: cannot resolve " + head.host());
            record(new Journey(Instant.now(), "proxy", "CONNECT", head.host(), head.port(),
                    Optional.empty(), Decision.DENY, UNRESOLVED, 0, 0,
                    Duration.between(started, Instant.now())));
            return;
        }
        Policy.Verdict verdict = Policy.decide(policy, head.host(), head.port(), resolution.addresses());
        if (!verdict.allowed()) {
            respond(out, 403, verdict.explain(head.host(), head.port()));
            record(new Journey(Instant.now(), "proxy", "CONNECT", head.host(), head.port(),
                    verdict.address(), verdict.decision(), verdict.rule(), 0, 0,
                    Duration.between(started, Instant.now())));
            return;
        }
        Socket upstream;
        try {
            upstream = connect(resolution.pick(verdict), head.port());
        } catch (IOException e) {
            respond(out, 504, "oillamp: cannot reach " + head.host() + ":" + head.port()
                            + " — " + Problems.reason(e));
            record(new Journey(Instant.now(), "proxy", "CONNECT", head.host(), head.port(),
                    verdict.address(), verdict.decision(), verdict.rule(), 0, 0,
                    Duration.between(started, Instant.now())));
            return;
        }
        live.add(upstream);
        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        AtomicLong up = new AtomicLong();
        AtomicLong down = new AtomicLong();
        pipeBothWays(in, out, upstream, up, down);
        live.remove(upstream);
        closeQuietly(upstream);
        record(new Journey(Instant.now(), "proxy", "CONNECT", head.host(), head.port(),
                verdict.address(), verdict.decision(), verdict.rule(), up.get(), down.get(),
                Duration.between(started, Instant.now())));
    }

    /// An absolute-form request (`GET http://host/path`), forwarded in origin form.
    ///
    /// Plain HTTP, which in practice means apt and some redirects. One request per connection:
    /// `Connection: close` is sent and the response is streamed back, so there is no
    /// keep-alive to manage.
    private void forwardHttp(InputStream in, OutputStream out, Head head, Instant started)
            throws IOException {
        Resolution resolution = resolve(head.host());
        if (resolution.failed()) {
            respond(out, 502, "oillamp: cannot resolve " + head.host());
            return;
        }
        Policy.Verdict verdict = Policy.decide(policy, head.host(), head.port(), resolution.addresses());
        if (!verdict.allowed()) {
            respond(out, 403, verdict.explain(head.host(), head.port()));
            record(new Journey(Instant.now(), "proxy", head.method(), head.host(), head.port(),
                    verdict.address(), verdict.decision(), verdict.rule(), 0, 0,
                    Duration.between(started, Instant.now())));
            return;
        }
        Socket upstream;
        try {
            upstream = connect(resolution.pick(verdict), head.port());
        } catch (IOException e) {
            respond(out, 502, "oillamp: cannot reach " + head.host() + ":" + head.port());
            return;
        }
        live.add(upstream);
        AtomicLong up = new AtomicLong();
        AtomicLong down = new AtomicLong();
        try {
            byte[] request = head.originForm().getBytes(StandardCharsets.ISO_8859_1);
            upstream.getOutputStream().write(request);
            upstream.getOutputStream().flush();
            up.addAndGet(request.length);
            pipeBothWays(in, out, upstream, up, down);
        } finally {
            live.remove(upstream);
            closeQuietly(upstream);
            record(new Journey(Instant.now(), "proxy", head.method(), head.host(), head.port(),
                    verdict.address(), verdict.decision(), verdict.rule(), up.get(), down.get(),
                    Duration.between(started, Instant.now())));
        }
    }

    // ─── forwards ──────────────────────────────────────────────────────────────────────────

    /// One connection to a forward's fixed target, without a policy check.
    ///
    /// The host resolves the name and connects, so the host's VPN and routing apply. This is how
    /// a user gives the sandbox one internal service without opening the rest of their network.
    private void handleForward(SocketChannel client, Forward forward) {
        Instant started = Instant.now();
        AtomicLong up = new AtomicLong();
        AtomicLong down = new AtomicLong();
        Socket upstream = null;
        try {
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(forward.target().host(), forward.target().port()),
                             (int) CONNECT_TIMEOUT.toMillis());
            live.add(upstream);
            pipeBothWays(Channels.newInputStream(client), Channels.newOutputStream(client),
                         upstream, up, down);
        } catch (IOException e) {
            listener.trouble(Problems.forwardUnreachable(forward, Problems.reason(e)));
        } finally {
            if (upstream != null) {
                live.remove(upstream);
                closeQuietly(upstream);
            }
            record(new Journey(Instant.now(), "forward:" + forward.name(), "TCP",
                    forward.target().host(), forward.target().port(), Optional.empty(),
                    Decision.ALLOW, "forward", up.get(), down.get(),
                    Duration.between(started, Instant.now())));
        }
    }

    // ─── plumbing ──────────────────────────────────────────────────────────────────────────

    /// Resolved addresses in resolver order, or a failure. Resolution happens on the host.
    private record Resolution(Tuple<IpAddress> addresses, boolean failed) {

        /// The address the verdict allowed, or the first address if the verdict has none.
        ///
        /// Built from the address's bytes, never parsed from text again, so the connection goes to
        /// exactly the address the policy judged. Parsing text a second time would let Java's idea
        /// of the address differ from the policy's.
        InetAddress pick(Policy.Verdict verdict) throws IOException {
            IpAddress chosen = verdict.address().orElseGet(addresses::first);
            return InetAddress.getByAddress(chosen.toBytes());
        }
    }

    /// Resolves on the host, with a deadline.
    ///
    /// `getAllByName` has no timeout of its own, and a resolver that stops answering would
    /// make the agent's connection hang instead of failing.
    private Resolution resolve(String host) {
        Optional<IpAddress> literal = IpAddress.parse(host);
        if (literal.isPresent()) return new Resolution(Tuple.of(IpAddress.class, literal.get()), false);
        var task = new java.util.concurrent.FutureTask<>(() -> InetAddress.getAllByName(host));
        Thread.ofVirtual().name("oillamp-resolve").start(task);
        try {
            InetAddress[] found = task.get(RESOLVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Tuple<IpAddress> addresses = Tuple.of(IpAddress.class);
            for (InetAddress address : found) {
                String text = address.getHostAddress();
                int scope = text.indexOf('%');            // fe80::1%eth0 — the zone is not an address
                if (scope >= 0) text = text.substring(0, scope);
                Optional<IpAddress> parsed = IpAddress.parse(text);
                if (parsed.isPresent()) addresses = addresses.add(parsed.get());
            }
            return new Resolution(addresses, addresses.isEmpty());
        } catch (Exception failed) {
            task.cancel(true);
            return new Resolution(Tuple.of(IpAddress.class), true);
        }
    }

    private static Socket connect(InetAddress address, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address, port), (int) CONNECT_TIMEOUT.toMillis());
        return socket;
    }

    /// Copies both directions concurrently and returns when both have finished.
    private static void pipeBothWays(InputStream clientIn, OutputStream clientOut,
                                     Socket upstream, AtomicLong up, AtomicLong down)
            throws IOException {
        Thread outbound = Thread.ofVirtual().start(() -> {
            copy(clientIn, quietly(upstream), up);
            try {
                upstream.shutdownOutput();
            } catch (IOException ignored) {
                // Already closed by the far side; nothing left to half-close.
            }
        });
        copy(inputOf(upstream), clientOut, down);
        try {
            outbound.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static OutputStream quietly(Socket socket) {
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            return OutputStream.nullOutputStream();
        }
    }

    private static InputStream inputOf(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    private static void copy(InputStream from, OutputStream to, AtomicLong counter) {
        byte[] buffer = new byte[BUFFER_BYTES];
        try {
            int read;
            while ((read = from.read(buffer)) >= 0) {
                to.write(buffer, 0, read);
                to.flush();
                counter.addAndGet(read);
            }
        } catch (IOException ignored) {
            // A closed connection is how these end; the byte counts already recorded stand.
        }
    }

    private void record(Journey entry) {
        // A name that does not resolve is logged as refused, but it is not the policy refusing it,
        // so it is not printed as "denied": that would send the user looking for a rule to change.
        if (entry.decision() == Decision.DENY && !entry.rule().equals(UNRESOLVED)) listener.denied(entry);
        if (entry.decision() == Decision.ALLOW && !logAllowed) return;
        journal.write(entry);
    }

    private void noteTrouble(IOException e) {
        if (!closing) journal.write(Problems.reason(e));
    }

    private static void respond(OutputStream out, int status, String body) throws IOException {
        String reason = switch (status) {
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 502 -> "Bad Gateway";
            case 504 -> "Gateway Timeout";
            default  -> "Error";
        };
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] head = ("HTTP/1.1 " + status + " " + reason + "\r\n"
                     + "Content-Type: text/plain; charset=utf-8\r\n"
                     + "Content-Length: " + bytes.length + "\r\n"
                     + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        // Head and body in one write. The body of a 403 names the rule that refused the
        // connection, and some clients read only once; written separately, they could see the
        // status without the reason.
        byte[] whole = new byte[head.length + bytes.length];
        System.arraycopy(head, 0, whole, 0, head.length);
        System.arraycopy(bytes, 0, whole, head.length, bytes.length);
        out.write(whole);
        out.flush();
    }

    // ─── request parsing ───────────────────────────────────────────────────────────────────

    /// A parsed request head. `badRequest` is non-null when it cannot be served.
    private record Head(String method, String host, int port, String target,
                        List<String> headers, Optional<String> badRequest) {

        /// The request as the origin server wants it: path only, hop-by-hop headers removed.
        String originForm() {
            StringBuilder out = new StringBuilder(method).append(' ').append(target)
                    .append(" HTTP/1.1\r\n");
            boolean sawHost = false;
            for (String header : headers) {
                String name = header.substring(0, Math.max(0, header.indexOf(':')))
                        .trim().toLowerCase(Locale.ROOT);
                if (name.equals("host")) sawHost = true;
                if (HOP_BY_HOP.contains(name) || name.startsWith("proxy-")) continue;
                out.append(header).append("\r\n");
            }
            if (!sawHost) out.append("Host: ").append(host).append("\r\n");
            return out.append("Connection: close\r\n\r\n").toString();
        }
    }

    private static final List<String> HOP_BY_HOP = List.of(
            "connection", "keep-alive", "te", "trailer", "upgrade", "proxy-connection");

    /// Reads the request head, one byte at a time, up to the blank line.
    ///
    /// One byte at a time on purpose. A buffered read could also consume the first bytes of the
    /// tunnelled data, which the tunnel would then never forward, and the TLS handshake would hang.
    private static Optional<Head> readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int consecutive = 0;
        while (head.length() < HEADER_LIMIT) {
            int b = in.read();
            if (b < 0) return head.isEmpty() ? Optional.empty()
                                             : Optional.of(bad("the request ended mid-header"));
            head.append((char) b);
            if (b == '\n') {
                if (++consecutive == 2) return Optional.of(parse(head.toString()));
            } else if (b != '\r') {
                consecutive = 0;
            }
        }
        return Optional.of(bad("the request head is larger than " + HEADER_LIMIT + " bytes"));
    }

    private static Head bad(String why) {
        return new Head("", "", 0, "", List.of(), Optional.of("oillamp: " + why));
    }

    private static Head parse(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (lines.isEmpty() || lines.getFirst().isBlank()) return bad("empty request");
        String[] parts = lines.getFirst().split(" ", -1);
        if (parts.length < 3) return bad("malformed request line");
        String method = parts[0];
        String target = parts[1];
        // What the proxy prints and logs comes from these two, so only what a real client
        // sends gets further: a method is a plain word, a host a name or an address.
        if (!METHOD.matcher(method).matches())
            return bad("the request line starts with something that is not a valid method");
        List<String> headers = new ArrayList<>(lines.subList(1, lines.size()));
        headers.removeIf(String::isBlank);

        if (method.equals("CONNECT")) {
            int colon = target.lastIndexOf(':');
            if (colon < 0) return bad("CONNECT needs host:port, got '" + target + "'");
            Optional<Integer> port = port(target.substring(colon + 1));
            if (port.isEmpty()) return bad("CONNECT has a port that is not a number");
            if (!HOST.matcher(target.substring(0, colon)).matches())
                return bad("that is not a valid host to connect to");
            return new Head(method, HostPattern.normalise(target.substring(0, colon)),
                            port.get(), target, headers, Optional.empty());
        }
        if (target.startsWith("http://")) {
            String rest = target.substring("http://".length());
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            int colon = authority.lastIndexOf(':');
            String host = colon < 0 ? authority : authority.substring(0, colon);
            Optional<Integer> port = colon < 0 ? Optional.of(80)
                                               : port(authority.substring(colon + 1));
            if (port.isEmpty()) return bad("the request URL has a port that is not a number");
            if (!HOST.matcher(host).matches())
                return bad("the host in the request URL is not a valid host name or address");
            return new Head(method, HostPattern.normalise(host), port.get(), path,
                            headers, Optional.empty());
        }
        if (target.startsWith("https://"))
            return bad("an https:// URL must be sent as CONNECT, not as an absolute-form request");
        // A request like "GET /path" means a client is treating the proxy as a web server.
        // Say so, rather than answering with a bare 400.
        return bad("this is oillamp's egress proxy, not a web server — "
                 + "set HTTP_PROXY/HTTPS_PROXY (they are already set in a login shell)");
    }

    /// An HTTP method: letters, digits and the few symbols the standard allows in a token.
    private static final java.util.regex.Pattern METHOD =
            java.util.regex.Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,32}");
    /// A host name, an IPv4 address, or an IPv6 address in brackets.
    private static final java.util.regex.Pattern HOST =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]");

    private static Optional<Integer> port(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 && value <= 65535 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    // ─── the network log ───────────────────────────────────────────────────────────────────

    /// One writer thread behind a queue, so a slow disk never delays a connection.
    ///
    /// If the queue is full, the line is dropped rather than delaying the connection.
    private static final class Journal implements AutoCloseable {

        private final Path file;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>(4096);
        private final Thread writer;
        private volatile boolean closing;

        Journal(Path file) {
            this.file = file;
            this.writer = Thread.ofVirtual().name("oillamp-network-log").start(this::drain);
        }

        void write(Journey journey) { lines.offer(journey.toJson()); }

        void write(String note) {
            lines.offer(JSON.createObjectNode().put("ts", Instant.now().toString())
                            .put("channel", "proxy").put("note", note).toString());
        }

        private void drain() {
            while (!closing || !lines.isEmpty()) {
                String line;
                try {
                    line = lines.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (line == null) continue;
                try {
                    Path parent = file.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException cannotWrite) {
                    // The network log is a record, not a control path. A session that cannot write
                    // it still works, and failing the session over it would be the wrong trade.
                }
            }
        }

        @Override public void close() {
            closing = true;
            try {
                writer.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ─── lifecycle ─────────────────────────────────────────────────────────────────────────

    /// Whether the proxy socket accepts connections. Used by the session's health check.
    static boolean answers(Path socket) { return Relay.answers(socket); }

    @Override public void close() {
        closing = true;
        for (ServerSocketChannel server : servers) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Already closed; the session is ending either way.
            }
        }
        for (AutoCloseable channel : live) closeQuietly(channel);
        live.clear();
        journal.close();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort by definition; the process is releasing these anyway.
        }
    }
}
