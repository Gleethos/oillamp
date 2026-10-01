package dev.oillamp;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

import dev.lamp.Problem;

import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Tuple;

/// The egress proxy: the sandbox's only way out to the network, plus the configured forwards.
///
/// The container runs with `--network=none`: no route, no DNS, only a loopback interface.
/// Inside it, a socat relay listens on `127.0.0.1:3128` and forwards each connection to a
/// Unix socket. On the host side of that socket, this class speaks HTTP proxy, asks [NetworkPolicyUtil]
/// whether the connection is allowed, resolves the name on the host, and connects on the agent's
/// behalf. A program that ignores the proxy variables has no network at all.
///
/// TLS is never intercepted. A `CONNECT` tunnel is copied byte for byte, so oillamp sees the
/// host name, the port and the resolved address, never the content.
///
/// The third way out is the model relay. The harnesses send their model requests, in plain HTTP,
/// to a socat relay on `127.0.0.1:3129`, which forwards them to [LampLayout#modelSocket()]. Here
/// each request gets the real key in place of the placeholder the sandbox holds, and goes on over
/// HTTPS to [Machine#modelService()], Eden AI's EU endpoint. The key never enters the sandbox.
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

    /// Where model requests go, and the key they go with.
    ///
    /// Its text never shows the key, so that no log line, problem or debugger view built from it
    /// can leak it.
    ///
    /// @param keyEnv the variable the key was read from, named in the answer when there is none
    record Model(URI service, String keyEnv, Optional<String> key) {
        @Override public String toString() {
            return "Model[" + service + ", key from " + keyEnv + ", "
                 + (key.isPresent() ? "held" : "missing") + "]";
        }
    }

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
            ObjectNode line = JsonUtil.object()
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
                               Model model, Listener listener) {
        Journal journal = new Journal(layout.networkLog(session));
        Egress egress = new Egress(config.network(), config.network().logAllowed(),
                                   listener, journal);
        Result<ServerSocketChannel> proxy = bindShared(layout.proxySocket());
        if (proxy instanceof Result.Err<ServerSocketChannel> failure) {
            egress.close();   // stops the network log's writer thread
            return Result.err(failure.problems());
        }
        egress.acceptConnections(((Result.Ok<ServerSocketChannel>) proxy).value(), egress::handleProxy, "proxy");

        for (Forward forward : config.forwards()) {
            Result<ServerSocketChannel> bound = bindShared(layout.forwardSocket(forward.name()));
            if (bound instanceof Result.Err<ServerSocketChannel> failure) {
                egress.close();
                return Result.err(failure.problems());
            }
            egress.acceptConnections(((Result.Ok<ServerSocketChannel>) bound).value(),
                    client -> egress.handleForward(client, forward), "fwd-" + forward.name());
        }

        Result<ServerSocketChannel> relay = bindShared(layout.modelSocket());
        if (relay instanceof Result.Err<ServerSocketChannel> failure) {
            egress.close();
            return Result.err(failure.problems());
        }
        egress.acceptConnections(((Result.Ok<ServerSocketChannel>) relay).value(),
                client -> egress.handleModel(client, model), "model");
        return Result.ok(egress);
    }

    private static Result<ServerSocketChannel> bindShared(Path socket) {
        Result<ServerSocketChannel> bound = Relay.bind(socket);
        if (bound instanceof Result.Ok<ServerSocketChannel>) {
            try {
                // Relay.bind makes the socket 0600. The relay inside the container runs as the
                // infra user, a different uid, so it needs 0666 to connect.
                FilesystemUtil.setMode(socket, PosixMode.SHARED_SOCKET);
            } catch (IOException e) {
                return Result.err(ProblemCatalogUtil.cannotListen(socket, ProblemCatalogUtil.reason(e)));
            }
        }
        return bound;
    }

    private interface Handler { void handle(SocketChannel client); }

    private void acceptConnections(ServerSocketChannel server, Handler handler, String name) {
        servers.add(server);
        Thread.ofVirtual().name("oillamp-egress-" + name).start(() -> {
            while (server.isOpen()) {
                SocketChannel client;
                try {
                    client = server.accept();
                } catch (IOException e) {
                    if (!closing) listener.trouble(ProblemCatalogUtil.cannotListen(Path.of(name), ProblemCatalogUtil.reason(e)));
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
            Optional<Head> parsed = readHead(in, Egress::parse, Egress::bad);
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
        NetworkPolicyUtil.Verdict verdict = NetworkPolicyUtil.decide(policy, head.host(), head.port(), resolution.addresses());
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
                            + " — " + ProblemCatalogUtil.reason(e));
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
        NetworkPolicyUtil.Verdict verdict = NetworkPolicyUtil.decide(policy, head.host(), head.port(), resolution.addresses());
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

    // ─── the model relay ───────────────────────────────────────────────────────────────────

    /// One model request: replace whatever key the sandbox sent with the real one, send the request
    /// to the model service, and stream the answer back as it arrives.
    ///
    /// Only an ordinary request for a path is accepted, such as `POST /v3/chat/completions`. The
    /// relay decides the host itself, so nothing in the request can send the key elsewhere. Every
    /// request is recorded in the network log, without its key or content.
    private void handleModel(SocketChannel client, Model model) {
        Instant started = Instant.now();
        InputStream in = Channels.newInputStream(client);
        OutputStream out = Channels.newOutputStream(client);
        String host = model.service().getHost();
        int port = servicePort(model.service());
        try {
            Optional<ModelRequest> parsed = readHead(in, Egress::parseModelRequest, Egress::badModelRequest);
            if (parsed.isEmpty()) return;      // the client went away mid-request
            ModelRequest request = parsed.get();
            if (request.badRequest().isPresent()) {
                respond(out, 400, request.badRequest().get());
                finishQuietly(client, in);
                return;
            }
            Optional<String> target = serviceTarget(model.service(), request.target());
            if (target.isEmpty()) {
                respond(out, 404, "oillamp: the model service is reached under /v3 from the sandbox, "
                        + "such as `POST /v3/chat/completions`; oillamp sends it on to "
                        + model.service() + ".");
                finishQuietly(client, in);
                return;
            }
            if (model.key().isEmpty()) {
                respond(out, 401, "oillamp: there is no model key for this session. oillamp holds "
                        + "the key, not the sandbox: set " + model.keyEnv() + " in the environment "
                        + "oillamp is started from, then start the session again.");
                record(new Journey(Instant.now(), "model", request.method(), host, port, Optional.empty(),
                        Decision.DENY, "no model key", 0, 0, Duration.between(started, Instant.now())));
                finishQuietly(client, in);
                return;
            }
            Socket upstream;
            try {
                upstream = connectToService(model.service());
            } catch (IOException e) {
                respond(out, 502, "oillamp: cannot reach the model service at " + host + " — "
                        + ProblemCatalogUtil.reason(e));
                return;
            }
            live.add(upstream);
            AtomicLong up = new AtomicLong();
            AtomicLong down = new AtomicLong();
            try {
                byte[] head = request.forService(host, target.get(), model.key().get())
                        .getBytes(StandardCharsets.ISO_8859_1);
                upstream.getOutputStream().write(head);
                upstream.getOutputStream().flush();
                pipeBothWays(in, out, upstream, up, down);
            } finally {
                live.remove(upstream);
                closeQuietly(upstream);
                record(new Journey(Instant.now(), "model", request.method(), host, port,
                        IpAddress.parse(upstream.getInetAddress().getHostAddress()),
                        Decision.ALLOW, "model", up.get(), down.get(),
                        Duration.between(started, Instant.now())));
            }
        } catch (IOException e) {
            noteTrouble(e);
        }
    }

    /// Ends a connection that was answered before its request was read to the end.
    ///
    /// Closing a socket with unread data in it makes the kernel reset the connection, and a client
    /// that sees the reset may never read the answer, which here explains the refusal. So the
    /// answer is followed by the end of this side, and whatever the client still sends is read and
    /// dropped, for a few seconds at most.
    private static void finishQuietly(SocketChannel client, InputStream in) {
        try {
            client.shutdownOutput();
        } catch (IOException gone) {
            return;
        }
        Thread limit = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(FINISH_TIME);
                client.close();
            } catch (InterruptedException | IOException finished) {
                // The client finished first; nothing to cut short.
            }
        });
        try {
            byte[] rest = new byte[BUFFER_BYTES];
            while (in.read(rest) >= 0) { /* dropped */ }
        } catch (IOException closed) {
            // Closed by the limit above, or by the client: either way, done.
        } finally {
            limit.interrupt();
        }
    }

    /// How long a refused client may keep sending before the connection is closed anyway.
    private static final Duration FINISH_TIME = Duration.ofSeconds(3);

    /// Connects to the model service: over TLS, checking that the certificate belongs to its
    /// host, for an `https` service (always, on a real machine); in plain TCP for the stand-in
    /// server a test runs.
    private static Socket connectToService(URI service) throws IOException {
        String host = service.getHost();
        int port = servicePort(service);
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
        if (!"https".equals(service.getScheme())) return plain;
        var tls = (SSLSocket) ((SSLSocketFactory)
                SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
        var parameters = tls.getSSLParameters();
        // Without this, TLS checks that the certificate is valid, but not that it is this host's.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        return tls;
    }

    /// The path a request from the sandbox asks the model service for.
    ///
    /// Inside the sandbox the model's address is always `http://127.0.0.1:3129/v3`, as Eden AI's
    /// is. A service configured with a path of its own, such as `http://127.0.0.1:11434/v1` for
    /// a model server on this machine, gets that path in place of `/v3`. A service without one
    /// gets the request's path unchanged. With a path configured, a request outside `/v3` has no
    /// place on the service, and gets nothing.
    static Optional<String> serviceTarget(URI service, String requested) {
        String base = Optional.ofNullable(service.getRawPath()).orElse("").replaceAll("/+$", "");
        if (base.isEmpty()) return Optional.of(requested);
        boolean underV3 = requested.equals("/v3") || requested.startsWith("/v3/") || requested.startsWith("/v3?");
        return underV3 ? Optional.of(base + requested.substring(3)) : Optional.empty();
    }

    private static int servicePort(URI service) {
        if (service.getPort() > 0) return service.getPort();
        return "https".equals(service.getScheme()) ? 443 : 80;
    }

    /// A model request head. Only its method, path and headers are kept.
    private record ModelRequest(String method, String target, List<String> headers,
                                Optional<String> badRequest) {

        /// The request as the model service receives it: the sandbox's own `Host`,
        /// `Authorization` and connection headers are dropped, and the real ones added.
        String forService(String host, String path, String key) {
            StringBuilder out = new StringBuilder(method).append(' ').append(path)
                    .append(" HTTP/1.1\r\n");
            for (String header : headers) {
                String name = header.substring(0, header.indexOf(':')).trim().toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(name) || name.startsWith("proxy-")
                        || name.equals("host") || name.equals("authorization")) continue;
                out.append(header).append("\r\n");
            }
            return out.append("Host: ").append(host).append("\r\n")
                      .append("Authorization: Bearer ").append(key).append("\r\n")
                      .append("Connection: close\r\n\r\n").toString();
        }
    }

    private static ModelRequest badModelRequest(String why) {
        return new ModelRequest("", "", List.of(), Optional.of("oillamp: " + why));
    }

    private static ModelRequest parseModelRequest(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (lines.isEmpty() || lines.getFirst().isBlank()) return badModelRequest("empty request");
        String[] parts = lines.getFirst().split(" ", -1);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1."))
            return badModelRequest("malformed request line");
        if (!METHOD.matcher(parts[0]).matches() || parts[0].equals("CONNECT")
                || !ORIGIN_PATH.matcher(parts[1]).matches())
            return badModelRequest("this is oillamp's relay to the model service, which takes requests "
                    + "for a path, such as `POST /v3/chat/completions`, sent to "
                    + "http://127.0.0.1:" + Forward.MODEL_PORT);
        List<String> headers = new ArrayList<>(lines.subList(1, lines.size()));
        headers.removeIf(String::isBlank);
        for (String header : headers)
            if (header.indexOf(':') <= 0) return badModelRequest("a header line has no name");
        return new ModelRequest(parts[0], parts[1], headers, Optional.empty());
    }

    /// A path on the model service: starts with `/`, printable ASCII, no spaces.
    private static final Pattern ORIGIN_PATH = Pattern.compile("/[!-~]{0,8191}");

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
            listener.trouble(ProblemCatalogUtil.forwardUnreachable(forward, ProblemCatalogUtil.reason(e)));
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
        InetAddress pick(NetworkPolicyUtil.Verdict verdict) throws IOException {
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
        var task = new FutureTask<>(() -> InetAddress.getAllByName(host));
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
            // TLS has no half-close; the far side ends the exchange instead.
            if (upstream instanceof SSLSocket) return;
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
        if (!closing) journal.write(ProblemCatalogUtil.reason(e));
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

    /// Reads the request head, one byte at a time, up to the blank line, and parses it.
    ///
    /// One byte at a time on purpose. A buffered read could also consume the first bytes of the
    /// tunnelled data, which the tunnel would then never forward, and the TLS handshake would hang.
    private static <T> Optional<T> readHead(InputStream in,
                                            Function<String, T> parse,
                                            Function<String, T> bad)
            throws IOException {
        StringBuilder head = new StringBuilder();
        int consecutive = 0;
        while (head.length() < HEADER_LIMIT) {
            int b = in.read();
            if (b < 0) return head.isEmpty() ? Optional.empty()
                                             : Optional.of(bad.apply("the request ended mid-header"));
            head.append((char) b);
            if (b == '\n') {
                if (++consecutive == 2) return Optional.of(parse.apply(head.toString()));
            } else if (b != '\r') {
                consecutive = 0;
            }
        }
        return Optional.of(bad.apply("the request head is larger than " + HEADER_LIMIT + " bytes"));
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
            if (colon < 0 || colon < target.lastIndexOf(']'))
                return bad("CONNECT needs host:port, got '" + target + "'");
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
            // An IPv6 address is in brackets and full of colons, so its port can only follow "]".
            int colon = authority.lastIndexOf(':');
            if (colon < authority.lastIndexOf(']')) colon = -1;
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
    private static final Pattern METHOD = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,32}");
    /// A host name, an IPv4 address, or an IPv6 address in brackets.
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]");

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
            lines.offer(JsonUtil.object().put("ts", Instant.now().toString())
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
